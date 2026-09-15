# W03 · 骑手的异步派单：DispatchConsumer 全件走查、分区有序性与空消费组的 offset 运维

> **本节要点**：派单员 `DispatchConsumer` 一共 43 行，浓缩了 Kafka 消费端最重要的三件事：**按 key 路由保证同一订单有序**、**三重校验防"白派单"**、**`auto-offset-reset=latest` 决定重启后看不看历史**。本章全件走查 + 本站工程实录：dispatch/audit 两个消费组从"活跃"到"空组"，真机跑一遍 `kafka-consumer-groups.sh --reset-offsets --dry-run / --execute`，把运维课本里的命令变成有输出的 muscle memory。
> **前置知识**：W02（12 步链路）、K 系列（topic/partition/offset 基础）。
> **产出**：能逐行讲清 onOrder() 的每个 guard；能解释"orderNo 当 key"；能独立完成"活跃组拒绝重置 → 空组放行重置"的完整运维闭环。

> 🗺 **主线进度**：`… W02 链路总览 ─ ▶W03 异步派单◀ ─ W04 支付状态机 ─ …`
> 🎞 **上一站发生了什么**：W02 画出全链 12 步，并真机演示了 Kafka 休克时订单卡 PAID、日志里 [dispatch] 行缺席。
> 📀 **本站你会得到**：
> - DispatchConsumer.java 全 43 行逐行注解
> - 两组 `reset-offsets` 真机实录：Stable 组被拒、Empty 组 dry-run/execute 全输出
> - dispatch 组与 train 的 audit 组的对称玩法

---

**本站名词卡**

| 名词 | 英文 | 一句人话 | 在本项目哪里见到 |
|---|---|---|---|
| 消费者 | consumer | 盯着 topic 拉消息的进程 | `DispatchConsumer` |
| 消费者组 | consumer group | 同组消费者分摊分区，offset 组内共享 | `groupId = "dispatch"` |
| 消息 key | record key | 决定消息进哪个分区的路由字段 | `kafka.send(TOPIC, orderNo, payload)` |
| 分区有序 | per-partition ordering | 同一分区严格按写入顺序消费 | 本站核心考点 |
| offset | - | 消费到的位置书签，组内提交 | CURRENT-OFFSET=26 |
| auto-offset-reset | - | 没有 offset 书签时从哪开始读 | `latest`（只读新消息） |
| 幂等消费 | idempotent consumer | 同一条消息处理两次，结果不变 | onOrder 的状态 guard |
| 空组 | empty group | 有 offset 书签但没有任何活人 | dispatch（takeaway 停机时） |

---

## 1. 一句人话开场

广播室（Kafka）里谁都在喊，派单员只认**小票上的两个格子**（orderNo、phase）且**只办一种事**：phase=PAID 且订单确实处于 PAID，才盖章派单。这套"先验条件再动手"就是消费端的防御式编程。

## 2. 全件走查（DispatchConsumer.java，43 行逐段）

### 2.1 监听声明（第 18-19 行）

```java
@KafkaListener(topics = OrderEventProducer.TOPIC, groupId = "dispatch")
@Transactional
public void onOrder(String payload) {
```

- `topics` 用常量引用 producer 侧——**topic 名只写一处**，防止两边各敲一遍字符串敲出分歧。
- `groupId = "dispatch"`：与 application.yml 的 group-id 一致（注解优先）。
- `@Transactional`：查单+改单+save 一次 H2 事务——要么派成，要么当没听见。

### 2.2 抠字段（第 22-23 行 + 36-42 行）

```java
String orderNo = extract(payload, "orderNo");
String phase = extract(payload, "phase");
```

`extract` 是土法 JSON 解析：找 `"orderNo":"` 再截到下一个引号。教学取舍直说：**不上 Jackson 是为了让你看清"消息就是字符串"**；缺陷是值里含引号就解析错，生产应换 `ObjectMapper.readValue(payload, OrderEvent.class)`（思考题 3）。

### 2.3 三重 guard（第 25-26 行）——本类的心脏

```java
Order o = orders.findByOrderNo(orderNo).orElse(null);
if (o == null || !"PAID".equalsIgnoreCase(phase) || !"PAID".equals(o.status)) return;
```

| guard | 挡住什么 | 实测场景 |
|---|---|---|
| `o == null` | 幽灵单（消息先到/库被清过） | 手工投 `fake-1111`（实验见动手验证） |
| `!"PAID".equalsIgnoreCase(phase)` | CREATED 等无关事件 | 手工投 `fake-0000` |
| `!"PAID".equals(o.status)` | **重复/乱序事件**：已处理过（幂等） | 同一条 PAID 重投时 |

**幂等的妙处**：重平衡把同一 PAID 投递两次，第一次改成 DISPATCHED 并提交；第二次进来 `o.status` 已是 DISPATCHED，guard 直接 return——at-least-once 投递 + 幂等处理 = 效果上的 exactly-once。

### 2.4 派单动作（第 27-33 行）

```java
if (o.status.equals("PAID")) {
    o.status = "DISPATCHED";
    o.riderId = 9000L;          // 模拟"调度中心"一次性指派给骑手 9000
    o.dispatchedAt = Instant.now();
    orders.save(o);
    System.out.println("[dispatch] order=" + orderNo + " -> 派给骑手 9000");
}
```

`riderId = 9000L` 写死。真实调度要按位置/负载挑人，那是一整套系统；这里用常量把"派单结果"具象化，让你专注**消息驱动的状态推进**。W02 实录里的补投重放实验已当场演示：把这条简单规则跑在"迟到的事实"上照样正确。

## 3. 核心考点：key 与分区有序

`OrderEventProducer.java:16`：`kafka.send(TOPIC, orderNo, payload)`——第二个参数就是 key。路由规则：key 非空 → `hash(key) % 分区数`，同一 orderNo 永远同一分区；单分区内严格有序。

实测当前 topic 单分区：

```text
$ tools/kafka/bin/kafka-topics.sh --bootstrap-server 127.0.0.1:9092 --describe --topic takeout-order-events
Topic: takeout-order-events	PartitionCount: 1	ReplicationFactor: 1 ...
```

单分区下你感觉不到 key 的存在。**扩到 3 分区后**：没有 key，同一订单的 CREATED 与 PAID 可能散进不同分区被乱序消费——guard 挡得住 CREATED，但任何顺序依赖都会踩坑。**key 是 0 成本买分区级有序**，Kafka 最重要的工程纪律。

注意措辞：Kafka 只保证**分区内**有序。订单 A 与 B 之间顺序无所谓（互不依赖）——恰好匹配业务：派单逻辑只看单子自己的状态。

## 4. `auto-offset-reset=latest`：旧消息不重放

```yaml
spring:
  kafka:
    consumer:
      group-id: dispatch
      auto-offset-reset: latest
```

auto-offset-reset 只在一种情况下生效：**该组在 broker 上没有已提交 offset**（首次上线或 offset 被清）。latest = 只读"我上线之后"的新消息；earliest = 全部历史重放。**重启 ≠ 重置**：offset 已提交的正常重启，从书签继续，一条不丢、一条不重。

W02 实录恰好是一场学习的现场直播：start-all.sh 的格式化判断 bug（真机修完的 diff 见 W02 §4）把数据目录清零后，dispatch 组彻底没有 offset，`latest` 生效 → 历史不再重放 → 补投一条事实消息即恢复派单。**语义与事故在同一分钟里对上号。**

## 5. 工程实录：空消费组的 offset 运维（reset-offsets 真机实录）

offset 对不上账（重复消费、错位）时，运维动作是给**空组**重置 offset。什么时候组是空的？**takeaway 停机时**（成员退出，offset 书签还在）。这次我们把 dispatch 和 train 的 audit 两个组各演示一遍。

### 4.1 实测一：活跃组拒绝重置（防御现场）

takeaway 在跑，dispatch 组 Stable：

```text
$ tools/kafka/bin/kafka-consumer-groups.sh --bootstrap-server 127.0.0.1:9092 \
    --group dispatch --topic takeout-order-events --reset-offsets --to-latest --dry-run
Error: Assignments can only be reset if the group 'dispatch' is inactive,
       but the current state is Stable.
```

**这就是 broker 的护栏**：书签被消费者拿在手里时改它，等于两个人同时改同一页书的页码。想重置？先把人叫走。

### 4.2 实测二：制造空组——"精准导航"先给它一个正确的进程定位

**坑（真机踩的）**：`kill $(cat logs/takeaway.pid)` 杀的是 nohup 脚本 pid，8085 的 java 活得好好的，组仍是 Stable。改用端口反查（这也是零起点-03 的知识点回收）：

```text
$ TP=$(ss -ltnp | grep 8085 | grep -o 'pid=[0-9]*' | cut -d= -f2)   # → 130694
$ kill $TP; sleep 7
$ ... --describe --group dispatch
CURRENT-OFFSET  LOG-END-OFFSET  LAG   CONSUMER-ID
1               1               0     -            ← 这就是空组：有书签，没活人
```

### 4.3 实测三：空组上 dry-run → execute（真实输出）

```text
$ ... --group dispatch --topic takeout-order-events --reset-offsets --to-latest --dry-run

GROUP   TOPIC                PARTITION  NEW-OFFSET
dispatch takeout-order-events 0          1          ← 报告"我会把书签放到 1"

$ ... --reset-offsets --to-latest --execute
（同样一行：NEW-OFFSET 1 —— 落账）

$ ... --describe --group dispatch
CURRENT-OFFSET  LOG-END-OFFSET  LAG   CONSUMER-ID
1               1               0     -          ← 书签=1，组还是空的
```

### 4.4 实测四：audit 组同款手术

train 的审计消费者组叫 `audit`（消费 train-order-events），组活着时同样被拒；把 train 停 7 秒变空组后：

```text
$ ... --group audit --topic train-order-events --reset-offsets --to-latest --execute

GROUP  TOPIC              PARTITION  NEW-OFFSET
audit  train-order-events 0          0
$ bash infra/start-all.sh ── train 起来后：
audit  train-order-events  0          0     0     consumer-audit-1-519ebf22-...
```

**两组的参数只有一处分歧**（topic 名），命令模式完全一样——记住模板，换 topic 就是换手术台。

### 4.5 复盘：这套命令什么时候用、风险在哪

- **用**：offset 与账本错位（重复处理过 / 想跳过一阵子脏消息 / 全新主题给旧组）。
- **风险**：`--to-earliest` 在数据还在时会全量重放（幂等 guard 兜得住，但日志刷屏+DB 重写压力）；`--delete-offsets` 则是"书签整本撕掉"，下次上线走 auto-offset-reset（latest 还是 earliest，语义全在这一行配置）。
- **纪律**：永远先 `--dry-run` 印出新位置，人眼过一遍，再 `--execute`。

## 5.5 附：消费端的"生命周期"与"丢失三可能"（从日志读 Kafka 的补充观测）

### 5.5.1 消费者生老病死在日志里的痕迹

`logs/takeaway.log` 里每个阶段都有关键词（grep -a 处理二进制混排）：

```text
$ grep -a "ConsumerCoordinator" logs/takeaway.log | grep -a "join\|Assign" | tail -3
[Consumer clientId=consumer-dispatch-1, groupId=dispatch] Request joining group ...
[Consumer clientId=consumer-dispatch-1, groupId=dispatch] Successfully joined group ...
```

| 阶段 | 日志关键词 | 系统里发生了什么 |
|---|---|---|
| 加入组 | `Request joining` / `Successfully joined` | 向 GroupCoordinator 报到，参与分配 |
| 被分配分区 | `Assigned partitions` | 单分区下 dispatch-1 独占 partition 0 |
| 退出组 | `pro-actively leaving the group` | 优雅停机主动退组，触发重平衡 |

**重平衡的代价**：组内成员变化时全组暂停消费重新分配——期间消息堆积（LAG 上涨），恢复后追平。实测方法：起第二个 takeaway 实例（同 groupId），看第一个实例日志的 rebalance 字样（W02 实录停机时那条 `Node 1 disconnected` 之后消费者重连的完整经历，同族现象）。K05 有专论。

### 5.5.2 消息会不会丢？三种可能逐个对号（W02 故障表第 11 行的展开）

1. **broker 丢**：教学配置单副本（ReplicationFactor=1，3.1 节 --describe 可见）——broker 磁盘坏即丢；生产配 3 副本 + `min.insync.replicas=2`。
2. **生产端丢**：`kafka.send()` 异步不关心结果；send 后进程立刻崩且消息还在缓冲区即丢；生产用 `send().get()` 同步等确认或 acks=all。W02 实录里 Kafka 停机期间发出的 PAID 事件正在这条路径上消失了。
3. **消费端"丢"**：先提交 offset 后处理、或处理失败但 offset 已走——三重 guard + DB 状态幂等把"丢了"降级为"停在 PAID"（可对账/人工），而不是"错账"。

**教学结论**：本项目在"丢"与"重"之间选了"宁重勿丢 + 幂等兜底"，所有环节都朝这个方向倾斜。

### 5.5.3 消息处理结果四象限（把 guard 表扩展）

| 消息情形 | guard 结果 | offset | 系统状态 |
|---|---|---|---|
| 合法 PAID 事件 | 处理（派单） | 提交 | DISPATCHED |
| 脏消息（不存在/相位错/状态错） | 丢弃 | 提交 | 不变（防御成功） |
| 处理中抛异常（H2 宕） | 失败 | **视提交时机** | 重试或跳过（思考 5） |
| 进程崩溃前未提交 | 未提交 | 重启重投 | 幂等 guard 兜住重复 |

## 6. 动手验证（三组实测对账）

```text
① $ ... --describe --group dispatch   → LAG=0（下单支付一单后，两边一起 +1）
② $ kafka-console-producer.sh ... 灌两条脏消息：
   {"orderNo":"fake-0000",...,"phase":"CREATED"}   → offset++ 但不派单（guard 2）
   {"orderNo":"fake-1111",...,"phase":"PAID"}      → offset++ 但不派单（guard 1）
   $ grep "派给骑手" logs/takeaway.log 里没有 fake-*   → "被消费 ≠ 被处理"
③ $ 全栈重启 → offset 不重放；换新组名 --from-earliest → 历史全倒出来；--from-latest → 4 秒静默
```

## 7. 思考题

1. producer 不传 key（`kafka.send(TOPIC, payload)`），现在的单分区出 bug 吗？3 分区后呢？
2. guard 里 `equalsIgnoreCase(phase)` 但 `equals(o.status)`，为何前者宽后者严？
3. 土法 `extract` 换 Jackson 反序列化的改动清单？反序列化失败要不要 return？
4. 把 `riderId = 9000L` 抽成 `interface RiderAssigner { Long assign(String orderNo); }` 怎么落？
5. `@Transactional` 标在 onOrder 上，save 之后抛异常则 H2 回滚——但 offset 提交了吗？（Spring Kafka 提交时机与事务边界的关系，at-least-once 的裂缝所在。）

### 参考答案要点

- 1：单分区全序无害；3 分区乱序 → 空转与未来顺序依赖的隐患。
- 2：phase 来自外部消息，大小写不可控，宽容匹配减少误杀；status 是自家字段，值域受白名单控制，必须严格相等。
- 3：`record OrderEvent(String orderNo, String phase)`；反序列化失败 catch + log + return（**坏消息不能卡死消费者**——毒丸问题；工程化是死信 topic）。
- 4：接口 + 默认实现返回 9000L；DispatchConsumer 注入接口；换轮询分派只换实现类。
- 5：默认提交时机独立于 listener 事务——回滚了但 offset 已走，消息不会再来：订单停 PAID，只能对账/人工兜底（W06 主题）。

## 8. 练习（H/F/M 三层）

- **H（热身）**：重跑 4.2-4.3 全流程，并追加一条"真实 CANCELED 单的 orderNo + phase=PAID"脏消息，预测能否派单再实测验证。
  验收：不派单（第三个 guard：status 不是 PAID 挡住）。
- **F（进阶）**：实现 `RiderAssigner` + 轮询实现 `RoundRobinAssigner`（AtomicLong % 骑手号池）。
  验收：连续三单 riderId 不同；smoke 仍绿（smoke 只断言 DISPATCHED）。
- **M（硬核）**：topic 改 3 分区（`kafka-topics.sh --alter --partitions 3`），并发 30 单，验证同 orderNo 事件永远同分区。
  验收：每单所有事件 partition 一致；30 单在 3 分区大致均衡。

## 9. 本节小结

- 三重 guard 分别挡幽灵单、无关事件、重复事件——幂等消费活教材。
- key=orderNo 用 0 成本买分区级有序；单分区暂时无感、多分区见真章。
- latest 只管"没有 offset 书签"的场景；正常重启靠已提交 offset 既不丢也不重——W02 的事故把两条规则在一个晚上都演了。
- 空组是 offset 运维的窗口：Stable 拒改、Empty 可改；模板是 describe → dry-run → execute 描述。

## 10. 下一站

W04 走进 `OrderService.transition()`：`LEGAL_NEXT.contains(...)` 如何把乱序流转彻底拒之门外——并用一个教学实验端点把 6 种 to 的拒绝/放行一次量给你看。

## 动手验证

本章所有代码/命令都能整段复制运行：先跑 `bash infra/start-all.sh` 把全栈点着，再回到本章相应小节逐条复制命令。把你的实测输出与"预期输出"逐行对账——一致即通过。

## 练习题

- 练 1：把本章动手验证的参数改一档，先预测输出再实测，记录差异原因。
- 练 2：设计一个"改坏条件"的反向实验，验证错误表现与预期一致。

## 参考答案

内嵌于对应思考题答案（本章"参考答案"节已覆盖）；若未找到，按工程实录的验证路径自行复跑对账即可确认。
