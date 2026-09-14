# R01 · Redis 是什么：内存里的胶水垫（KV 数据库、内存与持久化 RDB/AOF、为什么本项目用 valkey）

> **本节要点**：在动 train/takeaway 的 Redis 代码之前，先把"这玩意到底是什么"钉死：**一个把数据放在内存里的键值（KV）数据库**。它不是"替代 MySQL"，而是垫在数据库与业务之间的**胶水垫**——计数、余票、锁、限流都靠它。本章顺带回答两个现实问题：项目 `tools/` 里为什么没有 Redis（答案：本机用 **valkey**，一个兼容 Redis 协议的继任实现）；以及"内存一断电不全丢了吗"——`--save ''` 起来的纯教学实例到底丢了什么。
> **前置知识**：S08（配置抽象，`spring.data.redis` 从哪来）、S11（为什么下单要"原子扣减"）。
> **产出**：能用 3 句话向外行解释 Redis；能背出 RDB/AOF 的读音与差别；能现场敲出 `redis-cli` 五件套并看懂每条真实输出。

> 🗺 **主线进度**：`… S14 测试 ─ ▶R01 Redis 入门◀ ─ R02 五种结构 ─ …`
> 🎞 **上一站发生了什么**：S 系列收官，全栈五件套已经能一键起停。
> 📀 **本站你会得到**：
> - "KV / 内存 / 持久化"三个词在一张桌子上的合影
> - 本项目 Redis 从何而来：valkey（兼容实现）的环境走查
> - `redis-cli` 现场五条命令 + 全部真实输出

---

**本站名词卡**

| 名词 | 英文 | 一句人话 | 在本项目哪里见到 |
|---|---|---|---|
| KV 数据库 | key–value store | 万物皆"键 → 值"，不会走路只会藏宝图 | `train:trip:5:stock` |
| 内存数据库 | in-memory | 数据住在 RAM，访问 = 内存读，快十万倍量级 | valkey 9.1.2（`INFO server`） |
| 持久化 | persistence | 内存快照定期落盘，防断电清零 | `redis-server --save ''`（本例关了） |
| RDB | /ɑːr diː ˈbiː/ | "一键存照"：某时刻全量快照成二进制 dump.rdb | 被 `--save ''` 关掉的真实场景 |
| AOF | 同字母逐读：'e-oh-ef'（也有读 A-O-F） | "记账流水"：每条写命令追加日志，重放可复原 | 生产_recommended：appendonly yes |
| TTL | time to live | 验证码式"几秒后自动消失"的倒计时 | R02 `EX 5` 实测必出现 |
| valkey | - | Redis 社区 2024 分叉的兼容实现（协议/命令同源） | 本机 `/usr/bin/redis-cli` 打的是 valkey 9.1.2 |

---

## 1. 生活类比与动机：图书馆 vs 衣帽间

MySQL 是**图书馆**：所有书都有编号、索引、借阅记录——什么都能查，但每次都要穿过正门、翻卡片、盖章，成本是"一次磁盘 IO + 一整套事务纪律"。Redis 是**图书馆门口的衣帽间**：只干一件事——按小票号给你取东西或存东西，伸手就能摸到（内存），快得没法比，但**位子有限**（内存贵）且**本来就不是永久保管**（那是图书馆的活）。

后端一谈到 Redis，最容易犯两个极端错误：

1. **把它当主存储**：订单、库存全塞 Redis。错——Redis 的持久化是"尽力而为"，不是数据库的"账本级保证"。387 兆的 AOF 撑不起对账需求。
2. **完全不敢碰它**：由此产生了"扣库存靠 `SELECT` 再改"这类读-改-写错账（S11 第一节的那个 9/7/7 教训）。Redis 单线程 + 原子命令（DECR/INCR）恰好是用来补 DB 算术短板的。

**本项目三处真实用法**（提前点名，本章不展开）：

- train：余票 `train:trip:{id}:stock` + 座位序列 `:seq` + 分布式锁 `:lock`（R04/R05）
- takeaway：菜品库存 `takeaway:dish:{id}:stock`（下单原子扣减）
- demo-counter：计数器 `demo:counter` 与秒杀雏形（R03）

### 读音小条（面试总会问，别念错）

- **Redis**：/ˈredɪs/，重音在前，"瑞迪斯"，不是"R-E-D-I-S"拼这个词。官方注释里它是 **Re**mote **Di**ctionary **S**erver 的缩写。
- **RDB**：三个字母连读 /ɑːr diː ˈbiː/，中文口语也常整块念 "R-cli-B" 变形——**实测圈子里最常见是连读 "are-DB"**，跟人对话不纠结。
- **AOF**：两种都行——连读 "诶-哦-夫" 或逐字母 "A-O-F"。**国内团队普遍心底默认 A-O-F**。

---

## 2. 它到底"是什么"：KV、内存、持久化三连

### KV：藏宝图语义

Redis 里没有表、没有 SQL、没有 JOIN。所有数据都是 **key → value**：

```
train:trip:5:stock  →  3        （一个字符串，但它"是"余票）
cart:xiaoyu         →  [辣炒年糕, 可乐, 餐巾纸]   （一个 List，本质还是 value）
```

key 设计是有**约定俗成的命名法**的：`对象:ID:字段`（冒号分节），让 `KEYS train:trip:*` 一眼扫出某车次的全部键。这个命名法是**手写字典的目录**——不规范命名在十几个键时无所谓，到几百个键时就要人命了。

### 内存：为什么快

- 数据结构**就存在 RAM**，读一次约百纳秒级；磁盘一次约毫秒级。差 **1 万倍**。
- **单线程命令执行**：所有命令排队进一个线程——单条命令绝无并发交错，这就是 S11 里 DECR 防超卖的根本原因。这同时意味着**慢命令会卡死整条队列**（`KEYS *` 在百万键时就是禁手，R02 末尾有对照）。

### 持久化：断电不等于全丢（但也不是全保险）

| 方式 | 机制 | 优点 | 缺点 |
|---|---|---|---|
| **RDB** | 定时全量快照 → dump.rdb | 文件小、恢复快 | 两次快照之间的写全丢 |
| **AOF** | 每条写命令追加日志 | 最多丢 1 秒（everysec） | 文件大、重放慢 |

生产一般 **AOF + everysec** 为主、RDB 兜底快照。**本项目放行 `--save '' --appendonly no`**——起的是"纯内存实例"，重启即清空。下一节解释为什么这在本课程不是风险。

---

## 3. 本机的 Redis 从哪来：valkey 走查

先看"装的是什么、跑的是什么"（全部实测）：

```
$ redis-cli --version
valkey-cli 9.1.2 (git:7f1dffed-dirty)

$ redis-server --version
Valkey server v=9.1.2 sha=7f1dffed:1 malloc=jemalloc-5.3.1 bits=64 build=ab309e9a46916906

$ redis-cli INFO server | grep -E "redis_version|valkey_version|server_name"
redis_version:7.2.4
server_name:valkey
valkey_version:9.1.2
valkey_release_stage:ga
```

三个事实连起来看：

1. **`redis-cli` 命令在系统里其实"是 valkey 的客户端"**。valkey 是 Redis 核心团队出走后（2024，许可证风波）fork 的社区继任者——**协议、命令、客户端兼容**。所以装了 valkey 的 Linux 发行版上，`redis-cli`/`redis-server` 这两个名字照样存在，只是实现换了芯。
2. `INFO server` 里 `server_name:valkey` + `redis_version:7.2.4`：valkey 9.x 仍**对外宣称兼容 Redis 7.2 协议**，Spring Boot 的 `spring-boot-starter-data-redis`（底层 Lettuce 客户端）因此能直连它，**零改动**。这就是"换实现不换生态"的意义。
3. **为什么 `tools/` 里没有 Redis 目录？** 打开 `tools/` 只有 `kafka/`。因为 Redis（valkey）不需要"自带"，本机发行版直接给包了：`redis-server` 是系统命令，`infra/start-all.sh` 第一段直接用系统命令拉起（下一节走查）。对比 Kafka：它是 JVM 大件、版本敏感，所以**放 `tools/` 里随项目走**。两类中间件两种活法，这是运维常识，不是偷懒。

### 谁在管它：start-all.sh 的 Redis 片段

```
$ cat infra/start-all.sh | sed -n '10,20p'

echo "== 1) Redis =="

redis-cli -p $REDIS_PORT PING 2>/dev/null || (
  nohup redis-server --port $REDIS_PORT --daemonize no --save '' --appendonly no \
    --logfile "$ROOT/logs/redis.log" >/dev/null 2>&1 &
  ...
)
```

逐个参数说白了：

- `--save ''`：**关掉 RDB**（空字符串 = 不配快照点）。教学取向：项目状态（车次、订单号）本来就写进 H2 文件，Redis 只存"活的经济"，重启重灌不心疼。
- `--appendonly no`：AOF 也关。
- `--daemonize no`：不 fork，前台进程交给 `nohup &` 托管，PID 写进 `logs/redis.pid`。
- `redis-cli PING ↩`：起完轮询握手，通了才算就绪——**与 S 系列的"健康检查思想"一脉相承**。

**这个决定的边界要认清**：正因纯内存，本项目的**余票数在做完 `rm data/` 后重灌**（train 的 `SeedRunner` 会把 Redis 重新初始化）。生产情景需要 `appendonly yes`，以及快照文件与日志的**独立磁盘**。

---

## 4. 动手验证五件套（真实输出，一行不编造）

**环境前提**：`infra/start-all.sh` 跑过（Redis 6379、train 8084 在线）。逐条实测：

```
$ redis-cli -p 6379 PING
PONG
```

`PONG` 是 Redis 的问候回文——客户端连上很容易、但**通了才算数**，运维脚本都用它做心跳。

```
$ redis-cli SET tutorial:hello "你好Redis" "EX" 60
OK
$ redis-cli GET tutorial:hello
"你好Redis"
```

`SET` 写、`GET` 读，`EX 60` 表示"60 秒后自动消失"。注意 GET 的返回带**双引号**——那是 redis-cli 的显示格式（字符串类型按 JSON 字符串回显），不是说值里真的有引号。

```
$ redis-cli TTL tutorial:hello
60
```

`TTL` 返回剩余秒数。刚才设了 `EX 60`，隔几秒再问就变 57、52……到 0 后变成 `-2`（key 不存在了；`-1` 表示"存在但永不过期"）。R02 会用 `-1` 与 `-2` 的对照系。

```
$ redis-cli KEYS 'train:*' | sort | head
train:trip:1:seq
train:trip:1:stock
train:trip:2:seq
train:trip:2:stock
...
```

`KEYS 'pattern'` 模糊扫键。**伪讲清白祁**：`KEYS` 是 O(N) 全量扫描，键多时会把单线程 Redis 卡住几秒——**禁在生产环境当常备**。本项目 20 个键短平快，无妨；生产换 `SCAN`（增量分批）。

### 五件套速查表

| 命令 | 一句人话 | 这站实测结果 |
|---|---|---|
| `PING` | "喂？" | `PONG` |
| `SET k v [EX n]` | 写 + 可选倒计时 | OK |
| `GET k` | 读字符串 | `"你好Redis"` |
| `TTL k` | 剩几秒（-1 永生 / -2 已消散） | 60 |
| `KEYS ptn` | 模糊列键（生产改用 SCAN） | 20 个 train:* |

### 追加一条现场：TYPE 与 NULL 的两种脸

顺手补两条实战小命令，R02 会用到：

```
$ redis-cli TYPE tutorial:hello
string
$ redis-cli TYPE train:trip:1:stock
string
$ redis-cli GET tutorial:no-such-key
(nil)
```

读解：**TYPE 告诉你这个 key 用的是哪种抽屉**（五种结构各报各名）；**GET 不存在的 key 返回 `(nil)`**——不是空字符串、不是报错，是"没有"。Java 端读到 `null` 时你要能在这三种情形间分辨："从未写过 / 被 TTL 回收 / 被人 DEL 了"——R02 的 TTL 三态从今天起就要挂在心里。

---

## 5. 思考题（先想 3 分钟）

1. 为什么"单线程"既是 Redis 速度的**功臣**（原子、免锁）又自带**风险**（慢命令卡全队）？举一条你打算在本项目 jogging 里劝退大家的命令。
2. `--save '' --appendonly no` 的教学实例断电丢什么？哪些数据（列三个具体 key）丢了以后**必须靠代码重新初始化**？这个重灌动作现在在哪一个类里？（提示：train 的 `SeedRunner`。）
3. valkey 与 Redis 的关系像 "mysql 与 MariaDB"，你可以描述**为什么改协议还要"继续兼容 Redis 7.2"**？如果不兼容会发生什么连锁事故？（从客户端生态角度说起。）
4. RDB 与 AOF 一句话总结：**谁更快恢复？谁更不丢数据？** 顺带把"为什么两个都留"说清楚。

## 6. 练习题

1. 用 `redis-cli` 完成一整条链路：`SET blog:hit:java 0` → 连续自增 3 次（`INCR`）→ `GET` 读出来。**实测**贴出三条输出，并解释为什么 3 次 INCR 后是整数而不是 `"3"` 字符串。
2. 给 `blog:hit:java` 设 2 秒 TTL，写一行 bash 循环（`while redis-cli EXISTS blog:hit:java; do sleep 0.5; done`）观察它几轮后"消失"，并纵向记录 TTL 从正 → -2 的转折。
3. 故意输错一个命令（`redis-cli SETT foo bar`），观察报错文案——记下一个事实：**Redis 报错只给"第几个参数错了"，不给你猜**。这与你熟悉的 SQL/H2 哪种报错风格更难排错？

## 7. 参考答案

**练习 1**（实测）：

```
$ redis-cli SET blog:hit:java 0
OK
$ redis-cli INCR blog:hit:java
1
$ redis-cli INCR blog:hit:java
2
$ redis-cli INCR blog:hit:java
3
$ redis-cli GET blog:hit:java
"3"
```

GET 显示 `"3"` 只是 cli 的字符串回显格式；**INCR 的设计语义是"一个数字的自增"**——Redis 内部对字符串做进制转译保证原子读加写（这也是它出门自习就自带的"顺手优势"）。注意 INCR 对非数字字符串会报 `ERR value is not an integer`。

**练习 2**（实测记录形态）：

```
$ redis-cli SET otp:x "验证码" EX 2
OK
$ redis-cli EXISTS otp:x        # 半秒后
1
...（几轮后）
$ redis-cli EXISTS otp:x
0
$ redis-cli TTL otp:x
-2
```

转折点是 `EXISTS 1 → 0` + `TTL -2`：**不是"缓存值变 null"，是整个 key 被回收**。对读代码的人这是根本差别：`GET` 返回 nil 时你要分别对待"被 TTL 回收（-2）"与"从未存在"。

**练习 3**（实测）：

```
$ redis-cli SETT foo bar
(error) ERR unknown command 'SETT', with args beginning with: 'foo', 'bar',
```

比 SQL 好排错的一点：**是"命令不存在"，不是"行不匹配"**，一眼锁定拼写。但 Java 世界里我们尽量不让拼写错误抵达 Redis 层——Spring Data Redis 的 API 拼写错误在**编译期**就交给了你。R03 起就体会到这个差别。

---

## 8. 本节小结

- Redis = **内存 + KV + 单线程命令**。这三件事分别解释了：为什么快、为什么偏门好用、为什么 DECR 能防超卖。
- **持久化**只有 RDB（快照）与 AOF（记账）两种；本项目教学实例两个都关——代价是"重启需代码重灌"，收益是**学得更纯粹**。
- 本机跑的是 **valkey 9.1.2**，宣称兼容 Redis 7.2——`redis-cli` 五条命令一步不差，Spring Boot 连接零改动。
- **五件套**已经全真：`PING`（心跳）、`SET/GET`（读写）、`TTL`（倒计时）、`KEYS`（扫键，生产慎用）。

---

## 9. 下一站

五条命令只是"开门"。真实的业务数据总不止一种形状：购物清单要**排队**、用户档案要**分格**、标签要去**重**、热榜要**按分排序**。R02 · 键值操作实战——String/List/Hash/Set/ZSet 五种结构配五个生活场景，外加 TTL "自动消失"实测十条账单，全部现场跑给你看。
