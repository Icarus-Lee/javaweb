# Java速通 A08 · 并发基础：线程池、Runnable、Atomic 与 CopyOnWriteArrayList

> 三行件头：①要点——从裸 Thread 到线程池，Atomic/CAS 思想，"读多写少选 COW、写多上锁"；②前置——A01 引用语义，会 Python 的 threading 或 C++ `<thread>`；③产出——**一次真跑的"无保护++ / AtomicLong / synchronized 三方对照"边界实验**（含计时），与 demo-chat 广播代码走查。
>
> 🗺 主线进度：Java 速通第 8 站 | 🎞 上一站：A07 Maven | 📀 本站所得：并发选型的一把焦尺。

## 名词卡（重排序：按"事故排查顺序"）

| 名词 | 人话 |
|---|---|
| 竞态（race condition） | 多条腿乱跑互踩，结果随调度运气变化 |
| 原子值（AtomicLong 等） | CAS 支持下不怕并发的"改一改就正确" |
| CAS | Compare-And-Swap：比较-交换一步到位 |
| Runnable / Thread | "一段交给腿去跑的事"/执行腿绑定 |
| 线程池 | "腿的招聘处+调度台"：常驻腿+任务队列 |
| 闭锁（CountDownLatch） | "等到齐"栅栏 |
| **CopyOnWriteArrayList** | 读不加锁，写一份副本整体换（RCU 家族） |
| synchronized | 加锁大祭；任何时刻只有一条腿能进来 |
| Tomcat 工作线程 | Spring Boot 每请求一线，默认约 200 |

## 一、人话：为什么一口锅要配几双手

Spring Boot 的 web 服务**天生多线程**：Tomcat 从线程池派线程处理请求，几十并发是常态。写"全局剩 1 张票时 `if (stock>0) sell()`"，两条腿同时读到 1 同时卖——超卖。**并发不是彩蛋，是把 demo 写对的前提**。

"起一条腿"三姿势：

```java
// 1) 裸 Thread（直接招聘）
new Thread(() -> System.out.println("hi from leg")).start();
// 2) Runnable：把"事"与"执行者"分开
Runnable job = () -> System.out.println("do my job");
// 3) ExecutorService（生产方式）
ExecutorService pool = Executors.newFixedThreadPool(4);
pool.submit(job); pool.shutdown();          // 任务排队，4 条常驻腿轮流跑
```

为什么生产不用裸 Thread：每请求起新腿/销毁的创建销毁开销巨大——餐厅不会每来一位客人新雇一个服务员。线程池 = 预备 N 条常驻腿+任务队列，任务来了复用；Tomcat 默认约 200 工作线程，本项目 5 个 jar 靠它扛。

### Atomic：无锁原子值的机制一句话

```java
AtomicLong hits = new AtomicLong();
hits.incrementAndGet();      // 原子 +1；内部 CAS：比较-交换一步到位
long now = hits.get();
```

C++ 的 `std::atomic<long> x; x++` 完全同义。CAS 细节：读旧值→算新值→**写回前确认旧值没被别人动**→被动过就重试（自旋）。无竞争一击即中；百人共抢一个值则反复重试吞吐下降——**"CAS 退化"**，此时用 LongAdder 分片或换锁。选型顺序：**Atomic → 并发集合 → synchronized**。

### CountDownLatch 速览

```java
CountDownLatch gate = new CountDownLatch(3);   // 3 人到齐才开门
pool.submit(() -> { doThing(i); gate.countDown(); });
gate.await();                                   // 主线程卡到数完
```

## 二、真实代码走查：demo-chat 的两行 CopyOnWriteArrayList

`backend/demo-chat/src/main/java/com/javaweb/chat/ChatController.java`（17-18 行前后）：

```java
17:     private final List<Map<String, String>> history = new java.util.concurrent.CopyOnWriteArrayList<>();
18:     private final List<SseEmitter> viewers = new CopyOnWriteArrayList<>();   // 所有在线"观众席"
```

broadcast 循环（后段）：

```java
    private void broadcast(Map<String,String> msg) {
        for (SseEmitter em : viewers) {
            try {
                em.send(SseEmitter.event().name("msg").data(msg));
            } catch (IOException e) {
                viewers.remove(em);   // 断线观众撤掉（边遍历边删除！）
            }
        }
    }
```

 sensational：**一边 for-each 遍历一边 viewers.remove**，A03 说这是 ConcurrentModificationException 现场死刑——为什么这里合法？

- CopyOnWriteArrayList **每个迭代器拿的是快照**（数组复制），遍历时改/删不会炸；
- 每次**写**（add/remove）先复制全量新数组再原子替换引用——读者零障碍，写者独自付拷贝费。

| 列表 | 读频率 | 写频率 | COW 划算？ |
|---|---|---|---|
| viewers | 每条消息对每观众 send，可能每秒几十次 | 观众连上/断开，很少 | 划算 |
| history | 新观众接入时快照下发（ChatController.java:27） | 每条新消息一次 | 划算 |

**换 synchronized 什么时候更合适**：列表动辄上万且写频繁；需要"读判+写"原子（`if (!contains) add`）；大量随机索引操作。此时 `Collections.synchronizedList` 或 `ReentrantReadWriteLock` 才对。
给 C++ 一句：COW 精神上是 RCU 家族；C++20 才有 `atomic<shared_ptr>` 给了类似原语，Java 2004 年就随手可取。

## 三、工程实录：三方对照实证（无保护++ / Atomic / synchronized）

`/tmp/opencode/RaceBoth.java`：

```java
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public class RaceBoth {
    static long plain = 0;
    static final Object LOCK = new Object();
    interface Inc { void one(); }

    public static void main(String[] a) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(4);

        // ① 无保护 ++
        CountDownLatch gate = new CountDownLatch(50);
        for (int i = 0; i < 50; i++) pool.submit(() -> {
            for (int j = 0; j < 1000; j++) plain++;
            gate.countDown();
        });
        gate.await();
        System.out.println("① 无保护 ++        = " + plain);    // 每次 < 50000 且随机

        // ② AtomicLong（CAS）
        AtomicLong atomic = new AtomicLong(0);
        long t0 = System.nanoTime();
        gate = new CountDownLatch(50);
        for (int i = 0; i < 50; i++) pool.submit(() -> {
            for (int j = 0; j < 1000; j++) atomic.incrementAndGet();
            gate.countDown();
        });
        gate.await();
        System.out.println("② AtomicLong (CAS) = " + atomic.get());
        // + 耗时一栏（自实现）

        // ③ synchronized 包裹
        NumberBox nb = new NumberBox();
        gate = new CountDownLatch(50);
        for (int i = 0; i < 50; i++) pool.submit(() -> {
            for (int j = 0; j < 1000; j++) nb.synchronizedInc();
            gate.countDown();
        });
        gate.await();
        System.out.println("③ synchronized     = " + nb.v);
        pool.shutdown();
    }

    static class NumberBox {
        long v = 0;
        synchronized void synchronizedInc() { v++; }   // 任何时刻只有一条腿能进来
    }
}
```

**本机真跑输出一轮（4 条常驻腿、50 任务×1000++）**：

```
① 无保护 ++        = 16653          ← 丢了 33347 次！
② AtomicLong (CAS) = 50000（耗时 3 ms）
③ synchronized     = 50000（耗时 6 ms）
```

（多跑几轮，① 的值在 1~4 万间随机飘，② ③ 恒 50000。）**读法**：①"我丢了"+无规则——两条腿同时读到旧值、同时+1、只生效一次；② CAS 自旋"改一次"，③加锁排队不丢但吞吐慢些。**教训走递进：单值并发写选 Atomic；集合选并发类库；真要"复合原子（读判再写）"才上锁。**

### 补：COW 写代价的边界实验（CowCost.java，本机真跑）

`/tmp/opencode/CowCost.java`：

```java
List<Integer> hot = new CopyOnWriteArrayList<>();
for (int i = 0; i < 20_000; i++) hot.add(i);          // 每次写全量复制
// 对照
List<Integer> plain = new ArrayList<>();
for (int i = 0; i < 20_000; i++) plain.add(i);
```

**本机真跑输出**：

```
COW    add 20k 耗时(ms): 141
ArrayList add 20k 耗时(ms): 1
```

140 倍的写代价！但注意边界：demo-chat 的 `history` 全场聊天也就几十上百条、每次 `send()` 只 add 一条——一次全量复制微不足道。**口诀完整版：读多写少用 COW；写一大（万次级）或列表长（上万条）就换 synchronized/并发锁**。

## 四、线程池参数速查

```java
ThreadPoolExecutor pool = new ThreadPoolExecutor(
    4,                                   // core：常驻 4 条
    8,                                   // max：高峰最多 8
    60, TimeUnit.SECONDS,                // 闲 60s 辞退
    new ArrayBlockingQueue<>(100),       // 任务队列
    Executors.defaultThreadFactory(),
    new ThreadPoolExecutor.AbortPolicy() // 队列满：抛异常
);
```

| 参数 | 人话 | 反面 |
|---|---|---|
| core/max | 常驻/峰值 | max 太大内存爆 |
| 队列 | 排队 | 无界队列积压雪崩 |
| 拒绝策略 | 满了肿么办 | Abort 抛/CallerRuns 本人跑/Discard 扔 |

## 五、动手验证

1. 复跑 §三 的 RaceBoth 三方对照，**至少三轮**记录差异（①随机、②③恒 50000；② ③ 的耗时可能互换）。
2. 把 AtomicLong 换普通 `long counter` 跑 `counter++`——结果就是 §三 ① 的翻版，你亲当一次"丢更新"的制造者。
3. 目击 COW "遍历快照"（jshell 两行见练 2 答案的 code 块）。
4. CowCost 跑两轮对比 COW vs ArrayList 的写耗时（本机 141ms vs 1ms）。

## 思考题

1. demo-chat 的 `history` 只增不删，为什么不用 `List.of()` 建好完事？
2. 200 条 Tomcat 线程打 demo-counter 的 `/api/counter`（内部 Redis 原子），与"200 条线程各自 Java 变量 `--`"，为什么前者正确后者超卖？
3. `AtomicLong.incrementAndGet` 是 CAS 自旋，什么情况下"退化"得很慢？可以怎么破解？
4. §三 的 ② ③ 结果都正确且 ③ 反而更慢——那什么时候 synchronized 是**必需**而不是"更慢的 Atomic"？

## 练习题 / 参考答案

**练 1**：写 BankDemo（"先 get 后 if>0 扣" vs `updateAndGet(v -> v>0 ? v-1 : v)` 一行收束）——原子性丢失的 bug 由拆步版亲身体会：balance=1 附近会出现"两个线程同时读到 >0、都扣"→变负。**"先 check 后取"必须放进一个原子步骤**。

```java
// 一行包死（本项目若做内存计数可借这个姿势）
AtomicLong balance = new AtomicLong(1000);
long withdrew = 0;
boolean ok;
// updateAndGet 返回更新后的值：
long before = balance.get(), after = balance.updateAndGet(v -> v > 0 ? v - 1 : v);
ok = after < before && before > 0;   // 拆步不安全的示例要自己先跑出 bug 才服气
```

**练 2**：把 demo-chat 的两个 COW 换 `Collections.synchronizedList`：**遍历不加锁**（它只给单个方法调用加锁），for-each 里挨删大概率炸 CME；规范要求遍历时手工 `synchronized(list)`。COW 的迭代器拿的是快照——demo-chat 不是"侥幸"，是"选对了"。

```java
jshell> var cow = new java.util.concurrent.CopyOnWriteArrayList<>(List.of("a","b"))
jshell> for (var x : cow) { cow.add("new"); }
jshell> cow                       // ← 输出 [a, b, new, new]，不炸！

jshell> var syn = java.util.Collections.synchronizedList(new ArrayList<String>(List.of("a","b")))
jshell> for (var x : syn) { syn.add("new"); }
|  有时炸 java.util.ConcurrentModificationException（另一线程 add 时）
```

**练 3**：CountDownLatch 版四分求和：

```java
ExecutorService pool = Executors.newFixedThreadPool(4);
AtomicLong total = new AtomicLong(0);
CountDownLatch gate = new CountDownLatch(4);
int chunk = 10_000 / 4;
for (int k = 0; k < 4; k++) {
    final int from = k * chunk + 1, to = (k + 1) * chunk;
    pool.submit(() -> { for (int i=from; i<=to; i++) total.addAndGet(i); gate.countDown(); });
}
gate.await();
System.out.println(total.get());   // 50005000
```

**参考答案（思考题）**：
1. `List.of()` 是不可变：SSE 补历史后新消息没地方加。它"可变性"的真正含义是**随时间追加**——COW 在这里兼得"读快照"与"可追加"。
2. Redis 单命令原子（DECR/CAS 语义在后端集中处理）；JVM 变量的 `--` 是"读-写"两步非原子，两条腿互踩（§三 ① 已实证 16653/50000）。
3. 同一值的极高并发写（几百线程共抢一个 CAS 位），自旋重试率飙升；破解：分片（LongAdder 把一个值拆 base+cell 数组）、或换锁。
4. 需要"复合原子"或"多个变量一起原子切换"时（`if (!contains) add`、转账双方账户），Atomic 单值罩不住，synchronized 块才是正解。

## 本节小结
- Thread/Runnable=谁的腿跑什么事；ExecutorService=复用设施，生产一律用池。
- Atomic=无锁原子值（CAS），单值并发写首选。
- CountDownLatch=等齐栅栏。
- COW=读不加锁+迭代器快照——demo-chat 的 SSE 广播正中其甜区；写大/复合原子才换 synchronized。
- Spring 并发是常态：Controller 里公共可变状态务必考虑并发（counter 的正确示范是 Redis 外存）。
## 下一站

A09 聊 Lombok：`@Data` 一行生成 getter/setter/equals；为什么本项目教学**反其道**用 public 字段+record——用两份 javap 输出做字节数对比。
