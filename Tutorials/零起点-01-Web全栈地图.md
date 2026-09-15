# 零起点-01 · Web 全栈地图：一个 curl 请求的一生

> 三行件头：①要点——不写一行业务代码，用"追踪一个请求"把全栈 8 个站点一次看全；②前置——会 C/C++/Python，Java 零基础没关系；③产出——能背出接线表、能复现一次"kill 掉 Redis 后 smoke 报错"的事故。
>
> 🗺 主线进度：第 1 站（全册约 65 站，本站是地图） | 🎞 上一站：无（入口） | 📀 本站所得：一张运行中的全栈 + 一份能复跑的故障实录。

## 名词卡（本站出现的词，后面每章展开）

| 名词 | 人话解释 | 在本项目的化身 |
|---|---|---|
| **浏览器** | 替你发 HTTP 请求、画返回页面的软件 | Chrome / Firefox |
| **HTTP** | 一问一答的"传话格式" | 所有接口的底层协议（Z02 专讲） |
| **前端** | 跑在浏览器里，负责"好看 + 好点" | `frontend/train-ui`、`takeout-ui`（Vue3） |
| **后端** | 跑在服务器上，负责"规矩 + 数据" | 5 个 Java 服务（8081~8085） |
| **API** | 后端开给前端的"窗口" | `/api/trips`、`/api/bookings` |
| **JSON** | 像 Python 字典的文本格式 | `{"id":1,"trainNo":"G1024",...}` |
| **数据库** | 断电不丢的结构化存储 | H2 文件库 `data/*.mv.db` |
| **端口** | 一台机器上区分服务的门牌号 | 8081~8085、9090、6379、9092 |
| **Nginx** | 门卫：能发静态文件，也能转交请求 | 9090 汇总站 |
| **Redis** | 内存里的超快键值仓库 | 余票、计数器、分布式锁 |
| **Kafka** | 发布/订阅的消息广播系统 | 下单事件广播 |
| **smoke** | 每次改动后的"验收巡场脚本" | `python3 infra/smoke.py`（24 条断言） |

---

## 1. 问题驱动开场：一个 curl 请求的一生

你在终端敲下这一行，回车：

```bash
curl http://127.0.0.1:9090/apitrain/trips
```

接下来 200ms 内，这封"信"在影视城里走完五站。本站的任务：**把这五站小孩走大人都能指出来**。先跑起来再看（全栈没起的话先 `bash infra/start-all.sh`，下文有它的真跑输出）：

```
 浏览器/curl            Nginx(9090)            train(8084)           Redis(6379)
   │ GET /apitrain/trips │                       │                     │
   │────────────────────>│ GET /api/trips        │                     │
   │                     │──────────────────────>│ GET train:trip:1:stock
   │                     │                       │────────────────────>│
   │                     │                       │<────── "3" ──────────│
   │                     │   JSON 车次数组        │ （顺带查 H2 档案室）  │
   │<────────────────────────────────────────────│                     │
   │ HTTP 200 + JSON     │                       │                     │
```

一句话总结冷启动分工：**前端"说人话"，后端"讲规矩"，H2"记住"，Redis"快"，Kafka"喊话"，Nginx"引路"。**

## 2. 概念最小人话 + 术语对照

一句话：Web 后端 = 一群守在固定端口上的"进程窗口"，约定格式（HTTP）递条子，按格式（JSON）回执。生活类比到此为止（最多三行的规矩不能破）。

## 3. 真实代码走查：start-all.sh 与 smoke.py

本项目所有服务由 `infra/start-all.sh` 拉起（65 行，四段式）。先看骨架（行号已核对源文件）：

```bash
# infra/start-all.sh
 9: echo "== 1) Redis =="
11: redis-cli -p $REDIS_PORT PING 2>/dev/null || ( ...拉起 redis-server... )
19: echo "== 2) Kafka (KRaft 单节点) =="
20: if ! (echo > /dev/tcp/127.0.0.1/$KAFKA_PORT) 2>/dev/null; then   ← 土法 TCP 探活
30:   nohup "$KAFKA/bin/kafka-server-start.sh" ... &                ← 日志进 logs/kafka.log
37: echo "== 3) 后端 ×5 =="
38: start_jar() { # name port jarfile
40:   (echo > /dev/tcp/127.0.0.1/$port) 2>/dev/null && { echo "  $name 已在线"; return; }
42:   nohup "$JAVA_HOME/bin/java" -jar "$jar" > "$ROOT/logs/$name.log" 2>&1 &
59: echo "== 4) Nginx（静态站 + 反代）=="
60: "$ROOT/infra/nginx-reload.sh"
```

为什么这样写：**第 11/40 行"先探活再启动"是幂等的关键**——脚本跑多少遍，活着的进程不会被再起一份。第 42 行是全文核心动作：`java -jar` 把 fat jar 跑起来（Z04 讲 jar 是怎么来的），标准输出全部重定向进 `logs/`.

验收员是 `infra/smoke.py`（162 行）。地址表（第 8~14 行）：

```python
BASE = {
    "todo":    "http://127.0.0.1:8081/api",
    "chat":    "http://127.0.0.1:8082/api",
    "counter": "http://127.0.0.1:8083/api",
    "train":   "http://127.0.0.1:8084/api",
    "takeout": "http://127.0.0.1:8085/api",
}
NG = "http://127.0.0.1:9090"
```

`expect()` 函数（第 51 行起）条件式断言：发请求 → 比对状态码 → 打 ✓/✗。覆盖 5 组共 24 条：基础设施 2（Kafka 可达 + 余票复位）、排练厅 5、票务 9、外卖 4、大门 4。**第 111 行的 `time.sleep(1.5)` 是给 Kafka 异步链路留的时间**——异步测试不睡一觉就是测试作者自己的锅。

### 接线表（抄在手边，全书通用）

| 站点 | 端口 | 角色 | 关键路径 |
|---|---|---|---|
| demo-todo | 8081 | 排练厅：第一个 CRUD | `GET/POST /api/tasks` |
| demo-chat | 8082 | 排练厅：SSE 服务器推送 | `GET /api/chat/stream` |
| demo-counter | 8083 | 排练厅：Redis 计数/秒杀 | `GET /api/counter` |
| train | 8084 | ★ 票务大厅 | `/api/trips`、`/api/auth/*`、`/api/bookings/*` |
| takeaway | 8085 | ★ 外卖后厨 | `/api/menus`、`/api/orders/*` |
| Nginx | 9090 | 大门引导台 | `/train-ui/`、`/apitrain/*`、`/apitakeout/*` |
| Redis | 6379 | 计分板 | `train:trip:{id}:stock` 等 key |
| Kafka | 9092 | 广播室 | topic：`train-order-events`、`takeout-order-events` |

## 4. 工程实录：真实问题与解决

### 实录 4.1：a) kill 完 Redis，smoke 会怎么报错；b) 重启恢复

这是全册第一个"人为事故"：**后端是 Redis 的客户端，Redis 死了，依赖它的接口不会"优雅降级"，而是当场挂掉**。

```bash
# ① 现场作案：干掉 Redis（valkey）
$ pkill -x 'redis-server'
$ (echo > /dev/tcp/127.0.0.1/6379) 2>/dev/null && echo up || echo down
down
```

```bash
# ② 跑验收：smoke 现场报错（本机实测记录）
$ python3 infra/smoke.py
  ✓ 货架补满（1-5 号菜 stock=5）          ← 这些断言直接调 redis-cli，看似"没事"
  ✓ kafka 9092 在线
  ✓ todo 新建 / ✓ todo 完成翻转 / ✓ todo 删除    ← demo-todo 不用 Redis
  ✗ counter 自增 (code=-1, want=200) timed out  ← demo-counter 全靠 Redis
  ✗ counter 返回 n
  ✗ 车次列表 (code=-1, want=200) timed out      ← train 的余票在 Redis
  ✗ 车次缺余票字段
  ✓ 注册 / ✓ 登录+JWT                            ← 用户走 H2，不受影响
  ✗ 下单(UNPAID) (code=-1, want=200) timed out   ← 下单要抢 Redis 分布式锁
  ✓ 审计(Kafka 消费记录) / ✓ Kafka audit 台有记录(5 条)
  ✗ 支付 (code=500,...) {'status': 500, 'error': 'Internal Server Error', 'path': '/api/bookings/pay'}
  ✗ 取消结算余票 (code=500,...)
  ...
Traceback (most recent call line 137 in smoke.py ... AttributeError
```

这段"一红一绿"混合的失败才是真实世界：**挂掉的不是全栈，是"所有依赖 Redis 的链路"**。
排查路径（三步走）：
1. 状态码 `-1` = 连接层超时（Python 侧 `timeout=6` 的语义），`500` = 后端自己炸——先怀疑共享依赖；
2. 三条链路（counter/stock/lock）共同点：Redis，`ss -tlnp | grep 6379` 一看，6379 没了；
3. 更深一层证据在日志：`logs/takeaway.log` 里 Lettuce 客户端会喊 `Cannot reconnect to [127.0.0.1/<unresolved>:6379]: Connection refused`——进程没死，是它的**朋友**死了。

```bash
# ③ 重启恢复：start-all 的探活会跳过活着的，只补死掉的（幂等）
$ bash infra/start-all.sh
== 1) Redis ==
redis: PONG                     ← 只把 Redis 拉了起来
...
$ python3 infra/smoke.py
  ...
smoke: 24 通过 / 0 失败
```

追问一句值得：既然 Redis 又活了，为什么 **takeaway 还倒下过**？（本机实跑时曾出现：Redis 恢复后 takeaway 进程跟着死透，`8085 down`）
答案藏在 `logs/takeaway.log` 的收尾：Lettuce 有断线重连，但业务层抛出的异常**没有兜底**，一路传到线程池把任务线程炸死；同一个服务的其他线程也陆续被拖垮。修复思路：业务层 catch 或让缓存降级——但这属于 S07 全局异常课题，此处只用它记住一句：**缓存这根水管被拔了，不要假设"插回去一切自动复原"，进程也可能已经陪葬。**

### 实录 4.2：start-all 的"真跑输出"与幂等

```bash
$ bash infra/stop-all.sh && bash infra/start-all.sh
== 1) Redis ==
redis: PONG
== 2) Kafka (KRaft 单节点) ==
kafka: up
== 3) 后端 ×5 ==
  demo-todo: up (8081)
  demo-chat: up (8082)
  demo-counter: up (8083)
  train: up (8084)
  takeaway: up (8085)
== 4) Nginx（静态站 + 反代）==
nginx: up (9090)

全部就绪（或见上报错）。入口：
  - 开发前端(可选)：cd frontend/train-ui && npm run dev
  - Nginx 汇总站：http://127.0.0.1:9090 （/train.html 或 / 控制台索引）
```

**紧接着再跑第二遍**，输出会变成一行行"已在线"——这就是第 40 行探活命中直接 return 的效果：

```
  demo-todo 已在线（8081）
  demo-chat 已在线（8082）
  ...
```

注意一个陷阱（本机真踩过）："已在线"说的是**端口**有人听，不是"那个端口后面还是原来的进程"。野进程占住 8081 时，start-all 也会满脸真诚地打印"demo-todo 已在线"——Z03 的工程实录会现场复现这个戏码。

说说几个"地图上能摸到"的真实代码点，先把"读后感"垫进下一站：

### demo-chat：服务端也能主动说话（SSE，先混脸熟）

`backend/demo-chat/src/main/java/com/javaweb/chat/ChatController.java`（节选，行号已核）：

```java
27:     public SseEmitter stream() {
33:             em.send(SseEmitter.event().name("history").data(history));  // 先补历史
35:         viewers.add(em);         // 记住这个"观众席"
```

`stream()` 返回的 `SseEmitter` 挂着不断开——浏览器收到 `Content-Type: text/event-stream` 后处于"持续收听"；`history` 先补发一段，随后每条新消息经 `broadcast()` 推给全部 viewers。Z02 的课堂会拿它做实验。

### train 站点为什么重要：它是编排的样板

`backend/train/src/main/java/com/javaweb/train/service/BookingService.java:36-56`（下单主链，节选）：

```java
42:         Boolean locked = redis.opsForValue()
43:                 .setIfAbsent(lockKey, "1", Duration.ofSeconds(10));   // 1) Redis 抢锁
48:             Long stock = redis.opsForValue().decrement(stockKey);      // 2) 原子扣票
55:                 throw new IllegalStateException("已售罄");
70:             b = bookings.save(b);                                      // 4) H2 落库
72:             producer.orderCreated(...);                                // 5) Kafka 广播
```

五个站点在 30 行里都露脸：Redis（锁+扣票）、H2（落 ORDER 单）、Kafka（广播事件）。**一句话记住：下单链 = Redis 扣数 + H2 存单 + Kafka 广播**——T 系列整段把这条链拆开七步。

## 5. 模式对比 / 选型表

| 数据 | 放哪 | 为什么 |
|---|---|---|
| 长期要"记住"的（用户、订单、车次） | H2/数据库 | 磁盘 + 事务，断电不丢 |
| 高频变但不许错的（余票、计数） | Redis | 内存单线程原子操作，一秒十几万次 |
| "喊一嗓子谁关心谁听"（下单事件） | Kafka | 解耦：下单方不用等派单方 |
| 静态文件（Vue 打包产物） | Nginx 直接发 | 后端不必掺和"发文件" |

## 6. 动手验证（自己跑一遍 + 预期输出逐行对账）

1. `bash infra/stop-all.sh && bash infra/start-all.sh` → 逐行对照实录 4.2（8 个 up）。
2. `curl http://127.0.0.1:9090/apitrain/trips` → 返回 JSON 数组，含 `stock` 字段；与直连 `curl http://127.0.0.1:8084/api/trips` 内容一致（Nginx 只转手）。
3. `python3 infra/smoke.py` → 收尾 `smoke: 24 通过 / 0 失败`。
4. （有余力）亲手复演实录 4.1 的"杀 Redis → 复盘 smoke → 重启"完整一轮。
5. 打开浏览器访问 `http://127.0.0.1:9090/train-ui/`，F12 Network 标签点一下"查询车次"——亲眼看看 Z01 图里那封请求的出入境。

**预期输出速查**（跑不对就回头重看对应段落）：

| 步 | 命令锚点 | 期望 |
|---|---|---|
| 1 | start-all 尾段 | 8 行 up/已在线 + "全部就绪" |
| 2 | curl 9090 | JSON 数组 + stock |
| 3 | smoke | `smoke: 24 通过 / 0 失败` |
| 4 | 复演实录 | 中间 8 条左右红、恢复后全 24 绿 |

## 思考题

1. 为什么余票放 Redis 不放 H2？（提示：抢票时谁先被读到几百次？）
2. 浏览器访问 `http://127.0.0.1:9090/apitrain/trips`，Nginx 转发后 train 收到的路径是什么？
3. `smoke.py` 为什么要 `time.sleep(1.5)` 再查审计台？删掉会怎样？
4. 实录 4.1 里为什么"注册/登录"没挂而"支付"挂了？（对照接线表想依赖）

## 练习题

**练习 1**：不查表，默写接线表 8 行（站点/端口/角色）。
**练习 2**：用 curl 分别访问 8081、8083 的一个接口，命令和返回贴出来。
**练习 3**：亲手先手动 `redis-server` 起一份再跑 start-all，观察第 1 段打印什么、为什么不再起新进程。总

### 完整参考答案

**思考 1**：余票是"高频读 + 高频写 + 不许错"。数据库读写走磁盘日志与事务；Redis 内存单线程原子操作，O(1)。分工不是替代：H2 终审对账，Redis 现场计数。
**思考 2**：`/api/trips`。`infra/nginx-reload.sh` 里 `location /apitrain/ { proxy_pass http://127.0.0.1:8084/api/; }` —— 前缀替换。
**思考 3**：下单事件走 Kafka 是异步链路：producer 丢进 topic 即返回，消费者稍后才落库审计台。不留缓冲就断言"有记录"大概率扑空——测试异步的标准手法（轮询或留缓冲）。
**思考 4**：注册/登录只摸 H2（用户表）；支付/取消要走 Redis 结算余票。这正是"分层三件套"在验收上的意义。
**练习 2 参考**：

```bash
$ curl http://127.0.0.1:8081/api/tasks
[]
$ curl http://127.0.0.1:8083/api/counter
{"n":6}
```

**练习 3**：start-all 第 11 行先 `redis-cli PING`，PONG 直接短路跳过启动分支，只打印 `redis: PONG`。"先探测再启动"让脚本是幂等的（跑多少次结果一样）。

---

## 附：常用命令一页纸（本站所得的随身版）

```bash
# 全栈
bash infra/stop-all.sh && bash infra/start-all.sh     # 停了再起（幂等）
python3 infra/smoke.py                               # 24 条断言验收
# 直连各站
curl http://127.0.0.1:8081/api/tasks                  # 排练厅：todos
curl http://127.0.0.1:8083/api/counter                # 排练厅：计数
curl http://127.0.0.1:9090/apitrain/trips             # 汇总门：全栈入口
# 中间件
redis-cli -p 6379 get train:trip:1:stock              # 计分板直接问
```

注意：所有命令都默认先 `bash infra/start-all.sh`；`smoke` 红时会先"补满货架"再测（smoke.py:59-72 的自预置逻辑），这是本站的"无痕实验"设计。

---

## 本节小结
- 一个请求的五站旅程：浏览器 → Nginx(9090) → Spring Boot(8084) → 数据层（H2+Redis）→ 原路返回。
- 本项目 8 个站点：3 排练厅（8081~8083）+ 2 正片（8084/8085）+ Nginx + Redis + Kafka。
- start-all 靠"端口探活"幂等；smoke 用 24 条断言验收，**红绿的颜色即依赖关系**。
- kill Redis 不会全栈陪葬，但"所有依赖 Redis 的链路"当场挂；重启即恢复，不保证万无一失（进程可能跟着去了）。

## 下一站

[零起点-02-HTTP速成.md](零起点-02-HTTP速成.md)——把"传话格式"HTTP 拆开揉碎：请求行、状态码、请求头、方法语义，并用三种 method 打同一个 URL 看状态码差异。
