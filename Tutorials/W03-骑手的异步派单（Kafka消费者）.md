# W03 · 骑手的异步派单：DispatchConsumer 全件走查、分区有序性与 auto-offset-reset

> **本节要点**：派单员 `DispatchConsumer` 一共 43 行，却浓缩了 Kafka 消费端最重要的三件事：**按 key 路由保证同一订单有序**、**消费条件三重校验防止"白派单"**、**`auto-offset-reset=latest` 决定重启后看不看历史**。本章全件走查 + 三组实测：offset 推进、手工投递消息验证消费条件、重启后 offset 不重放。
> **前置知识**：W02（12 步链路）、K 系列（topic/partition/offset 基础）、S12（定时与异步）。
> **产出**：能逐行讲清 onOrder() 的每个 guard；能解释"orderNo 当 key"在单分区下暂时无感、多分区下才见真章；能说清 latest 语义并演示。

> 🗺 **主线进度**：`… W02 链路总览 ─ ▶W03 异步派单◀ ─ W04 支付状态机 ─ …`
> 🎞 **上一站发生了什么**：W02 画出全链 12 步，把"派单"这一格留白。
> 📀 **本站你会得到**：
> - DispatchConsumer.java 全 43 行逐行注解
> - 手工往 topic 投消息的实测（CREATED 被忽略、PAID 因订单不存在被忽略、真实 PAID 触发派单）
> - consumer group offset 表（LAG=0）与 auto-offset-reset=latest 的重启实验

---

**本站名词卡**

| 名词 | 英文 | 一句人话 | 在本项目哪里见到 |
|---|---|---|---|
| 消费者 | consumer | 盯着 topic 拉消息的进程 | `DispatchConsumer` |
| 消费者组 | consumer group | 同组消费者分摊分区，offset 组内共享 | `groupId = "dispatch"` |
| 消息 key | record key | 决定消息进哪个分区的路由字段 | `kafka.send(TOPIC, orderNo, payload)` |
| 分区有序 | per-partition ordering | 同一分区严格按写入顺序消费 | 本站核心考点 |
| offset | - | 消费到的位置书签，组内提交 | CURRENT-OFFSET=8 |
| auto-offset-reset | - | 没有 offset 书签时从哪开始读 | `latest`（只读新消息） |
| 幂等消费 | idempotent consumer | 同一条消息处理两次，结果不变 | onOrder 的状态 guard |

---

## 1. 生活类比与动机：派单员不看喊了什么，只看小票

广播室（Kafka）里每个人都在喊单，派单员（DispatchConsumer）的做法很克制：

- **只认小票上的两个格子**：orderNo 和 phase；
- **只办一种事**：phase=PAID 且订单确实处于 PAID，才盖章派单；
- **别人喊"已下单"（CREATED）**：不关他的事，直接翻篇。

这套"先验条件再动手"的模式，就是消息消费端的**防御式编程**——广播室里什么声音都可能有，派单员只对"我能处理的事实"负责。

---

## 2. 全件走查（DispatchConsumer.java，43 行逐段）

### 2.1 监听声明（第 18-19 行）

```java
@KafkaListener(topics = OrderEventProducer.TOPIC, groupId = "dispatch")
@Transactional
public void onOrder(String payload) {
```

- `topics = "takeout-order-events"`：常量引用 producer 侧，**topic 名只写一处**（防止两边各敲一遍字符串敲出分歧）。
- `groupId = "dispatch"`：和 application.yml 里 `spring.kafka.consumer.group-id: dispatch` 一致（注解优先）。
- `@Transactional`：下面的查单+改单+save 是一次 H2 事务——要么派成，要么当没听见。

### 2.2 抠字段（第 21-23 行 + 36-42 行）

```java
String orderNo = extract(payload, "orderNo");
String phase = extract(payload, "phase");
```

`extract` 是个土办法 JSON 解析：找 `"orderNo":"` 再截到下一个引号。教学取舍：**不上 Jackson 反序列化是为了让你看清"消息就是字符串"**；缺陷也直说——值里若含引号就解析错，生产代码应换成 `ObjectMapper.readValue(payload, OrderEvent.class)`（思考题 3）。

### 2.3 三重 guard（第 25-26 行）——本类的心脏

```java
Order o = orders.findByOrderNo(orderNo).orElse(null);
if (o == null || !"PAID".equalsIgnoreCase(phase) || !"PAID".equals(o.status)) return;
```

三个条件各挡一种脏消息：

| guard | 挡住什么 | 实测场景 |
|---|---|---|
| `o == null` | 广播室里的幽灵单（消息先到、库还没写/库被清过） | 本站实验 2 的 `fake-1111` |
| `!"PAID".equalsIgnoreCase(phase)` | CREATED 等非支付事件 | 本站实验 2 的 `fake-0000` |
| `!"PAID".equals(o.status)` | **重复/乱序事件**：订单已被处理过（幂等保护） | 消费者重放同一条 PAID 消息时 |

**幂等的妙处**：如果 Kafka 因重平衡把同一条 PAID 消息投递两次，第一次把状态改成 DISPATCHED 并提交；第二次进来时 `o.status` 已是 DISPATCHED，guard 直接 return——**不会重复派单**。这就是"幂等消费"：at-least-once 投递 + 幂等处理 = 效果上的 exactly-once。

### 2.4 派单动作（第 27-33 行）

```java
if (o.status.equals("PAID")) {
    o.status = "DISPATCHED";
    o.riderId = 9000L;          // 模拟：本机演示"调度中心"一次性指派给骑手 9000
    o.dispatchedAt = Instant.now();
    orders.save(o);
    System.out.println("[dispatch] order=" + orderNo + " -> 派给骑手 9000");
}
```

`riderId = 9000L` 是**写死的**。教学取舍：真实调度中心要按骑手位置、负载挑人，那是一整个系统；这里用常量把"派单结果"具象化，让你专注**消息驱动的状态推进**。思考题 4 会引导你把它抽成接口（题目原文："orderNo 抽接口（riderId）"——即把"由 orderNo 决定 riderId"这件事抽成一个可替换的 RiderAssigner 接口，默认实现返回 9000L）。

---

## 3. 核心考点："同一订单进入同一分区"

### 3.1 Producer 侧只发了一个关键参数

`OrderEventProducer.java:16`：

```java
kafka.send(TOPIC, orderNo, payload);    // 第二个参数就是 key
```

Kafka 的分区路由规则：**key 为空 → 轮询/粘性分区；key 非空 → hash(key) % 分区数**。同一 orderNo 永远算出同一个分区号，于是：

- 该订单的所有事件（CREATED、PAID、未来的 REFUNDED…）**严格有序**地落在一个分区里；
- 单分区只有一个消费者在消费（组内一个分区只分给一个成员），**处理顺序 = 事件发生顺序**。

### 3.2 为什么这件事在本项目"暂时无感"

实测当前 topic 只有 1 个分区：

```
$ tools/kafka/bin/kafka-topics.sh --bootstrap-server 127.0.0.1:9092 --describe --topic takeout-order-events
Topic: takeout-order-events	PartitionCount: 1	ReplicationFactor: 1 ...
```

单分区下所有消息天然有序——你感觉不到 key 的存在。**但一旦扩到 3 个分区**：没有 key 的话，同一订单的 CREATED 和 PAID 可能被发到不同分区、被不同消费者以乱序消费——消费者可能先收到 PAID 再收到 CREATED，guard 挡得住 CREATED，但任何依赖"事件顺序"的逻辑都会踩坑。**key 是花 0 成本买到的分区级有序**，这是 Kafka 最重要的一条工程纪律。

### 3.3 有序了，但只有分区级

注意措辞：Kafka **只保证分区内有序**，不保证全局有序。订单 A 和订单 B 之间的处理顺序是无所谓的（互不依赖）——这正好匹配业务：派单逻辑只看单个订单自己的状态。

---

## 4. `auto-offset-reset=latest`：旧消息不重放

application.yml：

```yaml
spring:
  kafka:
    consumer:
      group-id: dispatch
      auto-offset-reset: latest
```

**语义拆解**：auto-offset-reset 只在一种情况下生效——**这个消费组在 broker 上没有任何已提交的 offset**（第一次上线，或 offset 过期被清）。此时：

- `earliest`：从每个分区最老的消息读起（重放全部历史）；
- `latest`：只从"我上线之后新写入的"消息读起（**旧消息不重放**）。

### 重启 ≠ 重置（容易混的点）

`latest` **不影响**"有 offset 的正常重启"：dispatch 组的 offset 已提交在 broker，takeaway 进程重启后会从上次的书签继续，**一条不丢、一条不重**。实验见 5.3。

### 教学含义：为什么选 latest

设想你在课堂上重启 takeaway（比如刚改完代码），如果配了 `earliest`，昨天的几百条历史 PAID 事件会全部重放——虽然幂等 guard 挡得住，但屏幕会被 `[dispatch] ...` 刷屏，还会拉长启动时间。`latest` 让"重启 = 只管新事实"，演示环境干净清爽。生产环境要反过来想：**丢了 offset 的组用 earliest 全量重放 + 幂等消费**才是更安全的默认（宁可重放，不可漏单）。

---

## 5. 动手验证（三组实测，2026-09-14 本机实录）

### 5.1 实测一：offset 推进与 LAG 归零

```
$ tools/kafka/bin/kafka-consumer-groups.sh --bootstrap-server 127.0.0.1:9092 --describe --group dispatch
GROUP     TOPIC                 PARTITION  CURRENT-OFFSET  LOG-END-OFFSET  LAG  CONSUMER-ID
dispatch  takeout-order-events  0          8               8               0    consumer-dispatch-1-88aac57e...
```

读法：生产端已写到 offset 8，消费端已提交 8，LAG=0 = 全部处理完。下单支付一单后重跑，两边一起 +1。

### 5.2 实测二：手工投递脏消息，验证三重 guard

用 console-producer 直接灌两条（服务在跑，dispatch 组在线）：

```
$ tools/kafka/bin/kafka-console-producer.sh --bootstrap-server 127.0.0.1:9092 \
    --topic takeout-order-events
{"orderNo":"fake-0000","dishId":1,"qty":1,"phase":"CREATED"}
{"orderNo":"fake-1111","dishId":1,"qty":1,"phase":"PAID"}
```

结果（offset 8→10，说明**两条都被消费了**，但）：

```
$ grep "派给骑手" logs/takeaway.log | tail -3
[dispatch] order=d9f45b2f-... → 派给骑手 9000
[dispatch] order=0f963a4e-... → 派给骑手 9000
[dispatch] order=399d86ae-... → 派给骑手 9000      ← 没有 fake-*
```

- `fake-0000`：phase=CREATED，被第二个 guard 挡住（防"白派单"）；
- `fake-1111`：phase=PAID 但库里查无此单，被第一个 guard 挡住。

**消息被消费 ≠ 被处理**——消费者永远有权利"读后丢弃"，这正是防御式消费的日常。

### 5.3 实测三：重启不重放（latest 与已提交 offset 的分工）

```
$ bash infra/stop-all.sh && bash infra/start-all.sh      # 全栈重启
...
$ tools/kafka/bin/kafka-consumer-groups.sh --bootstrap-server 127.0.0.1:9092 --describe --group dispatch
CURRENT-OFFSET = 10, LAG = 0       ← offset 还是 10，历史 8 条没重放
```

再对比实验：换个**全新的组名**消费同一 topic（等价于"没有 offset 的组"）：

```
$ tools/kafka/bin/kafka-console-consumer.sh --bootstrap-server 127.0.0.1:9092 \
    --topic takeout-order-events --group fresh-crowd --from-latest \
    --timeout-ms 4000
（4 秒内无输出——latest 语义：老消息与我无关）
$ tools/kafka/bin/kafka-console-consumer.sh ... --group fresh-crowd --from-earliest ...
{"orderNo":"...","phase":"CREATED"}      ← earliest 把 10 条历史全倒出来了
```

两个参数，两种人生。dispatch 选了 latest（yml 配置），课堂安静。

---

## 6. 附：消费端的"生命周期"观察（从日志读 Kafka）

`logs/takeaway.log` 里消费者的生老病死都有痕迹（grep -a 处理二进制混排）：

```
$ grep -a "ConsumerCoordinator" logs/takeaway.log | grep -a "join\|Assign" | tail -3
... [Consumer clientId=consumer-dispatch-1, groupId=dispatch] Request joining group ...
... [Consumer clientId=consumer-dispatch-1, groupId=dispatch] Successfully joined group ...
```

三段式生命周期，对应三个可观测事件：

| 阶段 | 日志关键词 | 系统里发生了什么 |
|---|---|---|
| 加入组 | `Request joining group` / `Successfully joined` | 向 GroupCoordinator 报到，参与分区分配 |
| 被分配分区 | `Assigned partitions` | 单分区下：dispatch-1 拿到 partition 0 独占权 |
| 退出组 | `pro-actively leaving the group` | 进程关闭主动退组（优雅停机），触发组内重平衡 |

**重平衡的代价**（为什么值得观察）：组内成员变化时，全组暂停消费重新分配分区——期间消息堆积（LAG 上涨），恢复后追平。实测方法：起两个 takeaway 实例（同 groupId），看第二个加入时第一个的日志出现 rebalance 字样。这就是 K05 的重平衡主题，此处先混个眼熟。

### 一个容易被追问的问题：消息会不会"消费丢失"？

把 W02 故障表第 11 行展开讲透。消息丢失只有三种可能：

1. **broker 丢**：本教学配置单副本（ReplicationFactor=1，5.1 实测输出可见），broker 磁盘坏 = 丢。生产配 3 副本 + `min.insync.replicas=2`。
2. **生产端丢**：`kafka.send()` 是异步的，不关心结果。若 send 后立刻进程崩溃且消息还在缓冲区，丢。生产用 `send().get()` 同步等确认或配 acks=all。
3. **消费端"丢"**：先提交 offset 后处理、或处理失败但 offset 已走——本项目的三重 guard + DB 状态幂等把"丢了"的后果降级为"延迟"（订单停在 PAID 可人工/对账修复），而不是"错账"。

**教学结论**：本项目在"丢"与"重"之间选了"宁重勿丢 + 幂等兜底"——所有环节都朝这个方向倾斜，这也是绝大多数业务的正确倾斜方向。

## 7. 思考题

1. 如果 producer 发消息时**不传 key**（`kafka.send(TOPIC, payload)`），现在的单分区系统会出 bug 吗？扩到 3 分区后呢？
2. guard 里 `equalsIgnoreCase(phase)` 但 `equals(o.status)`，为什么前者宽后者严？
3. 把土法 `extract` 换成 Jackson 反序列化需要哪些改动？异常该怎么处理（反序列化失败要不要 return）？
4. 动手题：把 `riderId = 9000L` 抽成接口——定义 `interface RiderAssigner { Long assign(String orderNo); }`，默认实现返回 9000L；DispatchConsumer 注入接口而非写死。以后想换"轮询分派"只需换实现类。
5. `@Transactional` 标在 onOrder 上，若 save 之后抛异常，H2 回滚；但 offset 已经提交了吗？（提示：Spring Kafka 的提交时机与事务边界——这是"至少一次"的根源。）

## 8. 练习（H/F/M 三层）

- **H（热身）**：重跑 5.2 实验，再补投一条 `{"orderNo":"<某真实 CANCELED 单的 orderNo>","phase":"PAID"}`，预测会不会派单再验证。
  验收：不派单——第三个 guard（status 不是 PAID）挡住。
- **F（进阶）**：实现思考题 4 的 `RiderAssigner` 接口 + 轮询实现 `RoundRobinAssigner`（AtomicLong 自增 % 9999 起的骑手号池）。
  验收：连续三单的 riderId 各不相同；smoke 仍绿（smoke 只断言 DISPATCHED，不管骑手号）。
- **M（硬核）**：把 topic 建成 3 分区（`kafka-topics.sh --alter --partitions 3`），并发 30 单，验证"同一 orderNo 的消息永远在同一分区"（`kafka-console-consumer` 按 partition 打印核对）。
  提示：消息 key=orderNo；同一单的 CREATED/PAID 两条消息 partition 字段相同。
  验收：每单的所有事件 partition 一致；30 单分布在 3 个分区（大致均衡）。

### 参考答案要点

- 思考 1：单分区不出 bug（全序）；3 分区下同一单的事件可能散到不同分区，乱序消费——guard 能挡 CREATED 晚到，但"PAID 先于 CREATED 被处理"会造成本可避免的空转，且任何未来新增的顺序依赖都会炸。
- 思考 2：phase 来自外部消息，大小写不可控，宽容匹配减少误杀；status 是自家数据库字段，值域受白名单控制，必须严格相等。
- 思考 3：定义 `record OrderEvent(String orderNo, String phase) {}`，`om.readValue(payload, OrderEvent.class)`；反序列化失败应 catch 后 log + return（**坏消息不能让消费卡死**——毒丸问题；更工程的做法是把坏消息进死信 topic）。
- 思考 5：默认配置下 listener 先提交 offset 再（或独立于）事务提交——save 回滚了但 offset 已走，这条消息**不会再来**：订单停在 PAID。这是 at-least-once 的裂缝，工业解法是事务同步器/手动 ack，教学上记住"消费端也要做对账兜底"（W06 伏笔）。

### 附加对照：把 guard 表扩展成"消息处理结果四象限"

| 消息情形 | guard 结果 | offset | 系统状态 |
|---|---|---|---|
| 合法 PAID 事件 | 处理（派单） | 提交 | DISPATCHED |
| 脏消息（不存在/相位错/状态错） | 丢弃 | 提交 | 不变（防御成功） |
| 处理中抛异常（如 H2 宕） | 失败 | **视提交时机** | 重试或跳过（思考 5） |
| 进程崩溃前未提交 | 未提交 | 重启后重投 | 幂等 guard 兜住重复 |

---

## 本节小结
- DispatchConsumer 的三重 guard（订单存在 / phase=PAID / status=PAID）分别挡幽灵单、无关事件、重复事件——幂等消费的活教材。
- `send(TOPIC, orderNo, payload)` 的 key 是"同一订单进同一分区"的全部秘密：分区级有序、0 成本。
- `auto-offset-reset=latest` 只管"没有 offset 书签"的场景：首次上线不重放历史；正常重启由已提交 offset 保证既不丢也不重。
- 消息被消费 ≠ 被处理：消费者对脏消息有"读后丢弃"权。

## 10. 下一站

W04 走进 `OrderService.transition()`：那行 `LEGAL_NEXT.contains(...)` 如何把"乱序事件"彻底拒之门外——并当场触发一个 500 给你看。

## 练习题

- 练 1：把本章动手实验的参数改一档，预测输出再实测，把差异写下来。
- 练 2：设计一个"改坏条件"的反向实验，验证错误表现与预期一致。

