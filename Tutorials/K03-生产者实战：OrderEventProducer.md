# K03 · 生产者实战：OrderEventProducer（KafkaTemplate 配置走查、fire-and-record、发真事件收真事件）

> **本节要点**：Java 怎么发 Kafka 事件？三个问题一次答完：一，**配置从哪来**——application.yml 里 `spring.kafka.producer` 的 key/value serializer（train/takeaway 各一份真值对照）；二，**怎么发**——`KafkaTemplate.send(topic, key, payload)` 三段式（OrderEventProducer 真码逐行）；三，**发了谁收到**——fire-and-record 的教学姿态，并现场把一条真事件发出去、用 console consumer 收到（真实事件记录贴出）。
> **前置知识**：K02（broker 已就绪、KRaft 起停）、K01（topic 三件套）。
> **产出**：能三分钟给任意 Spring Boot 项目挂上生产者；能讲透 `send(topic, key, payload)` 的实参设计（key 为什么是 orderNo）；能出具"发送 → 收到"的完整证据链。

> 🗺 **主线进度**：`… K02 集群与 KRaft ─ ▶K03 生产者实战◀ ─ K04 消费者与消费组 ─ …`
> 🎞 **上一站发生了什么**：K02 起停一圈跑通，"Kafka Server started" 双里程碑落档。
> 📀 **本站你会得到**：
> - producer yml 真值走查（train 与 takeaway 对照）
> - `OrderEventProducer` 全真码走查（含 payload 手拼 JSON 的责任说明）
> - "发一收一"现场对账（console consumer 实录）

---

**本站名词卡**

| 名词 | 英文 | 一句人话 | 在本项目哪里见到 |
|---|---|---|---|
| bootstrap-servers | - | broker 门牌号（连哪里） | `127.0.0.1:9092` |
| KafkaTemplate | - | 生产者的把手（send 三段式） | `OrderEventProducer` |
| serializer | 序列化器 | Java 字符串 → Kafka 字节的翻译官 | `StringSerializer` |
| key | - | 结构性钥匙（兼顾分区路由） | `orderNo`（保序的根，K06 回收） |
| payload | 载荷 | 事件本体的字符串 | `{"orderNo":...,"phase":"CREATED"}` |
| fire-and-record | 发完即记 | 教学姿态：send 即回、不等 ack，但事件本身在日志/队列有据 | `kafka.send(TOPIC, orderNo, payload)` |
| CompletableFuture | - | send() 的返回类型（将来时可追） | K05 讲 ：get() 阻塞等 ack |

---

## 1. 生活类比与动机：写一张单子的三步姿势

K01 立了点外卖的比喻；本章把笔握进手里。服务员接单的**真实姿势**可拆成三步：

1. **拿到把手**：柜台的后厨订单打印机（KafkaTemplate）早就插好线了（Spring 自动装配）。
2. **写一张单**：单子上的内容就是事件本体（payload），**字迹清晰、字段固定**（production 与 consumer 的合同）。
3. **投出去**：把单子放进接单台就转身（send 即回）——**不站在台前等厨师点头**。至于"单子到底印没印清楚"，靠的是**事后可查**（consumer 侧收货），不是"写单时憋着不走"。

**教学姿态叫 fire-and-record：发完即走但留有据**。生产者不阻塞用户（这是客服价的底线），而"有没有真发出去"由消费侧的对账表来验收（本章第 4 节、K04 第 4 节）。

---

## 2. 配置从哪来：producer yml 走查

### train 的 application.yml（真值节选）

```yaml
spring:
  kafka:
    bootstrap-servers: 127.0.0.1:9092
    consumer:
      group-id: audit
      auto-offset-reset: latest
      key-deserializer: org.apache.kafka.common.serialization.StringDeserializer
      value-deserializer: org.apache.kafka.common.serialization.StringDeserializer
    producer:
      key-deserializer: org.apache.kafka.common.serialization.StringSerializer
      value-deserializer: org.apache.kafka.common.serialization.StringSerializer
```

四段读法：

1. **bootstrap-servers**：门牌号，指向 K02 起来的那个 broker。
2. **producer 的 key/value serializer 双双 StringSerializer**：本项目 key 是 orderNo 字符串、value 是 JSON 字符串——**教学选 "裸字符串"**，好让 console consumer 一眼读懂、排查时事件本体可直贴。
3. **consumer 的段先别深究**：那是 K04 的主场；此处预告 train 自己的消费者组叫 `audit`。
4. **yml 里没有 key**：消息的路由 key 不是配置，而是代码里 `send()` 的实参（下一节）。

### takeaway 的对照（同一套结构、组名不同）

```yaml
spring:
  kafka:
    bootstrap-servers: 127.0.0.1:9092
    consumer:
      group-id: dispatch      ← 与 train 的差别之一
    producer:
      key-deserializer: ...StringSerializer
      value-deserializer: ...StringSerializer
```

**两明一暗**：**producer 配置两侧全同**（都是 String 序列化）；consumer 组名各归各业务（audit / dispatch）；**topic 名各用各的**（`train-order-events` / `takeout-order-events`，代码常量定义）。这就是"一套 yml 机关、多套业务并行"的标准形态。

**依赖从哪来**（不必另加）：`spring-boot-starter-kafka` 或 web 项目直接用 `spring-kafka` 依赖——starter 自动构造 `KafkaTemplate` Bean，**构造器注入即可拿用**（与 R03 的 StringRedisTemplate 同一 IoC 门派）。

---

## 3. OrderEventProducer 真码走查（train 全文）

**文件：`backend/train/src/main/java/com/javaweb/train/messaging/OrderEventProducer.java`**

```java
// 生产者：把"订单已发生"的事实写给 Kafka（fire-and-record，教学版不追求Exactly-Once）。
@Component
public class OrderEventProducer {
    public static final String TOPIC = "train-order-events";
    private final org.springframework.kafka.core.KafkaTemplate<String, String> kafka;

    public OrderEventProducer(org.springframework.kafka.core.KafkaTemplate<String, String> kafka) {
        this.kafka = kafka;
    }

    public void orderCreated(String orderNo, Long tripId, int seatNo, int price, String phase) {
        String payload = "{\"orderNo\":\"" + orderNo + "\",\"tripId\":" + tripId +
                ",\"seatNo\":" + seatNo + ",\"price\":" + price + ",\"phase\":\"" + phase + "\"}";
        kafka.send(TOPIC, orderNo, payload);   // key=orderNo：同一订单进同一分区（顺序保证）
    }
}
```

六步读法：

1. **`@Component`**：一个 bean 上岗（IoC 门派，K01 的服务员入列）。
2. **`TOPIC` 常量**：train 独有频道名；**topic 名是业务名**、全局唯一，`AuditConsumer` 也用这一个常量——**生产与消费永远引同一个名字**，不会拼错。
3. **注入 `KafkaTemplate<String, String>`**：泛型的两个 String 与 yml 的两个 StringSerializer 对上——**配置与代码严丝合缝**。
4. **payload 手工 JSON 拼接**：教学版用字符串模板直接拼；**字段名与消费侧解析约定一致**。责任边界要明示：**手拼 JSON 不做转义**，字段内容必须"元字符安全"（orderNo 是 UUID、phase 是受控常量——本项目恰好都安全）；真实项目换 JSON 库。
5. **`send(TOPIC, orderNo, payload)` 三段式**：topic 必填、key 可选、payload 必填。**key=orderNo** 是本系列保序的种子——同一单所有事件（CREATED/PAID/CANCELLED）落到同一 partition（K06 全线兑现）。
6. **send 即回（不抛不等）**：返回 CompletableFuture（"将来时可追"）。**生产者不阻塞业务线程**——审计/派单的一切都发生在消费侧（解耦的落地）。

### takeaway 的对照（同款小一行）

```java
@Component
public class OrderEventProducer {
    public static final String TOPIC = "takeout-order-events";
    private final KafkaTemplate<String, String> kafka;

    public void orderCreated(String orderNo, Long dishId, int qty, String phase) {
        String payload = "{\"orderNo\":\"" + orderNo + "\",\"dishId\":" + dishId +
                ",\"qty\":" + qty + ",\"phase\":\"" + phase + "\"}";
        kafka.send(TOPIC, orderNo, payload);
    }
}
```

**同构一目了然**：生产者代码两边一模一样的形态——常量 TOPIC、手拼 payload、send 三段式。

---

## 4. 动手验证：发真事件收真事件

### 第 1 步：跑一次真实业务（train 一单走两事件）

（真实输出——先登记登录 token，再下单并支付：）

```
$ B=$(curl -s -X POST http://127.0.0.1:8084/api/bookings \
      -H "Authorization: Bearer $TOK" -H 'Content-Type: application/json' \
      -d '{"tripId":5}')
{"id":25,"orderNo":"2d4bcba1-0ae5-4a1e-95e1-f21572c688d8","userId":38,"tripId":5,"seatNo":1,"status":"UNPAID",...}

$ curl -s -X POST http://127.0.0.1:8084/api/bookings/pay \
    -H "Authorization: Bearer $TOK" -H 'Content-Type: application/json' \
    -d '{"orderNo":"2d4bcba1-0ae5-4a1e-95e1-f21572c688d8"}'
{"id":25,"status":"PAID","paidAt":"2026-09-14T10:27:53.66Z",...}
```

`book()` 与 `pay()` 各调一次 `producer.orderCreated(...)`——**两条真事件此刻已在 topic 里**。

### 第 2 步：console consumer 现场收货（真实输出）

```
$ timeout 10 env KAFKA_HEAP_OPTS="-Xmx64M" tools/kafka/bin/kafka-console-consumer.sh \
    --bootstrap-server 127.0.0.1:9092 \
    --topic train-order-events --from-beginning --timeout-ms 8000 2>/dev/null

{"orderNo":"d1e0dde3-5175-4eb8-b725-f2dc49a2486b","tripId":1,"seatNo":1,"price":42,"phase":"CREATED"}
{"orderNo":"d1e0dde3-5175-4eb8-b725-f2dc49a2486b","tripId":1,"seatNo":1,"price":0,"phase":"PAID"}
{"orderNo":"d1e0dde3-5175-4eb8-b725-f2dc49a2486b","tripId":1,"seatNo":1,"price":0,"phase":"CANCELLED"}
{"orderNo":"2d4bcba1-0ae5-4a1e-95e1-f21572c688d8","tripId":5,"seatNo":1,"price":314,"phase":"CREATED"}
{"orderNo":"2d4bcba1-0ae5-4a1e-95e1-f21572c688d8","tripId":5,"seatNo":1,"price":0,"phase":"PAID"}
```

读证三条：

1. **收到的就是我们亲手发出的**：第 4、5 行就是刚才那单（orderNo `2d4bcba1-…`、tripId 5、price 314 真值、CREATED 与 PAID 两段）——**payload 的字段名与实值完全对上**。
2. **同 orderNo 成串有序**：第一单 `d1e0dde3` 的三连（CREATED→PAID→CANCELLED）连续出现——**"同一单落同巷"的现场初证**（K06 全面展开）。
3. **price 的语义**：CREATED 时是**真实票价**（314 元），PAID/CANCELLED 时传 0（业务只关心状态推进，金额在 DB）——**事件字段为消费目的服务，不是 DB 的复刻**。

### 第 3 步：fire-and-record 的"不等人"真证

send 返回 CompletableFuture——**不 get() 就不等 ack**：

```java
kafka.send(TOPIC, orderNo, payload);   // 发出即返回（将来时句柄可追）
```

 **教学口径**：本项目 business 不调 get()——**下单接口毫秒级返回**；"事件真发出去没有"由消费侧对账验收（上面的第 2 步就是验收实据）。K05 会补 "acks 配置与阻塞等回（get()）" 的可靠性叙事。

---

## 5. "发一收一"对账表

| 环节 | 生产者侧证据 | 消费者侧证据 | 含义 |
|---|---|---|---|
| 下单 | HTTP 200 `UNPAID`（H2 落档） | 收到 `phase:"CREATED"` 事件 | 生产→消费打平 |
| 支付 | HTTP 200 `PAID` | 收到 `phase:"PAID"` | 状态与事件同账 |
| 字段合同 | payload 手拼 JSON | 消费侧 extract 同名字段 | 字段名是双方合同 |

**一句总**：**payload 不是测试数据**——它是 K02 broker 上的一笔真账，K04 消费端靠它吃饭。

---

## 6. 思考题（先想 3 分钟）

1. `send` 三段式中 key 为空时分区怎么选？（round-robin 轮流扔）——那"保序"会怎样？什么业务可以接受 key=null？
2. 手拼 JSON 若 orderNo 里带引号会怎样？为什么本项目"敢手拼"？（对照"字段内容受控"与"换 JSON 库"的取舍。）
3. fire-and-record 里 fire 的不等人、record 的"有据"各指什么？如果发送失败（broker 挂了）会发生什么？教学版的损失面是什么？
4. train yml 的 kafka 段同时有 producer 与 consumer——同一个 JVM 是"双角色"。这与 K02 "一个 JVM 有人字两帽（controller+broker）"的同构之美在哪里？

## 7. 练习题

1. 给 payload 增加一个字段（如 `payMethod`），发一单真事件，并在 console consumer 收到带新字段的输出；贴实录。
2. 把 key 换成 tripId 发几单，观察"同车次所有单"聚一线；**写下业务判断**：你的业务保序需求在"单旨"还是"车次旨"？key 应随谁走？
3. 用 takeaway 跑一单并收到 CREATED/PAID 两条真事件（对账 K04 的 dispatch 流程）。
4. 试 `kafka.send("oops-topic","k","v")` 发往不存在的 topic——看 broker 端会不会自动建 topic（auto-create 的默认行为）；再想想生产的取舍。

## 8. 参考答案

**练习 1**（实测形态）：

```
{"orderNo":"2d4bcba1-...","tripId":5,"seatNo":1,"price":314,"payMethod":"card","phase":"CREATED"}
```

消费端读到的字段与 payload 模板一致——**字段合同双向打平**。

**练习 2**（口径）：key=tripId 意味着"同车次订单保序成一线"（如"同一车次的票也要按序"）；若业务的顺序要求是"同一订单先 CREATED 后 PAID"，key 必须随手 orderNo 走。**保序维度 = key 的业务含义**（K06 主课）。

**练习 3**（实录形态）：

```
{"orderNo":"2477c7fa-...","dishId":1,"qty":1,"phase":"CREATED"}
{"orderNo":"2477c7fa-...","dishId":1,"qty":1,"phase":"PAID"}
```

同 orderNo 两段并列——audit/dispatch 按各自组的节奏各自消费（K04 全卷）。

**练习 4**（实测要点）：发往不存在的 topic 成功（broker 默认 auto-create 开）。生产通常一建 `auto.create.topics.enable=false`——**拼写错误别让它"静默产出一条假频道"**，宁可显式失败。

---

## 9. 本节小结

- **配置**：`spring.kafka.producer` 两个 StringSerializer + bootstrap-servers 9092；train/takeaway 同构、仅组与 topic 各归各。
- **生产者真码**：TOPIC 常量 + 手拼 payload + `send(TOPIC, orderNo, payload)`；**key=orderNo 是保序种子**。
- **fire-and-record**：send 即回不等 ack（教学姿态）；"发出没有"靠消费侧验收，K05 再谈"确证"的高级形态。
- **发一收一真账**：CREATED 与 PAID 两条真事件现场收齐——payload 字段与消费解析完全对账。

---

## 10. 下一站

发送端独舞收场。下一站 K04 · 消费者与消费组——`@KafkaListener` 的真码语义（topics 与 groupId）、组内分工与组间广播的组语义、AuditConsumer 与 DispatchConsumer 两端对照、以及 **`--describe` 对账偏移量表**的现场实录（audit 5/5、dispatch 10/10 与临时组 cross 验证）。

---

## 附录 A：payload 字段合同表（组成部分对得上、变更即断账）

以 train 的 payload 模板为例，逐字段列出"从哪来、到哪去"：

| 字段 | 生产者实参来源 | 消费者读法（K04） | 业务含义 |
|---|---|---|---|
| orderNo | `book()` 里 `UUID.randomUUID().toString()` | `extract(payload,"orderNo")` | 订单身份；同时是消息 key |
| tripId | 路径/请求体的 `tripId` | （dispatch 不用它） | 哪趟车 |
| seatNo | `seqKey` 的 INCR 结果 | （不用） | 座位号 |
| price | `trip.priceYuan`（仅 CREATED 时非 0） | 价格审计 | 票价 |
| phase | 受控常量（CREATED/PAID/CANCELLED） | 门二校验 | 事件语义开关 |

**合同变化的规则**：任何字段改名 → 两侧（producer 模板/consumer extract）必须**同一次改动**齐了再上——这就是本章"字段名是双方合同"的可操作性版本。

## 附录 B：三段式 send 的写法备忘

| 形态 | 用途 | 排查 |
|---|---|---|
| `send(topic,payload)` | keyless（round-robin 分区） | 不保序——只适合独立事件 |
| `send(topic,key,payload)` | 有 key（同 key 同 partition） | 本项目标准形态：key=orderNo |
| `send(...).get(3,SECONDS)` | 阻塞等 ack | 要"确证送达"时才用（K05 语义展望） |

## 附录 C：fire-and-record 的完整语气表

| 问题 | fire（-fired）什么意思 | record（有据）指什么 |
|---|---|---|
| 发送时 | 不等 ack 即返回（下单不阻塞） | send 的返回是 CompletableFuture，将来时可查 |
| 失败时 | 可能静默重试（producer 侧默认 retries） | 失败结果可从 Future 读出（若真读） |
| 铁账 | "发出去了" ≠ "用户收到了" | 消费侧 `--describe` / console 收货才是验收 |

---

