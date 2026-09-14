# Java速通 A08 · 并发基础：线程池、Runnable、Atomic 与 CopyOnWriteArrayList

> **本站你走在大厅哪一格**：这是一条"单人走廊变多人跑道"的通道。
> 你已经会写一个类、一个方法，本篇的问题是：**很多"事"同时发生时，Java 怎么管？**
> 一个 Spring Boot 服务每秒里同时有几十个请求线程在跑——本篇从最裸的 `Thread`
> 一路走到线程池和原子类，最后落到本站 demo-chat 里那两行真正的 `CopyOnWriteArrayList`。

## 名词卡

| 名词 | 人话 |
|---|---|
| 线程 | 进程内部的"执行腿"；一个 JVM 进程里可以同时有很多条腿 |
| Runnable | "一段可以交给腿去跑的事"——`void run()` 接口 |
| 线程池（ExecutorService） | "腿的招聘处 + 调度台"：招几条常驻腿，把任务扔给它跑 |
| 闭锁（CountDownLatch） | "等人到齐的栅栏"：主线程 `await()` 阻塞，数完才放行 |
| 原子值（AtomicLong 等） | "改一改就保证正确"的变量，CAS 支持下不怕并发 |
| CAS | Compare-And-Swap：比较-交换，线程安全的"改一次"最小原语 |
| CopyOnWriteArrayList | "写时复制"的线程安全列表：读不加锁，写一份副本整体换 |
| 竞态（race condition） | 多条腿乱跑时互相踩脚，结果随调度运气变化 |

## 一、人话：为什么一口锅要配几双手

Spring Boot 的 web 服务**天生多线程**：Tomcat 收到请求后从线程池派一条线程去处理，几十个并发是常态。
如果你写"全局只剩 1 张票时判断 `if (stock>0) sell()`"，两个线程同时读到 stock=1、同时卖——票就超卖了。
所以并发不是可选技能，而是把 demo 写对、把后文 BookingService 防超卖写对的前提。

Java 里"起一条腿"三种姿势：

```java
// 1) 裸 Thread（新手 ok，这是"直接招聘"）
Thread t = new Thread(() -> System.out.println("hi from leg"));
t.start();

// 2) Runnable 显式上手（"把事与执行者分开"）
Runnable job = () -> System.out.println("do my job");
new Thread(job).start();

// 3) ExecutorService（生产方式）
ExecutorService pool = Executors.newFixedThreadPool(4);
pool.submit(job);                       // 任务排队，4 条常驻腿轮流跑
pool.shutdown();                        // 收班
```

为什么生产不用裸 Thread？一次请求起一条腿、用完就销毁的**创建/销毁开销特别大**——
像餐厅不是每来一位客人就新雇一个服务员。线程池的答案：先备 N 条常驻腿 + 一个任务队列，任务来了直接复用。
Spring Boot 内嵌 Tomcat 默认约 200 条工作线程，本项目 5 个 jar 的 API 全靠它扛。

### Atomic\*：无需加锁的"只改一次"

```java
AtomicLong hits = new AtomicLong();
hits.incrementAndGet();      // 原子 +1；内部 CAS：比较-交换一步到位
long now = hits.get();       // 随时读
```

C++ 的 `std::atomic<long> x; x++` 与此完全同义，只是 Java 用对象包装 + 方法名表达。

并发"写"场景的选择顺序：**Atomic（单值无锁）→ ConcurrentHashMap（无锁散列表）→ synchronized（加锁大祭）**。
能用前两个就别上第三个（更慢、更容易写错）。

### 闭锁（CountDownLatch）：等人到齐

```java
CountDownLatch gate = new CountDownLatch(3);   // 3 人到齐才开门
for (int i = 0; i < 3; i++) {
    int id = i;
    pool.submit(() -> { doThing(id); gate.countDown(); });
}
gate.await();                                   // 主线程卡在这，等 3 个都 countDown 才往下
```

并发测试、并发模拟、"所有分片写完才继续"——CountDownLatch 是 Java 世界的 fork-join 等齐点。

## 二、CopyOnWriteArrayList vs synchronized：什么场景谁更好？

打开 demo-chat 真码（backend/demo-chat/src/main/java/com/javaweb/chat/ChatController.java:17-18）：

```java
private final List<Map<String, String>> history = new java.util.concurrent.CopyOnWriteArrayList<>();
private final List<SseEmitter> viewers = new CopyOnWriteArrayList<>();   // 所有在线"观众席"
```

两处都用 CopyOnWriteArrayList 而不是 `Collections.synchronizedList`，原因是它的语义恰好匹配：

**CopyOnWriteArrayList 的机制**：

- 每次**写**（add/remove）先**整体复制一份新数组**，在副本上改，改完原子替换引用。
- **读**（get、迭代）不加锁、无 check——迭代器拿到的是当时快照，因此
  "一边遍历一边加元素"那个经典的 `ConcurrentModificationException` 不会发生。
- 代价：每次写 O(n) 大拷贝——所以**只适合读多写少、列表不长**。

**demo-chat 为什么两处都命中"读多写少"**：

| 列表 | 读频率 | 写频率 | CopyOnWriteArrayList 划算吗 |
|---|---|---|---|
| `viewers`（在线观众） | 每次广播都遍历，可能每秒几十次 | 观众连上/断开，很少 | 划算 |
| `history`（聊天记录） | 新观众接入时快照下发（ChatController.java:27） | 每条新消息写一次 | 划算 |

对应代码节奏：`send()` 里 `history.add(msg)`（写少），`broadcast()` 里 for 遍历 viewers 后 `em.send(...)`（读多）。
try-catch 里把断线的 emitter `viewers.remove(em)`——同样是一次低频写，重叠在"高频读"场景下可接受。

**什么时候 synchronized 反而更好？**

- 列表动辄上万元素且**写频繁**（拷贝开销不可接受）；
- 需要"读判 + 写"原子性（`if (!list.contains(x)) list.add(x)` 这种复合操作）；
- 大量随机索引写/读、或需要排序/子列表等 List 高级操作。

此时 `Collections.synchronizedList` 或 `ReentrantReadWriteLock` 才是正确工具。

一句话给 C++ 同学：CopyOnWriteArrayList 精神上属于 **RCU（read-copy-update）** 家族——读者零障碍，写者独自付拷贝。
C++ 直到 C++20 才有 `std::atomic<std::shared_ptr>` 提供类似原语，Java 在 2004 年（Java 5）就随手可取。

## 三、动手验证：50 条腿压一个 AtomicLong

存为 `PoolDemo.java`，`javac PoolDemo.java && java PoolDemo`：

```java
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public class PoolDemo {
    public static void main(String[] args) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(4);
        AtomicLong counter = new AtomicLong(0);
        CountDownLatch gate = new CountDownLatch(50);

        for (int i = 0; i < 50; i++) {
            pool.submit(() -> {
                for (int j = 0; j < 1000; j++) counter.incrementAndGet();
                gate.countDown();
            });
        }
        gate.await();
        System.out.println("counter = " + counter.get());
        System.out.println("期望      = " + (50 * 1000));
        pool.shutdown();
    }
}
```

**预期输出**（两行相等）：

```
counter = 50000
期望      = 50000
```

**反面实验**：把 `AtomicLong counter` 换成普通 `long`、把 `incrementAndGet()` 换成 `counter++`，
多跑几次——结果**每次都小于 50000 且大小随机**（丢失的都是"两条腿同时读到同一个旧值"的日子）。
这一个反例能让你永久记住"为什么需要原子类/锁"。

**再补一个 CopyOnWriteArrayList 的写开销实验**：

```java
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

public class CowCost {
    public static void main(String[] args) {
        List<Integer> hot = new CopyOnWriteArrayList<>();
        for (int i = 0; i < 10; i++) hot.add(i);
        long t0 = System.nanoTime();
        for (int i = 0; i < 20_000; i++) hot.add(i);   // 每次写都全量拷贝
        System.out.println("COW add 20k 耗时(ms): " + (System.nanoTime() - t0) / 1_000_000);

        List<Integer> plain = new ArrayList<>();
        for (int i = 0; i < 10; i++) plain.add(i);
        t0 = System.nanoTime();
        for (int i = 0; i < 20_000; i++) plain.add(i);
        System.out.println("ArrayList add 20k 耗时(ms): " + (System.nanoTime() - t0) / 1_000_000);
    }
}
```

对比一跑即懂：COW 的写是"秒级/十毫秒级"，普通 ArrayList 是"毫秒级以下"。
**结论一句话**：读多写少选 CopyOnWriteArrayList；写一大就换 synchronized/普通锁。

## 四、线程池参数速查：别只会 newFixedThreadPool

生产更常见的是手工定制池（每个参数都有含义）：

```java
ThreadPoolExecutor pool = new ThreadPoolExecutor(
    4,                                  // corePoolSize：常驻腿数
    8,                                  // maximumPoolSize：高峰时最多几条
    60, TimeUnit.SECONDS,               // 多余腿闲 60 秒就辞退
    new ArrayBlockingQueue<>(100),      // 任务队列：腿全忙时任务先排队
    Executors.defaultThreadFactory(),
    new ThreadPoolExecutor.AbortPolicy()// 队列也满了怎么办：抛异常（拒绝策略）
);
```

| 参数 | 人话 | 反面教材 |
|---|---|---|
| core / max | 常驻 N 条、高峰 M 条 | max 设太大 → 内存放爆 |
| 队列 | 腿都忙时任务先排队 | 用无界队列 → 高峰积压成内存雪崩 |
| 拒绝策略 | 队列也满了怎么办 | Abort(抛错)/CallerRuns(提交者自己跑)/Discard(扔)/DiscardOldest(扔最老) |

给 C++ 同学的一句话：`ThreadPoolExecutor ≈ 自己写 thread pool + concurrent queue 的合体成品`，
Java 把它当库给全；你只需要决策 4 个数字。

### 手工 synchronized 对照（理解锁的成本）

同样的 50 线程计数，用 synchronized 会怎么写：

```java
static long counter = 0;
static final Object LOCK = new Object();
...
pool.submit(() -> {
    for (int j = 0; j < 1000; j++)
        synchronized (LOCK) { counter++; }   // 任何时刻只有一条腿能进来
    gate.countDown();
});
```

功能与 AtomicLong 版等价，但**吞吐差一个数量级**：synchronized 让 4 条腿排队，CAS 让它们「改完各自走人」。
所以经验法则再确认一遍：单值并发写优先 Atomic\*，集合优先并发类库，最后才考虑 synchronized。

## 思考题

1. demo-chat 的 `history` 只增不删，为什么不用 `List.of()` 建好就完事？其"可变性"对 SSE 补历史到底意味着什么？
2. 用 200 条 Tomcat 工作线程同时打 demo-counter 的 `/api/seckill`（其内部用 Redis 原子 DECR），和 200 条线程里各自 Java 变量 `--` 相比，为什么前者正确后者超卖？
3. `AtomicLong.incrementAndGet` 内部是 CAS 自旋。跟 synchronized 加锁比，什么时候 CAS 也会"退化"得很慢（提示：极高的并发写在同一个值上）？

## 练习题

1. 写一个 `BankDemo`：线程池 4 条腿、每个任务对 `AtomicLong balance` 做 1000 次"先 get 后 if(>0) decrementAndGet"的取钱，运行 100 任务；观察 total 取款数的正确性；再写一个"把 get 与 decrement 拆成两步"版本感悟原子性丢失时的 bug。
2. 把 demo-chat 的两个 CopyOnWriteArrayList 换成 `Collections.synchronizedList(new ArrayList<>())`，`broadcast` 遍历时抛 `ConcurrentModificationException` 的概率如何？用 for-each 遍历时另一线程 remove 试出结论。
3. 写一个 `CountDownLatch` 版"4 线程并行计算 1..10000 四段和再汇总"，保证汇总在 4 个任务全部完成后打印。

## 参考答案

**练 1**：原子版每次 50000 取款都可能成功只要 balance 够；而拆两步的版本在 balance=1 附近会出现"两个线程同时读到 balance>0、都扣"→ balance 变负。修复方式就是 Tutor 一句话：**"先 check 后取"必须放进一个原子步骤**——用 `AtomicLong.updateAndGet(v -> v > 0 ? v - 1 : v)` 一行包死，或对比后用 `compareAndSet` 循环重试。

**练 2**：大概率抛 `ConcurrentModificationException`：`synchronizedList` 只给**单个方法调用**加锁（add/remove），**迭代器遍历不加锁**，遍历期间另一线程 remove 才会炸（所以 synchronizedList 的规范要求你遍历时仍需 `synchronized (list)` 手工上锁）。CopyOnWriteArrayList 无此问题，因为迭代器拿的是快照——这正是 demo-chat 广播循环选它的第二理由。

**练 3**：

```java
ExecutorService pool = Executors.newFixedThreadPool(4);
AtomicLong total = new AtomicLong(0);
CountDownLatch gate = new CountDownLatch(4);
int chunk = 10_000 / 4;
for (int k = 0; k < 4; k++) {
    final int from = k * chunk + 1, to = (k + 1) * chunk;
    pool.submit(() -> {
        for (int i = from; i <= to; i++) total.addAndGet(i);
        gate.countDown();
    });
}
gate.await();
System.out.println("1..10000 的和 = " + total.get());   // 50005000
```

## 本节小结
- Thread/Runnable 是"谁的腿跑什么事"，ExecutorService 是"的事与腿解耦后的复用设施"；生产一律用池。
- `Atomic\*` = 无锁原子值，靠 CAS；适合计数器/进度条/座位序号这类单值场景。
- CountDownLatch 是等齐栅栏：任务的最后一步都 `countDown()`，主线程 `await()`。
- CopyOnWriteArrayList：**读多写少**场景优于 synchronized（读不加锁、遍历是快照）；写多或需要复合原子性时换 synchronizedList/锁。demo-chat 两处命中"读多写少"。
- Spring Boot 的并发是常态不是彩蛋：每个 HTTP 请求独立线程，所以 Controller 里的公共可变状态必须考虑并发。

## 下一站

A09 聊 Lombok：`@Data`/`@Getter`/`@Setter`/`@RequiredArgsConstructor` 这些编译期展开的便利与代价——
以及为什么本项目教学代码**反其道而行**，大量使用 public 字段 + record（TrainTrip.java 全部 public 字段零 getter）。
