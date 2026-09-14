# R03 · Java 交互：StringRedisTemplate（连接走查、opsForValue、"Java 类型映射成字符串"、demo-counter 真码）

> **本节要点**：Java 怎么跟 Redis 说话？答案分三层：**连接配置**（application.yml 的 `spring.data.redis`）、**操作门面**（`StringRedisTemplate` 与 `opsForValue()`）、**最重要的心法**——"Java 类型映射成字符串"。Redis 这边永远只认字符串，所有类型映射是 **Java 这一侧自己负责**。本章用 demo-counter 的真码逐行走查，并做"curl 与 redis-cli 双脚对照"实测。
> **前置知识**：R01/R02（Redis 本体与五种结构）、S08（Spring 配置体系）。
> **产出**：能给任意 Spring Boot 项目三分钟接入 Redis；能不看文档写出"自增一个 key / 读写一个字符串"；能说清 `RedisTemplate` 与 `StringRedisTemplate` 的差别与进坑史。

> 🗺 **主线进度**：`… R02 五种结构 ─ ▶R03 Java 交互◀ ─ R04 分布式锁 ─ …`
> 🎞 **上一站发生了什么**：R02 把五种结构各钉死一个场景，TTL 三态背熟。
> 📀 **本站你会得到**：
> - `spring.data.redis` 配置块逐行走查（train 与 demo-counter 两个真值对照）
> - demo-counter `CounterController` 全真码走查
> - "Java 对象 → Redis 字符串"的映射心法 + 双脚对照实测

---

**本站名词卡**

| 名词 | 英文 | 一句人话 | 在本项目哪里见到 |
|---|---|---|---|
| StringRedisTemplate | - | 只装字符串值的 Redis 操作门面（本项目专用） | demo-counter / train 的 `service` |
| RedisTemplate | | 泛型模板：可装对象（含序列化坑） | 本章"避坑"区 |
| opsForValue | operations-for-value | "String 抽屉的操作把手"（ 对 List/Hash/ZSet 各一对） | `CounterController` |
| 序列化 | serialization | Java 对象 ↔ 字节的翻译官 | 本章心法主战场 |
| Lettuce | /ˈlɛtəs/ | Spring Boot 默认底层连接客户端（读"生菜"） | starter-data-redis 自带 |

---

## 1. 生活类比与动机：店内翻译台

R02 你在 redis-cli 前台直接喊话。Java 后端不是前台：**Redis 只讲一种语言（字符串）**，而 Java 讲对象语言（`Long`、`Map`、POJO）。`StringRedisTemplate` 就是**店内的翻译台**：你用 Java 的语法下单（"给 demo:counter 加 1"），翻译台把它译成 Redis 的命令（`INCR demo:counter`），再把答复译回 Java（返回 `Long`）。

**本节最重要的直立心法**：**翻译台的两援只有一个长度——字符串**。所以：

1. 存对象？先自己在 Java 侧变字符串（`toString` / `record` / JSON）。
2. 读回来？留神**谁写的谁读**，"年代/两代写入风格"混写会读出怪东西。
3. 数字算术（INCR/DECR）**服务端拼接**（不是 Java 侧先读再加再写）——这一条是 R04 防超卖的地基，本章先把姿势钉住。

---

## 2. 连接从哪来：application.yml 走查

### demo-counter 的步步简洁版

```yaml
spring:
  application:
    name: demo-counter
  data:
    redis:
      host: 127.0.0.1
      port: 6379
server:
  port: 8083
```

三行就打完收工（`host`/`port`）。**没有任何账号密码、没有连接池调优**——为什么？因为 Redis 本地默认无密码（`requirepass` 未设），Spring 自动补上其余（超时、连接池、Lettuce 客户端）。**最小配置恰好能跑**，这正是"约定优于配置"的兑现。

### train 的对照（同一块、多了一串 Kafka）

```yaml
spring:
  data:
    redis:
      host: 127.0.0.1
      port: 6379
```

**Redis 部分一字不差**——host/port 都是环境参数，不随业务变大而变。train 真正不一样的只有旁边的：

```yaml
  kafka:
    bootstrap-servers: 127.0.0.1:9092
```

（Kafka 留给 K02。）

> **S08 复习钩子**：生产里这份 yml 会写成 profile 差异（`application-prod.yml` 里 host 指向 `$REDIS_HOST`、`port` 与密码）。本章的教学实例专注 127.0.0.1，差别在**值**而非**结构**。

### 依赖从哪来

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-redis</artifactId>
</dependency>
```

这一个 starter 同时干三件事：带 Lettuce 客户端、带自动配置（读 yml 拼出 ConnectionFactory）、带 `RedisTemplate`/`StringRedisTemplate` 两个现成 Bean。**你什么都不用 new**——S02 的 IoC 铰链正是：**starter 提供 Bean，构造器注入交给 Spring**。

---

## 3. demo-counter 真码走查（完整源码，未删一行）

**文件：`backend/demo-counter/src/main/java/com/javaweb/counter/CounterController.java`**

```java
@RestController
@RequestMapping("/api")
public class CounterController {
    public static final int STOCK = 10;
    private final StringRedisTemplate redis;

    public CounterController(StringRedisTemplate redis) {
        this.redis = redis;
    }

    // 计数器：每次访问 +1，返回最新值。
    @GetMapping("/counter")
    public Map<String, Object> counter() {
        Long n = redis.opsForValue().increment("demo:counter");
        return Map.of("n", n == null ? 0 : n);
    }

    // 秒杀雏形：只有 10 张票，用 Redis 原子递减，抢超自动拒绝。
    @PostMapping("/seckill")
    public Map<String, Object> seckill() {
        Long left = redis.opsForValue().decrement("demo:seckill:stock");
        if (left == null) {
            redis.opsForValue().set("demo:seckill:stock", String.valueOf(STOCK));  // 首次初始化
            left = redis.opsForValue().decrement("demo:seckill:stock");
        }
        if (left < 0) {
            redis.opsForValue().increment("demo:seckill:stock");   // 还回去
            return Map.of("ok", false, "reason", "已售罄");
        }
        return Map.of("ok", true, "left", left);
    }
}
```

**五步走查**：

1. **构造器注入 `StringRedisTemplate`**（S02 的 DI 三招之一）：Http 请求进来前它已在。这里用 `final`——S 系列一以惯之的"字段不可换芯"。
2. **`opsForValue()`**：先拿到"String 抽屉的把手"。这是一个**普通对象方法**，可以连着调。想用 List/Hash/ZSet 就换 `opsForList/opsForHash/opsForZSet`——与 R02 的五种抽屉一一对应。
3. **`increment("demo:counter")`** 译成 Redis 命令 `INCR demo:counter`，**返回 Long**（新值）。**没有 Java 侧的"读-改-写"**——现在明白 R01 说"INCR 服务端原子"的架构含义了吧。
4. **`n == null ? 0 : n`**：**判空守则**。本地单机几乎不会 null，但**协议层**说"key 出现若干特殊状况时返回 nil"，接口层的 `Map.of` 不允许 null 值——兜住。
5. **`increment/decrement` 返回 `Long`、set 接 String**：这就是本节的"Java 类型映射成字符串"的**第一现场**——详情在下一节。

---

## 4. 非常重要的事：Java 类型映射成字符串

`StringRedisTemplate` 泛型是 `<String, String>`：**key 是字符串、value 也是字符串**。所有"Java 类型"都发生一个两步翻译：

```
Java 侧                    Redis 侧（看到的一切都是字符串）
────────────                ─────────────────────────
int/long  42    → toString →  "42"
boolean true?             →  存 "true" 或约定 "1"/"0"（自己定！）
POJO/record  → JSON 序列化 →  '{"name":"小鱼"}'
Long(自增结果) ← parseBack ← "42"（返回时已类型化）
```

**三条行动心法**：

1. **数字别过度包装**。直接存 `"42"`，用 `increment/decrement` 让 Redis 算——Java 侧只负责"从 Long 读回业务量"。**若你自己 GET 出来再 parseLong 再 setString，原子性就没了**（这就是 S11 复现的错账 Redis 版）。
2. **对象序列化自己写**（教学版）：`toString` / `record` / JSON。JSON 串一旦格式约定变更，**老 key 里存的旧格式不会自动升级**——迁移要写"双读兼容"，别忘了。
3. **布尔/枚举 定约定常量**：规范如 `status:"UNPAID"` 直接 SADD/SET 字符串，**不要编 1/0 魔数**——调试时 redis-cli 一眼读得出。

### 对照：`RedisTemplate`（泛型模板）的坑史

`RedisTemplate<String, Object>` 若不做额外配置，JDK 序列化（JdkSerializer）一上——key/value 里塞进**二进制垃圾**，redis-cli 里 `KEYS` 出 `\xd2\x91…` 花码，全功能"报废"。所以要自己指定 JSON 序列化器。**结论写死：本项目一律 `StringRedisTemplate`**，序列化责任回到业务代码手上——**多写两行、少踩大坑**，教学和生产一视同仁。train 的 `BookingService` 注的就是它：

```java
private final StringRedisTemplate redis;   // train/.../service/BookingService.java
```

---

## 5. 动手验证：curl 与 redis-cli 双脚对照

**环境**：`start-all.sh` 跑过，demo-counter 在 8083。看看计数器现在落在哪（接续 R02 期间未重置），**逐行真实输出**：

```
$ redis-cli GET demo:counter
"21"
$ curl -s http://127.0.0.1:8083/api/counter
{"n":22}
$ curl -s http://127.0.0.1:8083/api/counter
{"n":23}
$ redis-cli GET demo:counter
"23"
```

**读解**：

- **curl 两次 +1 → Redis 侧 21→23 一一对应**：**Java 的 increment 与 redis-cli 的 INCR 是同一条命令**，没有任何"第二账本"。
- GET 显示 `"23"` 带引号是 cli 的字符串回显格式，值的类型其实是"字符串形式的数字"；Java 端读回时靠 **increment 返回 Long** 而不是 parse——**写入端与读回端风格要配套**，这是原子与类型双保险的合同。
- 顺手看它家的 `TYPE` 与 `TTL`（R02 的理论三态在此连机）：

```
$ redis-cli TYPE demo:counter
string
$ redis-cli TTL demo:counter
-1                                  ← 计数器永生，没有倒计时
```

**思考点到即止**：现在你老知道为什么 R02 说"存成什么样才能一条命令答你的问题"——Java 这一声 `opsForValue().increment("demo:counter")` 十一拼出 `INCR` 抽屉的把手，一切都按 Redis 的语言办事。

## 6. 三条"换了要命"底线

1. 永远 `StringRedisTemplate`（不是泛型 `RedisTemplate`）。
2. 算术交给 Redis（`increment/decrement`），**Java 侧不读改写**。
3. key 命名跟 `域:对象:字段`（R01 命名法），字段硬盘级诚实——一日屡次 KEYS 看上去都清爽。

## 7. 思考题（先想 3 分钟）

1. `increment` 与"先 GET 再 set(get+1)"的本质差别是什么？一次 demo-counter 里让两者**都同时**跑一次，哪种会"维护不了并发计数"？请演示与写两条对应的 curl。
2. demo-counter 的 `demo:counter` 是 `-1` 永生、`otp:session:8848` 有 5 秒 TTL——什么时候**业务上**必须给计数器挂 TTL？（提示：限流 R05 的滑动窗口。）
3. 若把 demo-counter 的 redis 注入换成 `RedisTemplate<String, Object>`（默认 JdkSerializer），第一次 `increment` 会发生什么？（试出来对比……不需要真跑，从"序列化会把 key 变什么样"推理。）
4. `Map.of("n", n)` 在 n=null 会抛异常（`Map.of` 禁 null）——**这正是源码判空的实用性根据**。除了判空，还能改成什么返回结构让 null 也能安全携带？（`HashMap.putAll`，record？想一下取舍。）

## 8. 练习题

1. 给 demo-counter 增加一个 `GET /api/seckill/status` 已有接口旁边的新接口：`POST /api/greet`，它在 Redis 里维护 `demo:greets` 一个**计数器**（`SADD` 下的 String？不对，用 `INCR`），返回 `{"thanks": n}`。用 curl 连打三次，再 `redis-cli GET demo:greets` 对照——把双脚输出贴出来（应同民 3）。 
2. 把 demo-counter 的 `increment` 改成"GET → 读取 Long.parseLong → SET(读数+1)"（读改写三步版），跑一次看接口正常，然后**两个终端同时 curl** 三次，看 `redis-cli GET` 最终值是不是已经是"零（报响）和时间（越加越对不上）"——把 3 次 GET 后真值写出来。**这一练是 R04 的先声**。
3. 用 `opsForHash` 在 demo-counter 里存"访问档案"：`HINCRBY demo:visits today 1`、`HINCRBY demo:visits total 1`，返回两条数字。问一个延伸：如果"today" 想挂过期，你会怎么设计 key使他们各自能 TTL？（提示：TTL 是 key 级——所以把 "today" 拆成"date 型 key"如 `demo:visits:2026-09-14`，**key 里放日期**就自动过期——这就是当日口径的普通做法。）

## 9. 参考答案

**练习 1**（示例实现 + 实测形态）：

```java
@PostMapping("/greet")
public Map<String, Object> greet() {
    Long n = redis.opsForValue().increment("demo:greets");
    return Map.of("thanks", n);
}
```

```
$ curl -X POST .../api/greet ; curl -X POST .../api/greet ; curl -X POST .../api/greet
{"thanks":1} {"thanks":2} {"thanks":3}
$ redis-cli GET demo:greets
"3"
```

双脚对照成立——**接口返回的数字与 Redis 里存的字符串一码事**。

**练习 2**（读改写三步版实测记录）：

两个终端并发 curl 三轮后，`GET demo:counter` 的最终值**小于**真实总访问数（例如 26 而不是 28）：两次"读-改-写"交错时，后写覆盖先写。结论：**COMMAND 原子 = 唯一解**；R04 会给大家一次"两个并发 curl 抢一张票"的正式对决。

**练习 3**（ today 过期思路）：

```java
redis.opsForHash().increment("demo:visits:" + LocalDate.now(), "today", 1);
redis.expire("demo:visits:" + LocalDate.now(), Duration.ofHours(24));
```

**key 里含日期** + 全 key TTL 24h——跨过"field 无 TTL"的边界，代价是查询"过去 7 日总和"要拼 7 个 key（MGET/数据端聚合），取舍两清。**顺手补一个**："total" 存永生 key `demo:visits:total`，不要试图 TTL 一部分字段。

---

## 10. 本节小结

- 连接：`spring.data.redis.host/port` 三行起步；依赖一个 starter 全包（Lettuce + 自动配置 + 两个现成 Bean）。
- 操作：先 `opsForValue()` 拿把手，再 `increment/get/set`；Long/数字的**算术留给 Redis**。
- **最大的心法就一句**：Redis 侧只有字符串；Java 侧的类型映射自己负责——本项目全体用 `StringRedisTemplate`，序列化责任在手，短工三分熟，风险清清爽爽。
- 双脚对照实测成立：**curl 的 JSON 数字与 redis-cli 的 GET 完美同账**。

---

## 11. 下一站

计数器是"一个人玩"，下单是"千百人同时抢"。R04 · 火车票的预扣与分布式锁——train 的 key 体系 (`stock/seq/lock`)、DECR 的原子性、`setIfAbsent + TTL` 锁的"人走门关"、BookingService 六步流水线逐行走查、以及两个并发 curl 同时下单的**真实实测输出**（余票不超扣；握到的 HTTP 500 与 kafka 事件的全记录）。
