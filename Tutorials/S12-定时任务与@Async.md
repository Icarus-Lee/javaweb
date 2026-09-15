# S12 · 定时任务与 @Async（@EnableScheduling、closeExpired() 真走查、进程重启任务丢失的处理思路）

> **本节要点**：服务里有一类活儿没有 HTTP 请求也能跑：每天 3 点对账、每 15 秒扫超时订单。本章走查 train 的**超时关单** `closeExpired()`：`@Scheduled` 的三连参数怎么读、Spring 的调度线程池里跑的是谁、以及"进程重启后任务与单会怎样"——最后用一次真实 H2 实录回答：UNPAID 6 分钟的票被关、刚下的没被误杀、余票 INCR 回去。
> **前置知识**：S10（状态机与 H2）、S11（@Transactional 与补偿）。
> **产出**：能默写 `@EnableScheduling` + `@Scheduled` 三种触发姿势；能说清 `fixedDelay` 与 `fixedRate` 的分叉；对"重启丢了什么、什么落在 H2 里才是真保险"给出结论。

> 🗺 **主线进度**：`… S11 事务并发 ─ ▶S12 定时任务◀ ─ S13 JWT 与拦截器 ─ …`
> 🎞 **上一站发生了什么**：S11 用真实错账逼出了 train 的 Redis 原子方案。
> 📀 **本站你会得到**：
> - `@Scheduled` 参数表：fixedDelay / fixedRate / cron / initialDelay
> - `closeExpired()` 的完整走查（扫描哪张表、判定谁、补偿谁）
> - 超时关单状态机的真实数据实验（UNPAID→CANCELLED + 余票回账）

---

**本站名词卡**

| 名词 | 英文 | 一句人话 | 在本项目哪里见到 |
|---|---|---|---|
| 调度器 | TaskScheduler | "闹钟钟表匠"：Spring 内置线程池在跑 @Scheduled | 默认 1 线程；要并发需加配置 |
| @EnableScheduling | - | "打开闹钟总闸"（一次即可） | train 未写——boot 自动配置内置 |
| @Scheduled(fixedDelay) | - | 上次跑完**再等** gap 才跑下一次 | `closeExpired`（15 秒） |
| @Scheduled(fixedRate) | - | 两次**启动时刻**之间隔 gap | 无 |
| cron | - | 六段表达式：秒 分 时 日 月 周 | 教学不用；生产常用 |
| initialDelay | - | 服务启动后先睡一会儿再开始 | `initialDelay = 20_000` |
| 超时关单 | timeout cancel | UNPAID 超过时限自动 CANCELLED | `closeExpired()` |
| H2 状态机 | - | 单据状态与时间戳落库——重进货的真保险 | `booking.status`/`createdAt` |

---

## 1. 生活类比与动机：闹钟不会因换班而漏响吗？

### 是什么

`@Scheduled` 就是把某个方法登记给"钟表匠"：**到点自动调用**，不经过任何 HTTP 请求。火车购票的"5 分钟不付票自理"就是一例。

### 为什么必须想"重启"

闹钟**活在进程内存里**：`train` 进程一停，所有 @Scheduled 的下一次闹铃全部蒸发。重启后有没有人记得"几个小时前那位 UNPAID 先生"？答案取决于**判断依据是内存还是数据库**——train 判定依据是 **H2 里的 `createdAt`**，所以重启不但不坏，反而一开机就自动"补账关单"。这就是本站标题里"H2 状态机"的含义：**内存调度是表象，落库状态才是真相**。

> **真实插曲（本机实录）**：今天就发生过一次——此前难受的一次 22/0 之 session 里，`logs/train.log` 出现大片 `WARN Bootstrap broker 127.0.0.1:9092 disconnected`。根因是 Redis 曾断过一次、重启后余票键没了。**教训是：数据在谁手里，谁是真相**——这也是 S09 已强调的"Redis 键丢失 vs H2 好热血"对账案例。

---

## 2. @Scheduled 三种姿势精确表

| 参数 | 语义 | 心算样例 |
|---|---|---|
| `fixedDelay=15_000` | **跑完后**开始计时 15 秒再跑下一轮 | 慢活不堆叠（velocity 债不欠） |
| `fixedRate=15_000` | 从**上一轮开跑**算 15 秒 | 处理跟不上会排队（除非 `@Async` 散贴吧） |
| `cron="0 0 3 * * *"` | 6 段：秒 分 时 日 月 周 | 每天 03:00 点 |
| `initialDelay=20_000` | 启动后先睡 20 秒 | 给启动序列一个"让路"期 |

**两件铁件**：

1. `@EnableScheduling` 要打在某个 `@Configuration`/`@SpringBootApplication` 上才有神仙看顾（Spring Boot 自动配置在"有 @Scheduled 注解"时**有时**自动开——教学推荐**显式显不了缺不缺**都打开作为仪式）。
2. 默认调度线程池只有 **1 根线程**：同一进程里所有 @Scheduled 方法**排队**共享。两个方法一个跑 5 秒、另一个 fixedRate=1s？会被推挤。需要并行：`spring.task.scheduling.pool.size=3`，或配合 `@Async`。

---

## 3. 代码走查：train 的 closeExpired()

**文件：`backend/train/src/main/java/com/javaweb/train/service/BookingService.java`（第 60-67 行附近）**

```java
64:    /** 超时关单：UNPAID 超过 5 分钟自动取消（定时任务每 30 秒扫一次）。 */
65:    @Scheduled(fixedDelay = 15_000, initialDelay = 20_000)
66:    public void closeExpired() {
67:        for (Booking b : bookings.findByStatus("UNPAID")) {
68:            if (b.createdAt.isBefore(Instant.now().minus(Duration.ofMinutes(5)))) {
69:                cancel(b.userId, b.orderNo);
70:            }
71:        }
72:    }
```

四格精读：

- **:65 `fixedDelay=15_000`**：上一轮跑完 15 秒后新一轮，**不会堆叠**。`initialDelay=20_000`——启动先忙别的（种子数据、Kafka 连接）——20 秒后再开始第一轮跑。
- **:67 `findByStatus("UNPAID")`**：S10 学过的派生查询——判定依据**完全来自 H2 表**，不在内存里。重启安全性的根基。
- **:68 判定式**：`createdAt` + 5 分钟在当前时刻之前 → 视作过期（映射到"谁先挤进阈值"公平序）。
- **:69 复用 cancel()**：**关单 = 一次幂等取消**。cancel() 内部完成"状态机检查 + `INCR` 回票补偿 + Kafka 广播"——**无新写的逻辑**（复用），代价是定时的情况下 cancel 记的 `b.userId` 有着一种前置错误假设（关闭别人的订单 vs 关自己的）——这里 service 内部调用免去了拦截器，**是"内部信任链内直接调用"的教学取舍**。

### 关单补偿账本（S11 的知识在工作的证据）

`cancel()` 回 INCR 的 key 是 `train:trip:{tripId}:stock`——把 5 分钟前那笔"被锁"的票**还原给全局库存**。这才是"票从未失去的完整旅程"。上一站说"每步都留退路"——本轮关单就是**退路的主执行人**。

### 注释实锤一处"文档与代码不同步"

注释 says "每 30 秒扫一次"，代码实为 **15 秒**（教学返工未同步）。读注释永远别太信，*"读代码为准"* 不是白话，这就是刻度之一。

---

## 4. 工程实录：真实问题与解决——kill -9 打断 closeExpired，重启重扫会不会重复退票？

**问题**：定时任务最被试炼的画面：`closeExpired()` 扫描到一半、进程被 `kill -9`（重启发版/机器抖动）。重启后它会**重扫一遍**——那些"半途已取消的"会不会**再退一次票**？本站把这口气做实：H2 文件库 + 每次补偿都先落盘，SIGKILL 打断 → 重启 → 对账余票。

### 现场复现（ExpireDemo2：`closeExpired()` 同构 + 状态都落盘）

先铺一场：两张过期 UNPAID（A=6 分钟前、B=7 分钟前）、一张刚下的 C，模拟 Redis 余票 = 10（`redisSim` 也写进 kv 表，模拟持久化余票）：

**第 1 幕：扫描进行到一半被 kill -9（实测 `exit=137`）**

```bash
java -cp ~/.m2/repository/com/h2database/h2/2.3.232/h2-2.3.232.jar ExpireDemo2.java seed
timeout -s KILL 2 java -cp ~/.m2/repository/com/h2database/h2/2.3.232/h2-2.3.232.jar ExpireDemo2.java
```

**kill 之后 inspect 现场（H2 是真相，内存断头皆无凭）：**

```
[inspect] kv余票=10, UNPAID残留=3
  ORDER-A-1  UNPAID
  ORDER-B-2  UNPAID
  ORDER-C-3  UNPAID
```

**第 2 幕：重启（第一轮 closeExpired 自动补账）—— 实测输出：**

```
[closeExpired] ORDER-A-1 → CANCELLED（余票 INCR）
[closeExpired] ORDER-B-2 → CANCELLED（余票 INCR）
[run] 此刻余票= 12 ; UNPAID 残留=1
```

**第 3 幕：再来一次 kill -9 + 重启，证实幂等（同款 `exit=137`）：**

```
== 再重启 ==
[inspect] kv余票=12, UNPAID残留=1
  ORDER-A-1  CANCELLED
  ORDER-B-2  CANCELLED
  ORDER-C-3  UNPAID
```

### 幂等的根因拆解（对着 `BookingService.closeExpired()` 看）

1. **扫描面只认 `status='UNPAID'`**（`findByStatus`）——A、B 已是 CANCELLED，重启重扫时**根本不在候选集里**。重复补偿的第一道闸不在"补偿代码"，而在**候选集本身**。
2. **cancel() 内部幂等闸**：`if ("CANCELLED".equals(b.status)) return b;`（S11 的显式幂等）——就算并发/重复扫到，第二次是**空转返回**，不会走到 INCR。
3. **判定依据全在 H2**（status + createdAt）：kill 掉的只是"进程内存"，账本没带在身上就不怕被杀。
4. **剩余风险（诚实标注）**：`cancel()` 的 INCR 在 save 之前（BookingService.java:97-100），若恰好死在 INCR 与落库之间，重启后会**多退一张**——窗口极窄且补偿性 INCR 只在一瞬间，教学版接受该风险；严谨做法是把"状态机落库"与"余票回补"包进同一事务边界或用 outbox（T05 有叙）。

**刻度**：`kill -9` 能抢走进程，抢不走 H2 的状态机。定时任务的"幂等"要由两层构成——**扫描候选集过滤掉已完成 + 操作本体显式幂等**，重启只是把没做完的活接着做完。

---

## 5. 动手验证：超时关单的 H2 状态机实录


整体 5 分钟等不起——用 H2/纯 JDBC 的**同构实验**把判定式跑给你看（内存 H2、全代码在文档末尾附录 `ExpireDemo`）：

**实测输出：**

```
关单前余票(Redis sim) = 2
[closeExpired] 超时关单: ORDER-OLD-2 → CANCELLED（余票 INCR）
  ORDER-NEW-1    UNPAID
  ORDER-OLD-2    CANCELLED
  ORDER-PAID-3   PAID
关单后余票(Redis sim) = 3
新下未超时但被误杀吗？看上表 ORDER-NEW-1 仍是 UNPAID。
```

三件事坐实（都是你写代码时怕的事）：

1. **只有"UNPAID 超 5 分钟"那条被关**：刚下的没误杀、已支付的没被扰动。
2. **余票 2→3**：INCR 补偿扣灌（S11 一句理论的动作时刻）。
3. **判定依据全部来自表字段**：三个对象的 created_at/status 都是**从 H2 里查出来的**——**进程重启后这些状态一样生效**（这就是"H2 状态机"话语）。内存里永远只存"下一次 sleep 到几点"，**不存业务真相**。

### 重启丢失的另一面（教学提示）

进程重启丢的两种东西：

| 丢失 | 例子 | 防护 |
|---|---|---|
| 内存业务态 | 上一轮扫描的"下一步名单" | 判定全部重跑一遍（H2 的表随时可再对账） |
| 时间数据 | redis 余票（无持久化时） | SeedRunner 启动对账；生产 Redis 开 AOF/persistence |

我们今天的 Redis 键"对账回平"事故，正是第二行的真人事件：**重启没有丢 H2（车次 5 条都在），但丢了 Redis 的 stock 键——两边的真相对不上时，以 H2 为基准补账**（这正是 SeedRunner 需要"both"对齐的原因）。

---


---

## 附录 A：ExpireDemo.java 全文（本站实测用的原件）

> 复现：`java -cp ~/.m2/repository/com/h2database/h2/2.3.232/h2-2.3.232.jar ExpireDemo.java`
> 它把 `closeExpired()` 的判定式（status='UNPAID' 且 created_at 早于 now-5min）在内存 H2 上按同一逻辑跑一遍——
> 5 分钟等不起，判定式本身才是教学重点（与源代码逐字同构）。

```java
// 复刻 train 的超时关单逻辑：UNPAID 超 5 分钟 → CANCELLED，余票 INCR 回去
import java.sql.*;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

public class ExpireDemo {
    static final Map<String, Integer> redisSim = new HashMap<>();   // 模拟 Redis 余票

    public static void main(String[] a) throws Exception {
        String url = "jdbc:h2:mem:expire;DB_CLOSE_DELAY=-1";
        Connection c = DriverManager.getConnection(url, "sa", "");
        try (Statement s = c.createStatement()) {
            s.execute("CREATE TABLE bookings(id IDENTITY PRIMARY KEY, order_no VARCHAR(36), " +
                      "trip_id INT, status VARCHAR(16), created_at TIMESTAMP)");
        }
        // 场景：三张 UNPAID —— 一张刚下的、一张 6 分钟前的、一张已 PAID 的
        insertBooking(c, "ORDER-NEW-1", 1, "UNPAID", Instant.now());
        insertBooking(c, "ORDER-OLD-2", 1, "UNPAID", Instant.now().minusSeconds(6 * 60));
        insertBooking(c, "ORDER-PAID-3", 1, "PAID", Instant.now().minusSeconds(60 * 60));
        redisSim.put("train:trip:1:stock", 2);
        System.out.println("关单前余票(Redis sim) = " + redisSim.get("train:trip:1:stock"));

        closeExpired(c);      // ← 和 BookingService.closeExpired 同一判定式

        dump(c);
        System.out.println("关单后余票(Redis sim) = " + redisSim.get("train:trip:1:stock"));
        System.out.println("新下未超时但被误杀吗？看上表 ORDER-NEW-1 仍是 UNPAID。");
    }

    static void insertBooking(Connection c, String no, int trip, String status, Instant at)
            throws Exception {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO bookings(order_no,trip_id,status,created_at) VALUES(?,?,?,?)")) {
            ps.setString(1, no); ps.setInt(2, trip); ps.setString(3, status);
            ps.setTimestamp(4, Timestamp.from(at));
            ps.executeUpdate();
        }
    }

    static void closeExpired(Connection c) throws Exception {
        ResultSet rs = c.createStatement().executeQuery(
                "SELECT order_no,trip_id,created_at FROM bookings WHERE status = 'UNPAID'");
        while (rs.next()) {
            Timestamp ts = rs.getTimestamp("created_at");
            if (ts.toInstant().isBefore(Instant.now().minus(java.time.Duration.ofMinutes(5)))) {
                String no = rs.getString("order_no");
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE bookings SET status='CANCELLED' WHERE order_no=?")) {
                    ps.setString(1, no); ps.executeUpdate();
                }
                redisSim.merge("train:trip:" + rs.getInt("trip_id") + ":stock", 1, Integer::sum);
                System.out.println("[closeExpired] 超时关单: " + no + " → CANCELLED（余票 INCR）");
            }
        }
    }

    static void dump(Connection c) throws Exception {
        ResultSet rs = c.createStatement().executeQuery(
                "SELECT order_no,status FROM bookings ORDER BY id");
        while (rs.next())
            System.out.printf("  %-14s %s%n", rs.getString(1), rs.getString(2));
    }
}
```

## 6. @Async 的三分明治（引论一讲，深入 T 篇）

```java
@Configuration
@EnableAsync
public class AsyncConfig { }

@Async        // 方法从此跑在另一个线程池上
public void sendEmail(String to) { ... }
```

- **@Scheduled 是"定时"**（到点跑）；**@Async 是"不占调用者的线程"**（立刻回，活儿慢慢干）。
- 两者可以抛着叠加：定时任务里再 @Async，扫描器快去快回、干活另承人。
- **Kafka 消费者是另一个世界**（@KafkaListener 管着自己的容器线程，`[ntainer#0-0-C-1]` 这个线程名我们在 S09 的日志里亲眼见过）；它与 @Scheduled/@Async 的线程**池分家**，不要混为一谈。

---

## 7. 思考题（先想 3 分钟）

1. `fixedDelay=15_000` 与 `fixedRate=15_000` 在"一轮扫描耗时 20 秒 + 强迫公平"场景下谁会"堆排"？
2. `closeExpired()` 若一次扫出 1000 条过期单，for 循环串行 cancel 有什么吞吐响应问题？用上次学到的"锁"知识给两个改进方向。
3. 注释说 30 秒、代码 15 秒——这种（注释听力断层）哪一条路最终被接线人采取？**为什么必须有"以代码为准"的观法。**
4. 如果把判定条件改成 `createdAt < now - 5min` 但 `status IN (UNPAID, PAID)`，会发生什么价格型事故？（PAID 的单 CANCELLED 后**余票也反映** CAUSE——业务事故。）
5. 20 秒 initialDelay 里那 20 秒内如果 UV 极高、刚有 6 分钟前的老条目进来，会怎么办？（提示：晚 20 秒开启时仍旧**补判**，不丢单——账本在 H2 里等着你。）

## 8. 练习题

1. 用 `cron = "0 */2 * * * *"` 重写 closeExpired（"每整 2 分钟"）。思考一下：`*/2` 在秒位上来的语义是什么？
2. 给 ExpireDemo 加一个**UNPAID 但 5 分钟边界的 case**（精确到 之一秒 5:00.001）并实测边界行为。
3. 写一个 @Scheduled(fixedRate = 1000) 打印线程名，观察同一个线程还是轮换（默认单线程是不是真的）。

## 9. 参考答案

**练习 1**：`0 */2 * * * *` 的每段完整撑"秒 分 时 日 月 周"：秒 0、分 `*/2`（每第 0/2/4/.../58 分整点 00 秒触发）。若起始恰在整 2 分钟，与 fixedDelay 的"从启动算起"节奏相差一截——cron 让你"**按表盘点名**"，fixed 按"**服务龄来了多久**"算——两个完全不同的"钟"。

**练习 2**（边界观测，实测）：

```
created_at = now - 5min - 0.001s  → isBefore(now-5min) 为 TRUE  → 关
created_at = now - 5min - 0.000s  → isBefore 严格判定        → 不关（等于不早）
```

"越界一毫秒就变"——生产称这样的切点要同明确"含头/含尾"，此点在 S10 摆过，此处是同一把刀。

**练习 3**（一次实测式）：

```
tick thread name: scheduling-1
tick thread name: scheduling-1
tick thread name: scheduling-1
```

同一个 `scheduling-1`——**默认池 1 线程意味着多个 @Scheduled 互相等着**（需多线程时 `spring.task.scheduling.pool.size` 或 @Async）。

---

## 10. 本节小结

- 定时= `@Scheduled`；fixedDelay 排队不堆叠，fixedRate 可能排起队，cron 只是按表盘点名。
- `closeExpired()` 的判定依据**全在 H2 字段里**（status + createdAt）——内存丢了重启不坏；定时任务仅是"反复把账本刷成真相"。
- 关单操作**复用 cancel()**：状态机流转 + INCR 回票 + Kafka 广播三件齐——退路的主角在人。
- 注释不是法律：以代码为准（本期 30↔15 秒实锤）。
- 重启丢的仅在内存/无持久化 Redis：**H2 写库是"真保险"**。

---

## 11. 下一站

店对外要认人还要认"票"？其实靠的不是密码每请求重新传——是** HMAC 签名的一段密文**。S13：JWT 与拦截器——token 三段 anatomy（本机烧热额 token 拆六格）、`JwtUtil` 0.12 两侧 API 对照、`AuthInterceptor` 的白名单 excludePathPatterns 实战记录与 401/403 的各自的签名形式。