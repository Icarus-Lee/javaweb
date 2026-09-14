# 零起点-01 · Web 全栈地图：一封请求的完整旅程

> 全册 65 篇教程的第一站。这一站不写代码，只做一件事：**把你将要走进的这座"影视城"的地图画清楚**。

---

## 🎬 开篇三件套

### 1️⃣ 本站你走在大厅哪一格

把整个 `javaweb` 项目想象成一座**制片厂影视城**：

- **大门引导台**（Nginx，9090 端口）：所有游客从这进，引导台告诉你去哪个厅。
- **票务大厅**（train 服务，8084 端口）：真实业务一号主角——查车次、登录、买票、支付、退票。
- **外卖后厨**（takeaway 服务，8085 端口）：二号主角——点单、支付、广播室喊一嗓子派单骑手。
- **三间排练厅**（demo-todo 8081 / demo-chat 8082 / demo-counter 8083）：练基本功的小剧场，正片里用到的每个招式都先在这里排练。
- **翻牌计分板**（Redis，6379 端口）：余票数、计数器这类"必须瞬间读对"的数字挂在这。
- **广播室**（Kafka，9092 端口）：下单后"喊一嗓子"，谁关心谁去听（解耦）。
- **档案室**（H2 数据库）：用户、订单、车次这些要长期保存的数据存这。

本站你站在**影视城大门口**——手里还没有门票（不会发 HTTP 请求），但导游先把地图发给你：游客怎么进门、传单怎么流转、每个厅在哪个位置。

### 2️⃣ 本站任务单

读完本篇，你要能不看笔记回答：

1. 浏览器里敲一个网址后，数据经过了哪 5 站？
2. 本项目一共起了几个服务？各在哪个端口？
3. `infra/start-all.sh` 一键启动时，屏幕上会打印什么？
4. `infra/smoke.py` 的 22 条断言都在验收什么？

### 3️⃣ 开工前自查

- [ ] 你会 C/C++/Python，但 Java 和 JavaScript 零基础（没关系，本册从零讲）
- [ ] 本机已装 JDK21、Maven、Redis、Nginx（README 说 pacman 装好的那套）
- [ ] 项目在 `~/Projects/javaweb`，`tools/` 下有 Kafka
- [ ] 终端能进项目目录即可，其余交给一键脚本

---

## 🗂 本站名词卡

| 名词 | 人话解释 | 在本项目的化身 |
|---|---|---|
| **浏览器** | 替你发 HTTP 请求、把返回的 HTML 画成页面的软件 | Chrome / Firefox |
| **HTTP** | 浏览器和服务器之间约定好的"传话格式"，一问一答 | 所有接口通信的底层协议 |
| **前端** | 跑在浏览器里的程序，负责"好看 + 好点" | `frontend/train-ui`、`takeout-ui`（Vue3 写的） |
| **后端** | 跑在服务器上的程序，负责"规则 + 数据" | 5 个 Java 服务（8081~8085） |
| **API** | 后端开给前端的"窗口"，按格式递条子就能办事 | `/api/trips`、`/api/bookings` 等 |
| **JSON** | 一种长得像 Python 字典的文本格式，前后端传数据的通用语言 | `{"id":1,"trainNo":"G1024",...}` |
| **数据库** | 断电也不丢的结构化存储 | H2 文件库 `data/*.mv.db` |
| **端口** | 一台机器上区分不同服务的"门牌号" | 8081~8085、9090、6379、9092 |
| **Nginx** | 高性能"门卫"，能发静态文件，也能把请求转交给后端 | 9090 汇总站 |
| **Redis** | 放在内存里的超快键值仓库 | 余票、计数器、分布式锁 |
| **Kafka** | 只管"发布/订阅"的消息广播系统 | 下单事件广播 |

> 💡 名词卡里的每个词后面章节都会展开，现在混个脸熟即可。

---

## 🧠 概念人话：一封请求的五站旅程

你在浏览器地址栏输入 `http://127.0.0.1:9090/apitrain/trips`，回车。接下来发生了什么？

```
 浏览器                 Nginx(9090)            train(8084)           Redis(6379)
   │  GET /apitrain/trips  │                       │                     │
   │──────────────────────>│  GET /api/trips       │                     │
   │                       │──────────────────────>│  GET train:trip:1:stock
   │                       │                       │────────────────────>│
   │                       │                       │<────── "3" ─────────│
   │                       │   JSON 车次数组        │  （顺带查 H2 档案室） │
   │<──────────────────────────────────────────────│                     │
   │  HTTP 200 + JSON      │                       │                     │
```

**第 1 站 · 浏览器**：把网址拆成"协议 + 主机 + 端口 + 路径"，装进一封格式严格的信（HTTP 请求）寄出去。

**第 2 站 · Nginx 引导台（9090）**：看到路径以 `/apitrain/` 开头，按配置表把 `/apitrain/` 换成 `http://127.0.0.1:8084/api/` 转身递进票务大厅——这叫**反向代理**。

**第 3 站 · Spring Boot 后端（8084）**：Java 写的服务。里面一个叫 `TripController` 的类接住请求，调业务逻辑。

**第 4 站 · 数据层**：车次基本信息从 **H2 档案室**（数据库）查，**实时余票**从 **Redis 计分板**读——注意，余票故意不放数据库，因为抢票时它每秒变几十次。

**第 5 站 · 原路返回**：后端把结果拼成 JSON，Nginx 原样转交，浏览器拿到 `HTTP 200` 和一串 JSON，画成页面。

一句话总结：**前端负责"说人话"，后端负责"讲规矩"，数据库负责"记住"，Redis 负责"快"，Kafka 负责"喊话"，Nginx 负责"引路"。**

---

## 🔍 真实代码走查：八个站点与一张接线表

本项目所有服务由一个脚本拉起。先看它的真实源码 `infra/start-all.sh`（65 行，下面是骨架）：

```bash
# infra/start-all.sh
 9: echo "== 1) Redis =="
11: redis-cli -p $REDIS_PORT PING 2>/dev/null || ( ...拉起 redis-server... )
19: echo "== 2) Kafka (KRaft 单节点) =="
20: if ! (echo > /dev/tcp/127.0.0.1/$KAFKA_PORT) 2>/dev/null; then
30:   nohup "$KAFKA/bin/kafka-server-start.sh" ... &
37: echo "== 3) 后端 ×5 =="
38: start_jar() { # name port jarfile
40:   (echo > /dev/tcp/127.0.0.1/$port) 2>/dev/null && { echo "  $name 已在线"; return; }
42:   nohup "$JAVA_HOME/bin/java" -jar "$jar" > "$ROOT/logs/$name.log" 2>&1 &
59: echo "== 4) Nginx（静态站 + 反代）=="
60: "$ROOT/infra/nginx-reload.sh"
```

读法（现在看不懂 bash 没关系，Z03 会逐行拆）：

- **第 11 行**：先 `PING` 探一下 Redis 在不在，不在才启动——脚本可以重复执行，不会把活着的服务再起一份。
- **第 20 行**：`echo > /dev/tcp/...` 是 bash 的土办法 TCP 探活，能连通说明端口有人听。
- **第 42 行**：核心动作——`java -jar xxx.jar` 把打包好的 Spring Boot 应用跑起来，日志重定向到 `logs/`。
- **第 60 行**：最后生成并加载 Nginx 配置，大门引导台开张。

### 接线表（谁在哪个端口）

把这张表抄在手边，全书都会用到：

| 站点 | 端口 | 角色 | 关键路径 |
|---|---|---|---|
| demo-todo | 8081 | 排练厅：第一个 CRUD | `GET/POST /api/tasks` |
| demo-chat | 8082 | 排练厅：SSE 服务器推送 | `GET /api/chat/stream` |
| demo-counter | 8083 | 排练厅：Redis 计数/秒杀 | `GET /api/counter` |
| train | 8084 | ★ 票务大厅 | `/api/trips`、`/api/auth/*`、`/api/bookings/*` |
| takeaway | 8085 | ★ 外卖后厨 | `/api/menus`、`/api/orders/*`、`/api/auth/login` |
| Nginx | 9090 | 大门引导台 | `/train-ui/`、`/apitrain/*`、`/apitakeout/*` |
| Redis | 6379 | 计分板 | `train:trip:{id}:stock` 等 key |
| Kafka | 9092 | 广播室 | topic：`train-order-events`、`takeout-order-events` |

### 验收员巡场：smoke.py 的 22 条断言

`infra/smoke.py`（148 行）是影视城的验收员，用 Python 模拟一位游客把全流程走一遍。开头定义了各站地址（第 8~14 行）：

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

它的核心是 `expect()` 函数（第 42 行起）：发请求 → 对比状态码 → 打 ✓ 或 ✗。22 条断言覆盖：

1. **基础设施**（1 条）：Kafka 9092 可达；
2. **排练厅**（5 条）：todo 增删改、chat 发消息、counter 自增；
3. **票务大厅**（9 条）：查车次→注册→登录拿 JWT→下单→审计台有 Kafka 记录→支付→取消；
4. **外卖后厨**（5 条）：alice 登录→点单→支付→等 Kafka 异步派单变 `DISPATCHED`；
5. **大门**（4 条）：两个静态站 200、反代 200、控制台首页 200。

---

## 动手验证

### 实验 1：一键起全栈（真实输出）

```bash
cd ~/Projects/javaweb
bash infra/stop-all.sh    # 先全停，保证从零开始
bash infra/start-all.sh
```

真实运行输出（本机实跑记录）：

```
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

> ⚠️ 真实世界的小插曲（也是最好的教材）：某次实跑时 Kafka 打印了 `DOWN`，查 `logs/kafka.log` 发现 `Port 9093: Address already in use`——旧 Kafka 进程还没死透新进程就来抢端口。处理办法：等几秒重跑 `start-all.sh`，或 `kill` 掉残留进程。**端口冲突是后端开发最常见的故障**，Z03 整篇都在讲它。

### 实验 2：验收员巡场

```bash
bash infra/smoke.sh
```

真实输出（22/22 全绿）：

```
== 基础设施 ==
  ✓ kafka 9092 在线
== demo-todo (8081) ==
  ✓ todo 新建 / ✓ todo 完成翻转 / ✓ todo 删除
== demo-chat (8082) ==
  ✓ chat 发消息
== redis 计数器 (8083) ==
  ✓ counter 自增
== train (8084) ==
  ✓ 车次列表 / ✓ 注册 / ✓ 登录+JWT / ✓ 下单(UNPAID)
  ✓ 审计(Kafka 消费记录) / ✓ Kafka audit 台有记录(5 条)
  ✓ 支付 / ✓ 取消结算余票
== takeaway (8085) ==
  ✓ 外卖登录(alice) / ✓ 点单创建 / ✓ 支付
  ✓ Kafka 异步派单（状态=DISPATCHED）
== nginx (9090) ==
  ✓ train 静态站 200 / ✓ takeout 静态站 200
  ✓ 反代 /apitrain → 8084 / ✓ 控制台首页 200

smoke: 22 通过 / 0 失败
```

### 实验 3：亲手当一次浏览器

```bash
curl http://127.0.0.1:9090/apitrain/trips
```

你会看到和直接问 8084 一模一样的 JSON——因为 Nginx 只是转了个手。打开浏览器访问 `http://127.0.0.1:9090/train-ui/`，F12 打开开发者工具的 Network 标签，点一下"查询车次"，亲眼看看那封请求长什么样。

---

## 思考题

1. 为什么余票数放在 Redis 而不是 H2 数据库里？（提示：抢票时谁先被读到几百次？）
2. 浏览器访问 `http://127.0.0.1:9090/apitrain/trips`，Nginx 转发后 train 服务收到的路径是什么？
3. `smoke.py` 为什么要 `time.sleep(1.5)` 再查审计台？如果删掉这行会怎样？
4. 如果 train 服务崩了，`/train-ui/` 页面还能打开吗？还能查到车次吗？

## 练习题

**练习 1**：不查表，凭记忆默写接线表的 8 行（站点/端口/角色）。

**练习 2**：用 `curl` 分别访问 8081、8083 的一个接口，把命令和返回贴出来。

**练习 3**：故意制造一次故障——`bash infra/stop-all.sh` 后只跑 `start-all.sh` 里 Redis 那一段前先 `redis-server &` 手动起 Redis，观察 start-all 是否跳过启动 Redis，并解释为什么。

### 完整参考答案

**思考题 1**：抢票场景下余票是"高频读 + 高频写 + 不许错"。数据库每次读写都要走磁盘日志和事务，Redis 在内存里单线程原子操作，一秒十几万次不在话下。数据库负责"最终对账"，Redis 负责"现场计数"——分工而非替代。

**思考题 2**：`/api/trips`。`infra/nginx-reload.sh` 第 59~61 行：`location /apitrain/ { proxy_pass http://127.0.0.1:8084/api/; }`——`/apitrain/` 前缀被替换成 `/api/`。

**思考题 3**：下单事件走 Kafka 是**异步**的：producer 把消息丢进 topic 就返回，审计台消费者要过一会儿才消费落库。不睡 1.5 秒就断言"审计台有记录"，大概率扑空——这是测试异步链路的标准手法（轮询或留缓冲）。

**思考题 4**：页面能打开（静态文件由 Nginx 直接发，不经过后端），但查车次会失败——`/apitrain/*` 转发到 8084 没人应答，浏览器收到 502。这正是"前后端分离 + 反代"的容错边界：门脸还站着，柜台塌了。

**练习 2 参考命令**：

```bash
$ curl http://127.0.0.1:8081/api/tasks
[]
$ curl http://127.0.0.1:8083/api/counter
{"n":6}
```

**练习 3**：start-all.sh 第 11 行先 `redis-cli PING`，PONG 说明已在线，直接跳过启动分支——所以手动先起 Redis 后，脚本只打印 `redis: PONG` 不再起新进程。这种"先探测再启动"的写法让脚本天然**幂等**（跑多少遍结果都一样）。

---

## 本节小结
- 一封请求的五站旅程：浏览器 → Nginx → Spring Boot → 数据层（H2+Redis）→ 原路返回。
- 本项目 8 个站点：3 排练厅（8081-8083）+ 2 正片（8084/8085）+ Nginx(9090) + Redis(6379) + Kafka(9092)。
- `start-all.sh` 靠"端口探活"实现幂等启动；`smoke.py` 用 22 条断言当验收员。
- 端口冲突是新手第一大坑，日志在 `logs/` 下，先看日志再慌。

## 下一站

[零起点-02-HTTP速成.md](零起点-02-HTTP速成.md)——把"传话格式"HTTP 拆开揉碎：请求行、状态码、请求头、Cookie，以及用 curl 一行命令实测每个知识点。
