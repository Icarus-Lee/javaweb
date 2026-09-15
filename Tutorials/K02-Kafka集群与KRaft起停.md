# K02 · Kafka 集群与 KRaft 起停（zookeeper 时代结束、kafka-storage.sh format、start-all.sh 片段真实拆解）

> **本节要点**：Kafka 4.x 起只有 KRaft 一条路——用 Kafka 自带的 Raft 元数据仲裁替换 zookeeper，"没 ZooKeeper 就起不来"的时代结束了。本章钉三件事：一，KRaft 是什么、为什么少了一套房；二，`kafka-storage.sh format` 到底干了什么（数据目录的"出生证"，且**只能盖一次**——实测让你看到撞卡报错）；三，`start-all.sh` 里的 Kafka 片段逐行拆解，加一个**起停一圈的日志行号实录**（logs/kafka.log 的真实数字）。
> **前置知识**：K01（topic/partition/offset 三件套）、零起点-03（端口、进程与一键启动）。
> **产出**：能讲清 KRaft 对 zookeeper 的替换逻辑；能说清 format 与 start 的分工；能现场起停一圈并给出日志证据行号。

> 🗺 **主线进度**：`… K01 消息队列思想 ─ ▶K02 集群与 KRaft◀ ─ K03 生产者实战 ─ …`
> 🎞 **上一站发生了什么**：K01 把三个真事件收进口袋，三件套立住。
> 📀 **本站你会得到**：
> - KRaft vs zookeeper 的"一家一栋楼"对照
> - format 撞卡实录（含本机真实 cluster-id）
> - 起停一圈的日志真据（2911 行止 → 重启后 5443 行、started 行 5389）

---

**本站名词卡**

| 名词 | 英文 | 一句人话 | 在本项目哪里见到 |
|---|---|---|---|
| KRaft | Kafka Raft | Kafka 自带的元数据仲裁（Raft 协议），顶替 zookeeper | `server.properties` 的 controller 配置 |
| zookeeper | zoe-keeper | 旧时代的元数据管家（3.x 必须带） | 本项目 4.3.1 里**不存在** |
| cluster-id | - | 数据目录出生证上的 UUID | `575f7511-7147-43d6-9e84-8b401b44289d` |
| format | - | 给空数据目录首次盖章（写 meta.properties） | `kafka-storage.sh format` |
| log.dirs | - | Kafka 数据目录（消息与元数据都在此） | `data/kafka/kraft`（env.sh 的 KAFKA_DATA） |
| standalone | 单节点 | controller 与 broker 同 JVM 一个进程 | `--standalone` |
| epoch | 任期 | Raft 的"这轮谁说了算"计数 | 日志里 `epoch=1` 的字样 |

---

## 1. 生活类比与动机：从"一家两栋楼"到"一家一栋楼"

**旧时代（Kafka 3.x 之前）**：Kafka 是"一栋干活的楼"，zookeeper 是旁边"记账的楼"。两栋楼之间有一根**常要通话的专线**——元数据查询（topic 列表）、选主（谁活谁死）、配置同步全走这根线。运维要**同时照顾两套房**:版本要配对、网络要稳、脑裂要防，故障排查时"到底哪栋楼坏了"都得先站队。

**KRaft 时代（3.3 起、4.x 唯一）**：Kafka 大楼**自己开一间 Raft 小办公室**（controller 角色）：少数轴心节点跑 Raft 协议**选主并记录元数据**（谁活着、有哪些 topic、epoch 多少）。zookeeper 整栋退租，**专线消失**。对本项目更直观：**一个 JVM 里两个角色**（controller + broker，`--standalone`），一条命令起家，日志只有一个目的地（logs/kafka.log）。

**format 是什么、干了什么（三句说全）**：

1. 对一个**空的数据目录**写一份"出生证"（`meta.properties`，含 cluster-id 与节点 id）——Kafka 启动时开箱即读，确认"这目录自家人"。
2. **只能盖一次**：重复 format 会因 cluster-id 对不上直接退出（本章实测）；已在用的目录绝不会被"格式化二遍"。
3. **不是格式化硬盘**：业务 topic 与消息由 broker 启动后按需写入；format 只负责"建目录与出生证"。

---

## 2. start-all.sh 的 Kafka 片段逐行拆解（真码）

```bash
echo "== 2) Kafka (KRaft 单节点) =="
if ! (echo > /dev/tcp/127.0.0.1/$KAFKA_PORT) 2>/dev/null; then
  # 把数据目录指到项目 data/（可整体删除即重置）
  sed -i "s#^log\.dirs=.*#log.dirs=$KAFKA_DATA#" "$KAFKA/config/server.properties"
  if [ ! -f "$KAFKA_DATA/cluster.meta.properties" ]; then
    rm -rf "$KAFKA_DATA"
    UUID=$(python3 -c 'import uuid;print(uuid.uuid4())')
    "$KAFKA/bin/kafka-storage.sh" format --standalone \
      --cluster-id "$UUID" \
      --config "$KAFKA/config/server.properties" >/dev/null 2>&1
  fi
  nohup "$KAFKA/bin/kafka-server-start.sh" "$KAFKA/config/server.properties" \
    > "$ROOT/logs/kafka.log" 2>&1 &
  echo $! > "$ROOT/logs/kafka.pid"
  for i in $(seq 1 40); do (echo > /dev/tcp/127.0.0.1/9092) 2>/dev/null && break; sleep 1; done
fi
```

六段读法：

1. **就绪探测**：`(echo > /dev/tcp/…/9092)` 是 bash 自带的 TCP 握手小魔术——9092 通了就认为"可能已在线"，跳过启动（幂等速查）。
2. **sed 拨数据目录**：把 `log.dirs` 固定到项目 `data/kafka/kraft`（env.sh 的 KAFKA_DATA）。**教学友好的决定**：想重置中间件，`rm -rf data/` 一锅端。
3. **没有出生证才 format**：以"出生证文件是否存在"判断——**有就跳过 format**，从机制上保证"format 顶多一次"。
4. **rm -rf + 新 UUID**：只在布局空目录时动手。**重置 = 删目录 + 换新章**，旧消息全部清零（K 系列的"旧事件吐泡"就来自这条）。
5. **kafka-server-start.sh 才是真正起进程**：默认 Xmx1G 的 JVM 大件，`nohup … > logs/kafka.log` 托管起来。
6. **PID 落册 + 端口轮询 40 秒**：与 Redis 段同一门派的"启动即验收"哲学。

**两句话总**：**format 盖出生证，start 起楼**。format 是瞬时的文件操作，start 是常驻的真进程。

---

## 3. 动手验证一：format 的"撞卡"实录

对**已被用掉的**数据目录再跑一次 format（故意换一个 cluster-id），实测：

```
$ env KAFKA_HEAP_OPTS="-Xmx64M" tools/kafka/bin/kafka-storage.sh format \
    --cluster-id 11111111-2222-3333-4444-555555555555 \
    --config tools/kafka/config/server.properties --standalone

Exception in thread "main" java.lang.RuntimeException: Invalid cluster.id in: /home/icaruslee/Projects/javaweb/data/kafka/kraft/meta.properties. Expected 11111111-2222-3333-4444-555555555555, but read 575f7511-7147-43d6-9e84-8b401b44289d
（exit code = 1）
```

三条读法：

1. **"Expected …但 read …"**：这个数据目录**已有主**，出生证上的号码是 `575f7511-7147-43d6-9e84-8b401b44289d`——与脚本传入的假 UUID 对不上，直接拒绝。**这是查看本机 cluster-id 最直观的一招**。
2. **exit 码 1**：format 失败走非零退出。脚本侧 `if [ ! -f 出生证 ]` 的判断，就是为了让 format 根本没机会撞到这一步。
3. **数据未受损**：撞卡即停——**format 层自带一道写保护**。"一键重置"走的是 `rm -rf data/`，而不是"反复 format"。

---

## 4. 动手验证二：起停一圈（真实日志行号）

完整走一遍：**stop → 9092 下线 → start → 日志行数暴涨**。

### 停：kafka-server-stop.sh

```
$ env KAFKA_HEAP_OPTS="-Xmx64M" tools/kafka/bin/kafka-server-stop.sh
$ (echo > /dev/tcp/127.0.0.1/9092) 2>/dev/null && echo "9092 仍在线" || echo "9092 已下线"
9092 已下线
$ ps -p $(cat logs/kafka.pid) >/dev/null && echo 进程还在 || echo Kafka 进程已退出
Kafka 进程已退出
$ wc -l logs/kafka.log
2911 logs/kafka.log
```

（stop 期间进程自身还写了一批关闭日志：clean shutdown 全套落在文件尾。）

### 起：kafka-server-start.sh

```
$ nohup env KAFKA_HEAP_OPTS="-Xmx256M" tools/kafka/bin/kafka-server-start.sh \
      tools/kafka/config/server.properties >> logs/kafka.log 2>&1 &
$ (echo > /dev/tcp/127.0.0.1/9092) 2>/dev/null && echo "kafka: up"
kafka: up
$ wc -l logs/kafka.log
5443 logs/kafka.log
$ grep -n "Kafka Server started" logs/kafka.log
2196:[2026-09-14 18:26:43,976] INFO [KafkaRaftServer nodeId=1] Kafka Server started ...
5389:[2026-09-14 18:28:50,853] INFO [KafkaRaftServer nodeId=1] Kafka Server started ...
```

三条关键读证：

1. **行号 2196 与 5389**：同一文件里出现两次 "Kafka Server started"——上轮（18:26）与这轮（18:28）各一次，**进程重生、日志追加**，一栏里程碑留双根。
2. **2911 → 5443**：起一次单节点 Kafka 约 2.5k 行起步日志（Raft 选举 + broker 各项就位）。**信息量在"选举与就位"，可别当成"日志异常多"。**
3. **"kafka: up" 只是 9092 通**；**"Kafka Server started" 才是官方里程碑**。端口通 ≠ 业务就绪——start-all 的保守值是"端口通即 up"，生产要等后者（或读 actuator/健康脚本）。

### 数据守恒确认（起停后 topic 还在）

```
$ env KAFKA_HEAP_OPTS="-Xmx64M" tools/kafka/bin/kafka-topics.sh \
      --bootstrap-server 127.0.0.1:9092 --list 2>/dev/null

__consumer_offsets
takeout-order-events
train-order-events
```

**重要事实**：只有 `rm -rf data/kafka/kraft` 会清掉业务 topic；**正常起停**数据全在。消费进度也一样守恒（K04 的 audit/dispatch 位移在重启后原样接上）。

---

## 4.5 工程实录：踩坑与修复——数据目录"整族重生"的三手证据

这一段不是设计好的剧本：教学写作当日，本机中间件发生了一次真实的环境重置——**Kafka 数据目录被一键重置（`rm -rf data/kafka/kraft` + format + 重启发生于 09:56）**。事故现场正好把本章三个论点从"纸面"变成"真账"，一层层读：

### 证据一：LOG-END 失忆（业务清零的直接表象）

```
$ tools/kafka/bin/kafka-consumer-groups.sh --bootstrap-server 127.0.0.1:9092 \
      --describe --group dispatch 2>/dev/null
GROUP    TOPIC                  PARTITION  CURRENT-OFFSET  LOG-END-OFFSET  LAG
dispatch takeout-order-events   0          2               2               0
```

重置前 dispatch 组早已追平 10 条（LAG 0）；重置后 LOG-END 只剩 **2**——**不是"少了 8 条消息"，而是 topic 整个新生成、从零起步**。`--from-beginning` 只能读到"新世代"的 2 条。

### 证据二：出生证实拍——新 cluster-id 的诞生

重置后再跑撞卡（假 UUID 11111111-…），读出的本地真号已经换人：

```
Exception in thread "main" java.lang.RuntimeException: Invalid cluster.id in:
/home/icaruslee/Projects/javaweb/data/kafka/kraft/meta.properties.
Expected 11111111-2222-3333-4444-555555555555,
but read 9b087bc8-5f23-47c9-8bf9-a0d579a05894
```

上一轮本章实测的旧号是 `575f7511-…`，现在是 `9b087bc8-…`——**"出生证"换了人就换本系列所有号**。定位手段同：给 format 一张假身份证，让它把地里埋的证件号交出来（比 `cat meta.properties` 更具"事故现场感"，脚本化探测也这么写）。

### 证据三：消费组集体"无状态重生"

`--list` 里真实的组只剩 `dispatch` 与 `audit`（业务在跑自动注册），旧的临时组（console-consumer-XXXX、k04-tempdemo）随风而散——**组的账挂在 broker 的 `__consumer_offsets` 主题上，主题本身也在被删的目录里**。这不是"组丢了消息"，是"整个世界换了一卷帐纸"。

### 定位思路与修复

1. **第一步对号入座**：`--describe` 六列、`--list` 三条不变——若它们同时缩小/改名，先怀疑"数据目录级事件"，而不是消费者代码。
2. **第二步验证出生证**：撞卡读号 → 与团队记录比对 → 判断"换了生命"。
3. **修复（其实是重建）**：按 start-all 惯例重灌——业务 topic 自动再生（auto-create 或生产者首发），消费组按业务重开账。**教学环境的重置成本被 start-all 压到一分钟，这不是巧合，是"db 可整体删除即重置"设计的红利**（start-all.sh 注释原话）。
4. **教训转化**：训练一个习惯——**排障先分清"进程级故障"（可以重启救）与"数据级故障"（目录都没了，别谈重启）**。`grep "Kafka Server started" logs/kafka.log` 与 `--describe` 是否掉线，是两者的分水岭。

### 一手 diff：事故前后的对照台词

| 维度 | 事故前（18:26 一轮实录） | 事故后（09:56 重置） |
|---|---|---|
| cluster-id | `575f7511-…` | `9b087bc8-5f23-47c9-8bf9-a0d579a05894` |
| takeout LOG-END | 10 | 2（又是新生成后的新账） |
| 组列表 | 6 个（含临时组） | 2 个业务组自愈注册 |

**一句话**：KRaft 起停本身无害（数据守恒，第 4 节验证），**删数据目录才是"一族一生"的界碑**——本章所有"守恒"结论，都要加上这个前提才完整。

---

## 5. 生产对照表（教学选择 vs 真实部署）


| 撞点 | 本项目 | 生产的常态 |
|---|---|---|
| 节点数 | 1（standalone） | 3 controller + N broker |
| 数据目录 | 项目内 `data/kafka/kraft` | 独立磁盘 + 快照策略 |
| 日志 | `> logs/kafka.log`（一个文件） | 切割（logrotate）+ 告警 |
| 就绪判定 | 端口通即认为 up | 等 "Kafka Server started" 或专用探针 |
| format | 出生证存在就跳过 | 集群版每 controller 一个节点 id，人控一次 |

---

## 6. 思考题（先想 3 分钟）

1. zookeeper 时代的两大典型故障（两楼专线中断、版本配对错乱）在 KRaft 时代还行得通吗？用"主是谁、账在哪"各答一句。
2. 若有人用**正确的** cluster-id 对**在线**集群再跑一次 format，会发生什么？（从撞卡实录的"Expected/read 对号"逻辑推：数据层会怎么答。）
3. "9092 通"与"Kafka Server started"之间有一个窗口。若 Spring Boot 消费者恰在此窗口启动，会发生什么？（从客户端重试与 `auto-offset-reset` 的行为想。）
4. start-all 等待 40 秒的是什么？若 40 秒内 Kafka 没起来，脚本会怎么走、你该怎么排障？（从脚本流程与 `logs/kafka.log` 的第一步读起。）

## 7. 练习题

1. 真跑一次撞卡（用假 UUID）拿到本机真实 cluster-id，并解释"读章对号"两词。
2. 亲手完成起停一圈：贴出 stop 后的进程/端口证据、start 后的 "Kafka Server started" 行号、以及 topics 列表不变的证据。
3. 一键重置体验：`infra/stop-all.sh` → `rm -rf data/kafka/kraft` → `start-all.sh`，然后 `--list` 看两条业务 topic 消失；用 console-consumer 验证 `--from-beginning` 也读不到任何事件。**这一跑是"数据目录 = 一族一生"的边界值体验。**
4. 打开 logs/kafka.log 找带 `epoch` 的行（Raft 自账）三行，说出"epoch 是谁在计时、为什么每次起停都可能加深"。

## 8. 参考答案

**练习 1**：本机真实 cluster-id = `575f7511-7147-43d6-9e84-8b401b44289d`（来自撞卡输出的 read 侧）。**读章对号**：cluster-id 是磁盘出生证上的号码，format/start 都要"读取并核对"。

**练习 2**（真跑实录样式）：

```
stop 后证明：
  9092 已下线（/dev/tcp 探测失败）
  Kafka 进程已退出
start 后证明：
  5389 行：[2026-09-14 18:28:50,853] INFO [KafkaRaftServer nodeId=1] Kafka Server started
  logs 行数 2911 → 5443
不变证明：
  --list 三行依旧（__consumer_offsets / takeout-order-events / train-order-events）
```

**练习 3**（重置实录形态）：删目录后 format 重新生成新 cluster-id；`--list` 只剩 `__consumer_offsets` 自动再生；`--from-beginning` 读不到任何业务事件——**data 目录 = 业务数据一族**，删目录等于清账。

**练习 4**：epoch 是 Raft 的任期号——**同一轮里被选出的谁（leader）说了算**。每次起停、选举都推进 epoch，日志里 `CandidateState → Leader (epoch=n)` 是"当轮主权"的盖章。单节点教学里也照样选主（自己是唯一 voter），这不是"浪费"，是 KRaft 协议的常态。

---

## 9. 本节小结

- **KRaft = Kafka 自带的元数据仲裁**（Raft 协议），4.x 起唯一路线——zookeeper 整页结束。
- **format 只干一次**：写出生证（cluster-id）进 data 目录；重复跑撞卡退出（实测 exit=1，读出的本机号 `575f7511-…`）。
- **start-all 片段六段**：探测 → sed 拨目录 → 条件 format → nohup 起进程 → PID 落册 → 端口轮询；**起 ≠ 就绪**（started 行才是里程碑）。
- **起停一圈实测**：2911 行止 → 重启后 5443 行；started 行号 2196 / 5389；topic 数据守恒——只有 `rm -rf data/` 才清账。

---

## 10. 下一站

集群侧就绪，下一章把笔交回生产者：K03 · 生产者实战 OrderEventProducer——`spring.kafka.producer` 的 yml 真值走查（key/value serializer 从哪来）、fire-and-record 的真实姿态、**发一条真事件并现场收到它**（事件贴纸一张）。

---

## 附录 A：server.properties 关键行走查（与 start-all 联动）

```properties
process.roles=broker,controller          # 单节点双角色（standalone 的 KRaft 形态）
node.id=1                                # 本节点编号
controller.quorum.bootstrap.servers=localhost:9093   # Raft 小队成员（教学里只有自己）
listeners=PLAINTEXT://:9092,CONTROLLER://:9093   # 两个门牌：业务口 9092、Raft 口 9093
log.dirs=/home/icaruslee/Projects/javaweb/data/kafka/kraft   # ← start-all 的 sed 就是改这一行
num.partitions=1                         # 新 topic 的默认巷数（教学口径）
```

五行读法：

1. **`process.roles`**：broker（干活）与 controller（记账）双双在自己 JVM 里——KRaft 的单节点形态。
2. **`controller.quorum.voters`**：Raft 投票小队（本项目只有 1 号自己）。真集群里这一行是"几号@几台:端口"的多人名单。
3. **双 listeners**：9092 给业务（producer/consumer 连这里）、9093 给 Raft 内部。**业务出问题看 9092、选举出问题看 9093** ——排障分流的第一步。
4. **`log.dirs`**：start-all 的 `sed` 每次都把该行拨向 `data/kafka/kraft`——**脚本与配置的联动点**。
5. **`num.partitions=1`**：新建 topic 的默认单巷。扩到多巷时，K06 的保序戒律回头翻一遍。

## 附录 B：起停排障三步法（现场真跑过）

1. **先看进程**：`ps -p $(cat logs/kafka.pid)`——PID 在不在，三秒定量。
2. **再看端口**：`(echo > /dev/tcp/127.0.0.1/9092) 2>/dev/null && echo up`——端口通只说明"有人听门铃"，不算业务就绪。
3. **最后看日志里程碑**：`grep -n "Kafka Server started" logs/kafka.log`——有这一行才算 broker 真就位；没有，就顺日志找 Raft 选举段（`QuorumState` 行）报错在哪。

三步的次序就是"进程 → 端口 → 里程碑"——与 S 系列健康检查同派。K06 的"running 与就绪"判据一脉相承。

## 附录 C：本章词汇的"读法与写法"备忘

| 词 | 读法 | 书写注意 |
|---|---|---|
| KRaft | "克拉夫特"（Raft 音） | 大写 K 小写 Raft 是官方拼写 |
| zookeeper | zoo-keeper | 一词不加连字符；指代 zookeeper 服务时可缩写 ZK |
| cluster-id | - | 是 UUID；写日志/工单要抄全 36 位 |
| log.dirs | - | 带点号的 properties 键名，别写成 log_dirs |
| standalone | - | --standalone 是单节点 format 的旗标 |

## 附录 D：三个常见误解一句话澄清

1. **"KRaft = Kafka 内置了 Raft 库"**：对，但说的是"元数据仲裁由 Kafka 自己的 controller 角色跑 Raft 协议"，不是"Kafka 事务的那个 transaction"。
2. **"format 会清空数据"**：不会。format 只对**空目录**盖章；对已占用目录只会撞卡退出（本章实测）。清空靠 `rm -rf`，那是人的决定。
3. **"端口通 = Kafka 就绪"**：9092 通只代表有人听；**"Kafka Server started" 那行日志才是官方里程碑**。

---

