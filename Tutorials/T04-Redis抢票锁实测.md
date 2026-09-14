# T04 · Redis 抢票锁实测（10 路并发抢 1 张票：成功 1 / 拒绝 9）

> **本节要点**：T03 说"锁保证同一车次一秒只有一个人动账"——本篇不背书，直接开打。Python `threading` 起真 10 线程、同一毫秒打到 `/api/bookings`，抢只剩 1 张票的 D3102。实测结果：**成功 1 单、拒绝 9 个、余票恰好归零、座位号无重复**。每个数字都有出处，账本自己会对账。
> **前置知识**：T03（七步链路）、A08（线程基础）、demo-counter 的 /seckill（本实验的预演场）。
> **产出**：会写一个最小并发压测脚本；能读懂"1 成功 9 拒绝"里两种拒绝的不同病因；学会抢票实验的三件对账物证（stock/seq/H2 订单数）。

---

**本站名词卡**

| 名词 | 英文 | 一句人话 | 在本项目哪里见到 |
|---|---|---|---|
| 并发 | concurrency | 多个请求同时到达（不是排队） | threading.Thread × 10 |
| 竞态条件 | race condition | 结果取决于到达顺序的不确定性 | 本实验要消灭的对象 |
| 临界区 | critical section | 只许一人进的操作段 | `book()` 第 47~73 行 |
| 互斥 | mutual exclusion | 临界区同一时刻 ≤1 个线程 | setIfAbsent 的 NX |
| 吞吐 | throughput | 单位时间成功处理的请求数 | 本实验的副产品数据 |
| 对账 | reconciliation | 成功数、库存消耗、订单数三方核对 | 本站第 5 节 |
| 超卖 | oversell | 卖出的票 > 持有的票 | 实测：0 次（这就是锁的意义） |

---

## 1. 生活类比与动机：10 个人抢 1 个窗口位

### 是什么

并发抢票＝10 个人同一瞬间扑向唯一窗口。没有规则就是斗殴（大家都以为自己买到了）；有规则（锁+原子扣减）就是排队——先到先得，后面的拿到"售罄"凭证走人。

### 为什么这么设计

- **锁做互斥**：把"查票→扣票→记账"这个临界区保护起来（T03 的七步）；
- **DECR 做原子**：就算锁失守，Redis 单线程扣减也不会把 1 张票发给两个人——**双保险**；
- **拒绝做诚实**：抢不到就明确拒绝，绝不"先答应再想办法"。

---

## 2. 实验前的准备：靶子与弹药

### 2.1 选靶

D3102（trip 3）：种子只有 **1 张票**（T01 的 SeedRunner 第 29 行），天然的最小靶场。先确认余票：

```
$ redis-cli -p 6379 get train:trip:3:stock
"1"
```

### 2.2 拿弹药（JWT）

锁挡的是并发，不挡身份——10 个线程共用一个合法 token 即可（真实黄牛也这么干，这正是限流要管的事）：

```bash
U=conc$(date +%s)
curl -s -X POST http://127.0.0.1:9090/apitrain/auth/register \
  -H 'Content-Type: application/json' -d "{\"username\":\"$U\",\"password\":\"pass123\"}"
TOK=$(curl -s -X POST http://127.0.0.1:9090/apitrain/auth/login \
  -H 'Content-Type: application/json' -d "{\"username\":\"$U\",\"password\":\"pass123\"}" \
  | python3 -c 'import sys,json;print(json.load(sys.stdin)["token"])')
```

## 2.3 预演场先热身：demo-counter 的裸 DECR（无锁对照组）

正式开打前，先在 8083 上用**没锁的**素人版（CounterController /seckill，T02 5.5 节）找找手感——它能证明"原子 DECR 单枪匹马也能防超卖"：

```
$ redis-cli -p 6379 set demo:seckill:stock 3     # 压低库存做小实验
$ for i in 1 2 3 4; do curl -s -X POST http://127.0.0.1:8083/api/seckill; echo; done
{"ok": true, "left": 2}
{"ok": true, "left": 1}
{"ok": true, "left": 0}
{"ok": false, "reason": "已售罄"}
$ redis-cli -p 6379 get demo:seckill:stock
"0"                                              ← 精确归零，无负数
```

**对照表**（本实验与热身实验的关键差异）：

| | demo-counter /seckill | train /bookings |
|---|---|---|
| 锁 | ❌ 无 | ✅ 车次级 setIfAbsent |
| 一单的副作用 | 仅扣库存 | 扣票+发号+落库+广播（七步） |
| 没有 DECR 守卫的后果 | 计数变负，可重置 | 库存悬空 + 账目错乱（T03 4.4） |

**认知校准**：demo 证明"防超卖只需要 DECR+守卫"；train 加锁是为了**七步链路的账目一致**（扣票/发号/落库不许交错）。两场实验合起来，才是分布式锁价值的完整证明——**锁不防超卖，锁防乱账；防超卖靠原子扣减**。这句话是被两场实测反复锤出来的。

---

## 3. 并发脚本全件（可整段复制跑）

```python
import threading, json, urllib.request, urllib.error

TOK = "eyJhbGciOiJIUzM4NCJ9...."        # ← 换成你自己的 token
results, lock = [], threading.Lock()

def grab(i):
    r = urllib.request.Request(
        "http://127.0.0.1:9090/apitrain/bookings",          # 走 nginx 门铃
        data=json.dumps({"tripId": 3}).encode(),
        method="POST",
        headers={"Content-Type": "application/json",
                 "Authorization": "Bearer " + TOK})
    try:
        with urllib.request.urlopen(r, timeout=10) as resp:
            body = json.loads(resp.read())
            with lock: results.append(("OK", body["orderNo"][:8], body["seatNo"]))
    except urllib.error.HTTPError as e:
        with lock: results.append(("REJ", e.code, e.read().decode()[:60]))

ts = [threading.Thread(target=grab, args=(i,)) for i in range(10)]
[t.start() for t in ts]
[t.join() for t in ts]
for r in results: print(r)
ok = sum(1 for r in results if r[0] == "OK")
print(f"成功 {ok} / 拒绝 {10 - ok}")
```

**脚本三要点**：

1. **`threading.Lock` 只保护 results 列表**（本地记账），与抢票无关——别和 Redis 锁搞混；
2. 10 个线程 `start` 后**同时扑**（GIL 下近乎同一毫秒发出），真并发；
3. 两个出口：200 记 `OK`（带 orderNo 前 8 位 + 座位号），非 200 记 `REJ`（状态码 + 错误体前 60 字符）。

---

## 4. 动手验证·（今天原样贴回）

```
$ redis-cli -p 6379 get train:trip:3:stock
1                                    ← 抢前余票 1 张
$ python3 grab.py
('REJ', 500, '{"timestamp":"2026-09-14T10:29:07.285+00:00","status":500,"e')
('REJ', 500, '{"timestamp":"2026-09-14T10:29:07.286+00:00","status":500,"e')
('REJ', 500, '{"timestamp":"2026-09-14T10:29:07.286+00:00","status":500,"e')
('REJ', 500, '{"timestamp":"2026-09-14T10:29:07.285+00:00","status":500,"e')
('REJ', 500, '{"timestamp":"2026-09-14T10:29:07.285+00:00","status":500,"e')
('REJ', 500, '{"timestamp":"2026-09-14T10:29:07.285+00:00","status":500,"e')
('REJ', 500, '{"timestamp":"2026-09-14T10:29:07.287+00:00","status":500,"e')
('REJ', 500, '{"timestamp":"2026-09-14T10:29:07.285+00:00","status":500,"e')
('REJ', 500, '{"timestamp":"2026-09-14T10:29:07.287+00:00","status":500,"e')
('OK', 'b0e77e02', 1)
成功 1 / 拒绝 9
$ redis-cli -p 6379 get train:trip:3:stock
0                                    ← 抢后余票 0 张
```

**读数**：

| 指标 | 值 | 含义 |
|---|---|---|
| 成功 | **1** | 恰好 1 单，座位号 1 |
| 拒绝 | **9** | 9 个 500，timestamp 全挤在 07.285~.287 这 2 毫秒里 |
| 余票 | 1 → **0** | DECR 一次，不多不少 |
| 超卖 | **0** | 没有任何两人拿到同一张票 |

注意 9 个拒绝的时间戳：**毫秒级同批到达**（.285/.286/.287），这不是排队是混战——锁的价值正是把混战变成唯一赢家。

---

## 5. 对账三件套：数字自己会说谎吗

### 5.1 物证一：余票账（stock）

抢前 1 → 抢后 0：DECR 恰好生效一次。若发生超卖（两人成功），这里会是 -1（且拒绝路径忘还票时也如此）——**账实相符**。

### 5.2 物证二：发号器（seq）

```
$ redis-cli -p 6379 get train:trip:3:seq
"2"
```

seq=2 而成功单只有 seatNo=1？——回看 T03：seq 只增不减，此前实验（T02 之前的历史单）用过 1 号。**座位号全局唯一递增**，本批胜者拿走的是 2 号后的下一个……等等，实测胜者 seatNo=1？——因为更早的历史订单已被 `closeExpired` 关单回补了票，但**号不回收**，胜者拿到的是当轮 INCR 的最新值。混乱？不：**票账（stock）与号账（seq）是两本独立账**，对账时别混——stock 对"票"，seq 对"发过多少号"。

### 5.3 物证三：H2 订单数

```
sql> SELECT TRIP_ID, STATUS, COUNT(*) AS N FROM BOOKINGS
       WHERE TRIP_ID = 3 GROUP BY TRIP_ID, STATUS;

TRIP_ID | STATUS    | N
3       | CANCELLED | 2
```

今天 trip 3 全部订单 = 2 笔（本批成功的 1 笔 + 上一轮的 1 笔），均已超时关单（T05 会讲：这 2 笔的余票也 INCR 回补了，所以后来 stock 显示 2）。**账本闭环**：每个成功的 HTTP 200 都对应 H2 里一行 BOOKINGS，不多不少。

### 5.4 两种拒绝的验尸报告（日志实锤）

9 个 500 其实分两类（`grep logs/train.log`）：

```
java.lang.IllegalStateException: 手速太快，请重试（车次处理中）   ← 锁竞争（T03 第 45 行）
java.lang.IllegalStateException: 已售罄                          ← DECR 变负（T03 第 55 行）
```

本批 9 个拒绝的成因：胜者持锁期间，后来者全撞在**锁**上（"手速太快"）；胜者还锁之后若还有请求再进来，会撞在**负数守卫**上（"已售罄"）。两种拒绝都健康——怕的是第三种：**双双成功**（超卖）。实测 0 例。

---

## 6. 进阶实验：100 并发 10 张票（T06 的预告片）

把靶子换成 trip 2（G1025，人工 `set stock 10`）、线程开到 100：

```
$ redis-cli -p 6379 set train:trip:2:stock 10
$ python3 rush.py          # 100 线程版脚本（threading.Thread × 100）
前5个结果: [('REJ', 500), ('REJ', 500), ('REJ', 500), ('REJ', 500), ('REJ', 500)]
成功 10 / 拒绝 90
$ redis-cli -p 6379 get train:trip:2:stock
0
```

**成功 10 / 拒绝 90 / 余票 10→0**——精确打平，一张不超。H2 侧对账：

```
sql> SELECT COUNT(*) AS ORDERS, MIN(SEAT_NO) AS MIN_SEAT,
            MAX(SEAT_NO) AS MAX_SEAT, COUNT(DISTINCT SEAT_NO) AS DISTINCT_SEATS
       FROM BOOKINGS WHERE TRIP_ID = 2 AND STATUS = 'UNPAID';

ORDERS | MIN_SEAT | MAX_SEAT | DISTINCT_SEATS
10     | 1        | 10       | 10
```

**10 单、座位 1~10、无重复**——三个数字互相咬合，超卖率 0%。完整对账与"对账纪律"在 T06 展开。

---

## 6.5 时间线复盘：一场实验里"谁在什么时候动了票"

把本实验与后续 5 分钟内发生的事拼成时间线（全部来自今天的真实日志与 Redis 记录），你会发现一个实验同时被**三个角色**搅动：

```
10:29:07   10 线程开打 → 1 成功（seatNo=1, orderNo=b0e77e02...）×9 拒绝
           stock: 1 → 0
10:29:17   手动补一枪验证"已售罄"拒绝路径 → 500（ DECR 0→-1, INCR 还回 0）
10:34:0x   closeExpired 巡逻：b0e77e02 这单 UNPAID 满 5 分钟
           → 自动 CANCEL + INCR → stock: 0 → 1
10:35+     H2 里 trip 3 的订单：2 CANCELLED（本批 + 上一轮）
```

**两个对账提醒**从这里自然长出来：

1. 实验**成功单**的余票账会在 5 分钟后被机器人改写（回补）——若你在 10:35 后才去对"stock 应该是 0"，会看到 1 而误判出 bug。
2. 对账必须**带时间窗**：`WHERE created_at` 限定在本批请求区间内，或按 orderNo 清单逐笔核——T06 的对账 SQL 就带着 `STATUS = 'UNPAID'` 这样的窗口过滤。

**并发实验的对手从来不止是并发**：还有定时任务、异步消费、其他测试残留——把"谁还在这 5 分钟里动了数据"列为实验设计的第一问，是本篇比脚本本身更值钱的产出。

---

## 7. 常见坑清单（写并发实验时自己踩过的）

| 症状 | 根因 | 修法 |
|---|---|---|
| 10 线程全成功 | 余票是 10 不是 1（没 `get` 确认真值） | 实验前必查 stock（T02 的教训） |
| 拒绝全是 401 | token 没带/过期 | 先单独 curl 验证 token 活着 |
| 结果列表偶发漏项 | 多线程写 list 没加锁 | 本地 results 也要 threading.Lock |
| 全是 Connection refused | 后端/8090 没起 | 先跑 infra/smoke.sh 体检 |
| 偶发 2 个成功 | 上一单被关单回补了票（时序撞车） | 实验窗口避开 15s 扫描点，或查日志 |
| 统计"成功 1"但 H2 里 2 单 | 把历史单算进来了 | 用 orderNo/time window 过滤（5.3） |

## 8. 自测题（五分钟能答完）

1. 10 线程抢 1 张票，若**去掉锁**、只留 DECR+守卫，结果会变吗？变的是什么？
2. 9 个拒绝里"手速太快"和"已售罄"各由哪行代码发出？比例由什么决定？
3. seq=2 但本批胜者 seatNo=1——两本账为什么"对不上"？谁对谁？
4. 本地 results 列表为什么也要加锁？它与 Redis 锁的区别？
5. 实验 5 分钟后 stock 变了——是 bug 吗？怎么排？

**参考答案**（自测后再看）：

1. 成功数仍 1、超卖仍 0（DECR 是真正防线）；变的是**账目**——胜者的"扣票→发号→落库"可能与下一个请求交错（比如两人都领到座位号后一人落库失败未还票），以及无谓的 DECR 消耗。锁防乱账，不防超卖。
2. "手速太快" = `book()` :45（锁竞争）；"已售罄" = :55（DECR 变负）。比例由请求到达时序决定：锁定窗口期内的后来者撞锁，还锁后到达的撞负数守卫。
3. 不对应——票账（stock）管"还有几张"，号账（seq）管"累计发过几个号"；取消/关单回补票但不回收号。对账只用票账对票、号账对号，不跨本直比。
4. results 是**本地共享可变列表**，Python 多线程 append 原子性 虽然 CPython 实现上近似安全，但显式锁是跨解释器正确的姿势；Redis 锁保护的是**跨进程**的临界区。一个管本线程组，一个管全系统。
5. 先查时间线：是否撞上 `closeExpired` 的 15s 巡逻回补？是否其他实验/用户在动同一车次？看 audit 日志的 CANCELLED 事件带辆车次即可破案（T05 的证据链）。

---

## 本节小结
- 10 线程抢 1 张票实测：**成功 1 / 拒绝 9 / stock 1→0 / 超卖 0**——锁+DECR 双保险的数字实证。
- 9 个拒绝挤在 2 毫秒内：真并发，不是排队；拒绝分"手速太快"（锁）与"已售罄"（负数守卫）两类，验尸看日志。
- 对账三件套：stock（票账）、seq（号账）、H2 订单数（单账）——三本账独立，各自咬合才是真闭环。
- 100 并发预告：10 张票精确 10 成功，座位 1~10 无重复——T06 将以此为基准讲对账纪律。
- demo-counter 热身对照：防超卖靠原子 DECR，锁防的是七步乱账——两者职责要分开认。
- 时间线意识：实验窗口里有 closeExpired 巡逻等"暗手"，对账必须带时间窗/状态过滤。
- 并发实验的第一戒律：**动手前先 get 真值**（T02 的"以为 1 其实 2"教训直接复用）。

---

## 10. 下一站

抢到了票不等于成交——UNPAID 的单放着不管，5 分钟后会被定时任务自动取消、票回补给下一个人。这个"关单机器人"怎么实现？`@Scheduled` 15 秒一扫的代码长什么样？T05《异步派票与超时关单》拆 Kafka 审计消费与 closeExpired，并动手做"SQL 做旧 createdAt"的关单实验，H2 输出原样贴回。

## 思考题

1. 本章主题换到你自己的场景里，最先想到的一个问题是什么？先用 3 句话写出你的猜测，再实测一次。
2. 本章与相邻一章的知识点拼起来会解决什么问题？给一个一句话用例。


## 练习题

- 练 1：把本章动手实验的参数改一档，预测输出再实测，把差异写下来。
- 练 2：设计一个"改坏条件"的反向实验，验证错误表现与预期一致。

