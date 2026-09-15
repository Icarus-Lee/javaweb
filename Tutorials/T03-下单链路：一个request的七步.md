# T03 · 下单链路：一个 request 的七步（lock→decr→seq→save→Kafka→window）

> **本节要点**：`book()` 只有 42 行，却是一条七步流水线：抢锁→扣票→发座位→查档案→落库→广播→还锁。每一步都可能死，每一步的死法都对应一种"善后"——本篇把七步逐个拆开，标出每步的失败模式与回滚代价，并用 try/finally 讲透"锁的释放保证"这一分布式系统的生死约定。
> **前置知识**：T01（三表与状态机）、T02（DECR 与负数守卫）、S11（事务与并发）。
> **产出**：能默画七步流水线；能对每步说出"死了会留下什么残局、代码怎么收"；能向别人解释为什么还锁必须放 finally、为什么锁要带 10 秒自动过期。

---

**本站名词卡**

| 名词 | 英文 | 一句人话 | 在本项目哪里见到 |
|---|---|---|---|
| 分布式锁 | distributed lock | 跨进程的"占用茅坑"标记 | `setIfAbsent(lockKey, "1", 10s)` |
| setIfAbsent | SET NX | **不存在才写入**，原子判重 | `book()` 第 42~43 行 |
| 锁过期时间 | TTL | 防死锁的人质时限：人走门自动开 | `Duration.ofSeconds(10)` |
| finally 保证 | - | 无论成败必执行的收尾块 | 第 74~76 行 |
| 幂等回补 | - | 把做坏的操作原样撤销 | DECR 负数时 INCR |
| 事件广播 | event publish | 把"已发生的事实"写给 Kafka | `producer.orderCreated(...)` |
| 竞态窗口 | race window | 多个请求同时抢的瞬间 | 锁保护的就是它 |
| 拒绝码 | - | 两种 500：锁竞争 vs 已售罄 | 日志实锤见 T02 |

---

## 1. 生活类比与动机：火车站售票窗口的七道工序

### 是什么

一次下单＝一次完整过闸：**抢锁（进闸）→ 扣票（取票）→ 发号（排座）→ 查档案（对车次）→ 落库（登记造册）→ 广播（通知下游）→ 还锁（出闸）**。七步串行，一步不少。

### 为什么这么设计

- **锁**保证同一车次一秒内只有一个人在动账——把并发串行化；
- **DECR** 保证票不卖超（原子扣减）；
- **顺序不能换**：先扣票再发座（座位号跟着票走）、先落库再广播（事件描述已发生的事实）。交换任何两步都会制造新竞态——课后思考题留给第 8 节。

### 七步总览（对照源码行号）

```
1) 抢锁   :42-46   setIfAbsent + TTL 10s      死法：锁被占 → 拒绝"手速太快"
2) 扣票   :49-56   DECR + 负数守卫             死法：变负 → INCR 还票 + 拒绝"已售罄"
3) 发座   :58      INCR seq                    死法：无（Redis 在线即成）
4) 查档案 :60-61   H2 找车次                   死法：车次不存在 → 已扣的票要还（见 4.4）
5) 落库   :63-70   new Booking + save          死法：DB 挂 → 已扣的票悬空（见 4.5）
6) 广播   :72      Kafka send                  死法：Kafka 挂 → 事件丢（教学接受，见 4.6）
7) 还锁   :74-76   finally delete              死法：进程崩 → TTL 兜底（见 5.3）
```

---

## 2. 第 1 步：抢锁——setIfAbsent 的原子性

```java
37: String stockKey = "train:trip:" + tripId + ":stock";
38: String lockKey  = "train:trip:" + tripId + ":lock";
39: String seqKey   = "train:trip:" + tripId + ":seq";

41: // 1) 谁先 set 成功谁锁门（10 秒自动解锁，防"人走门忘关"）
42: Boolean locked = redis.opsForValue()
43:         .setIfAbsent(lockKey, "1", Duration.ofSeconds(10));
44: if (locked == null || !locked) {
45:     throw new IllegalStateException("手速太快，请重试（车次处理中）");
46: }
```

三个要点：

1. **锁的粒度是"车次"不是"全站"**：`lockKey` 带 tripId——G1024 上锁不影响 G77 卖票。粒度越细并发度越高，这是分布式锁设计的第一决策。
2. **`setIfAbsent` = Redis 命令 `SET key value NX EX 10`**：判断"不存在"和"写入"是一个原子动作，不存在"两个请求同时看到没锁、然后都写入"的中间态。若拆成 `GET` + `SET` 两步就是经典竞态（S08/A08 的 lost update 换了个马甲）。
3. **TTL 10 秒是买保险**：万一第 7 步还锁前进程崩了，锁 10 秒后自动蒸发，系统自愈。没有 TTL 的锁 = 死锁制造机（人走了门焊死，全车次永久瘫痪）。

**锁竞争的实测面目**（T04 的 10 线程实录）：10 个请求同时到达，1 个抢到锁继续走，9 个在第 45 行被弹回，HTTP 500 + `手速太快，请重试（车次处理中）`。**注意这不是错误是拒绝**——生产上应返回 429/409 让前端弹"稍后再试"，教学版借道全局异常走了 500（改进练习见第 8 节）。

---

## 3. 第 2~3 步：扣票与发号——两个原子操作各司其职

### 3.1 扣票（DECR + 负数守卫）

```java
48: // 2) 原子扣票：DECR，结果为负即"卖重复了"，必须还回去
49: Long stock = redis.opsForValue().decrement(stockKey);
...
53: if (stock < 0) {
54:     redis.opsForValue().increment(stockKey);   // 还票
55:     throw new IllegalStateException("已售罄");
56: }
```

T02 已精读，此处只补**在锁内执行的意义**：DECR 本身原子、不需要锁；锁包住它是为了让"扣票→发座→落库"三步**作为整体**不被交错。比如：没有锁时，A 扣了票还没落库，B 也扣票发座，两人的座位号序列/库存对账就会错位。**锁把"多步事务"的中间态藏起来了**。

### 3.2 发号（INCR seq）

```java
57: // 3) 座位号：INCR 序列分配（教学版从 1 号开始）
58: Long seat = redis.opsForValue().increment(seqKey);
```

每个车次一个计数器（`SeedRunner` 第 44 行初始化为 0），每卖一张 +1。实测：trip 1 今天卖出了 14 张票（含回补重卖），`GET train:trip:1:seq` 返回 `14`——**发号器只增不减**，取消的订单不收回座位号（重卖的票拿新号，真实 12306 也如此：座位复用但票号全新）。

---

## 4. 第 4~6 步：落库与广播——失败模式逐个盘

### 4.1 第 4 步查档案（:60-61）

```java
60: TrainTrip trip = trips.findById(tripId)
61:         .orElseThrow(() -> new IllegalArgumentException("车次不存在"));
```

死法：tripId 是伪造的（前端被黑直接打 API）。**残局**：已经扣了 1 张票（第 2 步成功）！车次不存在，这 1 张票永远悬空——stock 白白少了 1，且无人回补。**这是七步里第一个"真残局"**，修法见 4.4。

### 4.2 第 5 步落库（:63-70）

```java
63: Booking b = new Booking();
64: b.orderNo = java.util.UUID.randomUUID().toString();
65: b.userId = userId;
66: b.tripId = tripId;
67: b.seatNo = seat.intValue();
68: b.status = "UNPAID";
69: b.createdAt = Instant.now();
70: b = bookings.save(b);          // 4) 落库（H2）
```

死法：H2 挂了/磁盘满。**残局**：票已扣、座已发、库没进——stock 少 1、seq 白跳 1。**为什么不用 `@Transactional` 一裹了之**：因为 Redis 的 DECR **不参与 JPA 事务**——数据库回滚了，Redis 的扣减回不来（跨存储无分布式事务，这是本教学的核心诚实点）。所以七步链路的"回滚"全是**手写补偿**，而不是注解魔法。

### 4.3 第 6 步广播（:72）

```java
71: // 5) 广播给下游：本课的 Kafka 事件先给"审计台"盖个戳
72: producer.orderCreated(b.orderNo, tripId, b.seatNo, trip.priceYuan, "CREATED");
```

`OrderEventProducer.java` 第 16~20 行把事件拼成 JSON 发到 `train-order-events` 主题，**key=orderNo**（同一订单的事件进同一分区，保序）。死法：Kafka 不可达 → `kafka.send` 抛异常 → 请求 500，**但订单已落库**！用户看到失败却其实占到了座（票已扣、单已成）——最阴的一种不一致。生产修法：发送失败重试/落"待发事件表"异步补偿；教学版的取舍：Kafka 由 start-all.sh 保证先起，视为基础设施可用。

### 4.4 补偿的完整性对照表（本篇核心产出）

| 死在哪 | 残局 | 本项目处理 | 生产正解 |
|---|---|---|---|
| 1 抢锁失败 | 无 | 直接拒绝（零残局） | 返回 429 让前端重试 |
| 2 扣票变负 | 无（INCR 还了） | 第 54 行 | 同左 |
| 4 车次不存在 | **stock 少 1** | ❌ 未处理（教学残局） | 补偿：catch 后 INCR 回票再抛 |
| 5 落库失败 | stock 少 1 + seq 跳号 | ❌ 未处理 | 同上：扣票后所有步骤包 try/catch 补偿 |
| 6 Kafka 失败 | 订单在、事件丢 | 接受（审计可能缺一条） | outbox 模式/重试队列 |
| 7 还锁前崩 | 锁占 10 秒 | TTL 兜底自愈 | 同左 + 锁值存"持有人身份"防误删 |

**教学态度**：残局 4/5 是刻意保留的"坑"——它们是学习补偿事务（compensating transaction）最好的标本。S11 讲"数据库事务回滚"，本篇讲"跨存储只能补偿"——两课合璧才是分布式一致性的完整图景。

---

## 5. try/finally：锁的释放保证（第 7 步的生死约定）

```java
47: try {
...     //  第 2~6 步全在 try 里
72:     producer.orderCreated(...);
73:     return b;
74: } finally {
75:     redis.delete(lockKey);         // 6) 归还锁（finally 保证"无论成败都还"）
76: }
```

### 5.1 为什么必须是 finally

第 2~6 步有**五处** throw（锁竞争、售罄、车次不存在、DB 异常、Kafka 异常）。任何一处抛出，若还锁写在 try 尾部，锁就永久卡死（10 秒内该车的所有请求全吃"手速太快"）。`finally` 的语义是 **JVM 级承诺**：try 块无论正常返回、异常抛出、甚至 `return` 中途——finally 必跑。这是 Java 异常体系送分布式锁作者的礼物。

### 5.2 finally 也靠不住的时刻（诚实边界）

`finally` 兜不住**进程整个消失**（kill -9 / 断电 / OOM）：代码根本没机会执行。这时 TTL 保险生效——锁最多占 10 秒自动蒸发。两层保险的分工：

- **finally**：管"代码活着但抛异常"（99% 的情形）；
- **TTL**：管"代码死了"（1% 的灾难）。

### 5.3 一个真实隐患：误删他人的锁

锁值是固定 `"1"`：若 A 的业务超 10 秒（锁已自动过期），B 抢到新锁干活；A 此时跑完 finally，`delete(lockKey)` 会**把 B 的锁删了**！修法：锁值存随机 ID，删之前 Lua 脚本校验"是我才删"。教学版 10 秒 TTL 远大于毫秒级业务，触发概率≈0，但**面试必考**——记住三件套：`NX`（互斥）+ `EX`（防死锁）+ 值校验（防误删）。

---

## 6. 动手验证：顺着七步走一遍真实请求（今天实录）

```
$ TOK=...   # 登录拿 JWT（AuthController :52 签发）
$ curl -s -X POST http://127.0.0.1:9090/apitrain/bookings \
    -H 'Content-Type: application/json' -H "Authorization: Bearer $TOK" -d '{"tripId":1}'
{"id":22,"orderNo":"e30ad5ca-0525-4118-a685-1d694ab58d35","userId":34,"tripId":1,
 "seatNo":14,"status":"UNPAID","createdAt":"2026-09-14T10:25:10.076379992Z","paidAt":null}
```

一次 200 背后七步全过。逐件验证残骸：

```
$ redis-cli -p 6379 get train:trip:1:stock        # 第 2 步的账
"2"
$ redis-cli -p 6379 get train:trip:1:seq          # 第 3 步的号
"14"
$ redis-cli -p 6379 exists train:trip:1:lock      # 第 7 步：锁已还（0=不存在）
(integer) 0
```

```
$ tail -1 logs/train.log                           # 第 6 步：事件已广播
[audit] {"orderNo":"e30ad5ca-0525-4118-a685-1d694ab58d35","tripId":1,"seatNo":14,"price":42,"phase":"CREATED"}
```

**七步的物证齐了**：stock 少了、seq 进了、锁没了、审计台上多了一行戳。下单请求从进门到出门全程毫秒级（curl 返回即落库完成），审计消费是事后异步（T05 的戏份）。

---

## 6.5 顺序为什么不能换：三道思考题拆竞态

第 1 节说"顺序即因果"，这里给三道反证题——把七步里任意两步交换，看会烂在哪：

**题 1：先落库再扣票（5↔2 交换）会怎样？**

落库成功、扣票时发现售罄 → 订单已存在但票没扣到 → **凭空多了一张"已占座"的订单**，库存却没少。回滚订单？跨存储又要手写补偿——比原顺序更糟。原顺序（先扣票）的哲学：**先把稀缺资源拿到手，再办手续**；拿不到资源，手续白办也无所谓（残局为零）。

**题 2：先还锁再落库（7↔5 交换）会怎样？**

锁一还，下一个请求立刻进来扣票发号——而上一单还没落库。若上一单最终落库失败要补偿（INCR 还票），账面出现"两单都以为自己扣到了票"的交错窗口。锁保护的正是**七步整体**，提前放人等于拆墙。

**题 3：先广播再落库（6↔5 交换）会怎样？**

Kafka 事件说"订单 X 已创建"，可 H2 里它还不存在——消费者（审计台）若立刻回查订单，扑空。事件必须描述**已发生的事实**（T05 的审计台账顺序因此不乱）。这也是事件驱动设计的第一戒律：**先落事实，再发消息**（outbox 模式的思想内核）。

**三题合参**：七步的顺序不是"代码风格"，是**资源获取 → 事实确立 → 对外广播 → 资源释放**的因果链——倒过来任何一环，都会制造需要更复杂补偿才能填的坑。

---

## 6.6 工程实录：真实问题与解决——把 stock key 删了再下单：第 2 步"DECR 前崩溃"实测 & 防御分支

**问题从哪来**：第 2 步的失败模式里最阴的是"DECR 根本没跑成"——即**key 都没来及动**就崩了。用 `redis-cli del` 把 `train:trip:{id}:stock` 拿掉，模拟"计数层整个不见了"（真实版型：重启未灌种子、运维 key 误删、Redis 实例半路换血）。然后抢一单，看系统怎么接。

### 复现实录（2026-09-15 全栈在线，trip 5 的 key 删干净）

```
$ redis-cli del train:trip:5:stock
(integer) 1
$ redis-cli mget train:trip:5:stock train:trip:5:seq train:trip:5:lock
1) (nil)          ← stock 已消失
2) "0"            ← seq 还在
3) (nil)          ← 没锁

$ curl -s -X POST http://127.0.0.1:9090/apitrain/bookings \
    -H 'Content-Type: application/json' -H "Authorization: Bearer $TOK" -d '{"tripId":5}'
{"timestamp":"2026-09-15T08:49:10.819+00:00","status":500,"error":"Internal Server Error","path":"/api/bookings"}
```

### 检查"善后物证"（这次请求给系统留了什么残局？）

```
$ redis-cli exists train:trip:5:lock
(integer) 0        ← 锁被 finally 还了——七步的"出闸"承诺没有破
$ redis-cli get train:trip:5:seq
"0"                ← 发号器没白跳：DECR 前的抛出把人留在了门外
```

**这不是运气，是代码里的守卫**：`book()` 第 49~51 行在 DECR 后有个专门接 null 的分支

```java
49:             Long stock = redis.opsForValue().decrement(stockKey);
50:             if (stock == null) {
51:                 throw new IllegalStateException("车次未初始化（stock key 缺失）");
52:             }
```

Spring Data Redis 对**不存在的 key** 做 DECR 返回 `null`（不是 0 也不是 -1）——`if (stock == null)` 正是专为"key 消失"准备的防御分支；没有它，请求会带着 NPE 下坠，把日志里最直白的病因染成最玄学的一坨。**宁可抛语义清楚的自定义异常，也不要用 NPE 蒙混**——这是七步里最小的一个"失败也要留字条"行动。

### 修复小 diff（教学作业：从"守住"升级到"自愈"）

守卫现状是"拒绝并报错"，有教学价值也够安全；生产味更浓的写法是**按额定座位重建计数**、本次继续拒绝（把重建与续买拆开，防并发重复初始化）：

```diff
     Long stock = redis.opsForValue().decrement(stockKey);
     if (stock == null) {
-        throw new IllegalStateException("车次未初始化（stock key 缺失）");
+        int seats = trips.findById(tripId)
+                .orElseThrow(() -> new IllegalArgumentException("车次不存在"))
+                .totalSeats;
+        redis.opsForValue().set(stockKey, String.valueOf(seats));   // 以 totalSeats 为唯一可信锚点重初始化
+        throw new IllegalStateException("车次计数已重建，请重试一次");  // 本次仍拒绝：避免并发重复初始化
     }
```

**为何以 `totalSeats` 为锚**：key 消失意味着你**永远不知道它消失前是多少**——唯一可信值是"额定座位"（历史订单都在 DB 里活着，DECR 侧只许按"没卖"口径重来）。如果对账例程（T06）也在线上， 对账例程还能给出第二个锚：`stock = totalSeats − count(UNPAID+PAID)` 的派生修正。

### 复原验证（补回 key 再抢）

```
$ redis-cli set train:trip:5:stock 4
OK
$ curl -s -X POST http://127.0.0.1:9090/apitrain/bookings ... -d '{"tripId":5}'
{"id":85,"orderNo":"3db05061-abe8-4b50-8de0-7861dc92b946","userId":77,"tripId":5,
 "seatNo":1,"status":"UNPAID","createdAt":"2026-09-15T08:49:11.343470847Z","paidAt":null}
```

走完七步的请求与删 key 之前的行为一致——**防御分支的另一端（恢复态）同样被实测过了**。

---

## 7. 常见坑清单

| 症状 | 根因 | 修法 |
|---|---|---|
| 并发下单库存打成负数 | 拒绝路径没 INCR 还票 | 第 54 行 |
| 全站所有车次都"手速太快" | 锁 key 忘带 tripId（全站一把锁） | 粒度到车次（第 38 行） |
| 偶发"锁卡死 10 秒" | 崩进程没走 finally | TTL 兜底（设计如此，非 bug） |
| 异常后库存永久少 1 | 补偿缺失（4.4 表） | 扣票后的步骤包 try/catch，catch 里 INCR |
| 订单落了库但审计没记录 | Kafka 发送失败被吞 | outbox/重试；教学版查 kafka.log |
| 删锁把别人的删了 | 锁值固定"1" | 随机值 + Lua 校验再删（5.3） |

---

## 8. 自测题（五分钟能答完）

1. `setIfAbsent` 一条命令干了哪两件事？拆成两步会发生什么？
2. 锁的 TTL 设 10 秒，业务只跑 50 毫秒——TTL 是不是太浪费？设 1 秒行不行？
3. 第 4 步"车次不存在"抛异常后，库存少了 1——怎么补？（写出修复代码位置）
4. `finally` 与 TTL 各兜什么场景？哪个兜"kill -9"？
5. Kafka 发送失败时订单已落库，用户看到 500——这是什么性质的问题？怎么治？

**参考答案**（自测后再看）：

1. "检查不存在"+"写入"原子合一（`SET NX EX`）；拆两步则两个请求可同时通过检查、双双写入——锁失效，回到裸奔。
2. 不行。TTL 必须覆盖"最坏业务耗时 + 崩溃后的恢复窗口"；1 秒内业务没跑完锁先过期，互斥失效、并发穿透。10 秒是"远大于业务、远小于事故"的教学取值。
3. 把第 4 步包进 try/catch，catch 里先 `redis.increment(stockKey)` 还票、再 `redis.delete(lockKey)` 后重抛——七步里"扣票之后"的所有步骤都需要同款补偿（本篇 4.4 表的两处 ❌ 就是留给你的作业）。
4. finally 兜"进程活着但抛异常/提前 return"；TTL 兜"进程整个没了"（kill -9、断电、OOM）。后者只有 TTL 能救。
5. 不一致（订单在、事件丢）。治法：outbox——订单与"待发事件"同事务落库，后台任务扫表补发 Kafka；教学版靠"基础设施先起"降低概率。

---

## 9. 本节小结

- 七步流水线：抢锁→扣票→发号→查档案→落库→广播→还锁，顺序即因果，不可交换。
- 锁三件套：`SET NX`（互斥）+ TTL（防死锁）+ 值校验（防误删）；粒度到车次。
- 每步的失败模式各有残局：锁竞争零残局、售罄靠 INCR 自愈、档案/落库失败会悬空库存（教学保留的补偿标本）、Kafka 失败丢事件（outbox 是正解）。
- `finally` 是代码活着的承诺，TTL 是代码死了的保险——两层保险缺一不可。
- 验证七步的四个物证：stock、seq、lock、audit 日志——一条 curl 后全部可查。

---

## 10. 下一站

七步链路里"抢锁"是单线程下的规则，可**真·并发**（10 个线程同一毫秒杀过来）锁到底挡不挡得住？票会不会卖超？T04《Redis 抢票锁实测》用 Python threading 打 10 路并发，把成功数、拒绝数、余票账本全部实测摊开——数字不会说谎。

## 练习题

- 练 1：把本章动手实验的参数改一档，预测输出再实测，把差异写下来。
- 练 2：设计一个"改坏条件"的反向实验，验证错误表现与预期一致。

