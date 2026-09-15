# S02 IoC 与 DI：不再手动 new

> 主线：本站你走到大厅的"服务台"。旧世界你自己动刀（`new` 出所需一切）；新世界你在柜台**声明需要什么，对象被递到你手上**——权力从"我"倒给"容器"，这就是 IoC；"递"这个动作，就是 DI。
>
> 学完本课你将能：说出手动 new 的四个具体毛病；看懂本项目所有真实代码里的"构造器注入"样式；回答"三个注解该在什么层用什么"。

## 问题出发

一次真机实录（我写本课时真踩的）：为了教学在 demo-counter 里新写了"推送器"类 `PushNotifier`，
`HelloGate` 想要它——但**忘了贴 @Component**。启动 3 秒内 Spring 直接拒答：
`required a bean of type '...Notifier' that could not be found`。
这不是运行时才炸的坑（那时业务已上线），而是**启动期 fail-fast**——IoC 容器最值钱的一次自我帮助。
本篇讲"对象谁来造"的权力转移，同时把这些"容器拒绝发货"的真实案例当反面教材。

## 本站名词卡

| 名词 | 一句话人话 |
|---|---|
| IoC（Inversion of Control，控制反转） | "对象由谁创建、创建时依赖谁"的决定权交给容器 |
| DI（Dependency Injection，依赖注入） | 容器把依赖塞进你的构造器/字段——IoC 的**实现手段** |
| Bean | 被容器登记、创建、装配、销毁的对象 |
| `@Component` | 通用的"请托管我"标记 |
| `@Service` | 业务层的语义化托管标记 |
| `@Repository` | 数据访问层的语义化托管标记 |
| 构造器注入 | 通过构造器参数收依赖（本项目统一风格） |
| `ApplicationContext` | 装着所有 Bean 的仓库 + 总调度 |

> 相关背景阅读：如果对 `@注解` 语法本身陌生（它是什么、与 C++ 属性语法的对应），先看 Java速通 A06《注解与反射：为什么 Spring 满是 @ 符号》。

## 一、手动 new 的四个毛病（一个反例讲全）

假设没有 Spring，demo-todo 可能会写成交接状：

```java
// ❌ 反例（反模式全集中营）
public class TaskController {
    private TaskRepo repo = new TaskRepo();                  // ① 内部硬造
    public TaskRepo getRepo() { return repo; }               // ② 想测试也得绕真实库
}

public class TaskRepo {
    private Connection conn = H2Connector.open("./data/demo-todo");  // ③ 每个实例各开一条连接
    ...
}
```

四病逐条：

### 病 1：测试难

想测 `TaskController.list()`？它内部焊死了真实 H2 文件库。测试跑在 CI 上没数据、数据被污染、或想换成假数据源都办不到——必须改源码。**依赖写死在字段里 = 测试替身（mock/fake）永无插桩位。**

### 病 2：复用难

`TaskRepo` 无状态，全项目一份就够；但每次 `new` 都造新的，白费对象与连接。谁该共享、谁该独占，散落各处的 `new` 无从管理。

### 病 3：替换难

今天要"把 H2 换成 MySQL"，明天要"给 repo 加一层缓存"。所有 `new TaskRepo()` 的文件都得改。**依赖决定权分散在调用方 = 一改就全改。**

### 病 4：单例与生命周期无人管

连接池什么时候建、什么时候优雅关闭？两个线程同时用一个 repo 谁保证安全？这些是**全局资源**问题，应该有一个统一的地方来"管编制、管生死"——这就是容器。

IoC 的方向对调一言：

> **别在自己类里 `new` 依赖；把自己交进容器，把需求写在构造器签名上，让容器喂。**

## 二、构造器注入：本项目的真实主力样式

### demo-todo（最短样本）

```java
// backend/demo-todo/.../controller/TaskController.java:11-19
@RestController
@RequestMapping("/api/tasks")
public class TaskController {
    private final TaskRepo repo;

    // 构造器注入（Spring IoC 的直观体验）：框架把 TaskRepo 递进来。 ← 源码原注释
    public TaskController(TaskRepo repo) {
        this.repo = repo;
    }
```

一个 Controller 就**一个依赖**，行数极少但四件事齐全：

1. `private final`——字段构造完定死，不能被替换、不能是 null；
2. 构造器是**唯一入口**——没有任何第二路径绕开容器；
3. 容器启动时：发现 `TaskController` 要 `TaskRepo` → 容器内早已备好 → 递入；
4. 从此 `list()` 里 `repo.findAll()` 只写字面如常。真实运行一下：

```bash
curl -s http://127.0.0.1:8081/api/tasks
```

真实输出：

```json
[]
```

（若你正在使用接口早就有数据，那也是列表——不改文字。）

### train（多依赖样本）

```java
// backend/train/.../service/BookingService.java:18-31
@Service
public class BookingService {
    private final TrainRepo trips;
    private final BookingRepo bookings;
    private final StringRedisTemplate redis;
    private final OrderEventProducer producer;

    public BookingService(TrainRepo trips, BookingRepo bookings,
                          StringRedisTemplate redis, OrderEventProducer producer) {
        this.trips = trips;
        this.bookings = bookings;
        this.redis = redis;
        this.producer = producer;
    }
}
```

一口气四依赖，依赖树拔出来看：

```text
BookingService (@Service)
├── TrainRepo            (JPA 动态代理，由 Spring Data 收册)
├── BookingRepo          (JPA 动态代理)
├── StringRedisTemplate  (Redis 自动装配制造的单例)
└── OrderEventProducer   (@Component 消息发送器)
    └── KafkaTemplate    (Kafka 自动装配制造的单例)
```

**递进关系实拍**：被注入的依赖自己也是被托管的 Bean。容器从"叶子"（KafkaTemplate）起一层层造，最后到"根"（BookingService）——这是三行的机制：依赖注入天然可以级联。

### takeaway（业务派）

```java
// backend/takeaway/.../service/OrderService.java:17-34（节选）
@Service
public class OrderService {
    private final OrderRepo orders;
    private final DishRepo dishes;
    private final StringRedisTemplate redis;
    private final OrderEventProducer producer;
    ...
}
```

train/takeaway 的 `@Service` 两个类结构几乎全等——这就是本系列的**风格规约**一目了然的好处。

## 三、@Component / @Service / @Repository 语义规范

三者**底层机制**完全一样（都注册成 Bean），差别只在**语义**——给读代码的人明确"这属哪一层":

| 注解 | 全名中的"我该放哪" | 本项目实例（文件:行） |
|---|---|---|
| `@Component` | 通用件/横切件/不好归类 | `train/security/AuthInterceptor.java:9`、`train/messaging/OrderEventProducer.java:7`、`train/messaging/AuditConsumer.java:14` |
| `@Service` | 业务规则/事务/编排 | `BookingService.java:18`、`OrderService.java:17` |
| `@Repository` | 数据访问层 | 本项目不手写——JPA 接口由 Spring Data 自动推断（见下方说明） |

**为什么 `TaskRepo` 一行接口没写注解也能被注入？** 因为它继承了 `JpaRepository`，Spring Data 的扫描对象是"JPA 接口"，扫到自动造动态代理 Bean。所以这里的 **"接口继承即入册"** 是约定俗成，源码中看不到注解：

```java
// backend/demo-todo/.../repo/TaskRepo.java（全文 8 行）
public interface TaskRepo extends JpaRepository<Task, Long> {
}
```

### 规约落地：分组使用的"出厂规则"

- **业务 class（含状态机/事务）→ `@Service`**；
- **横切设施（拦截器、消息收发、初始化器）→ `@Component`**；
- **数据接口 → 不打注解，交给 Spring Data**；
- **Controller → @RestController（自带 @Component）**。

在本项目里翻一遍都可对上（如 takeaway 下单这条链）：

```text
OrderController(@RestController) → OrderService(@Service) → OrderRepo / DishRepo(JPA 接口)
                                                            → DispatchConsumer(@Component，消费 Kafka)
```

每层注解都长在它该在的位置。**你以后写新代码照此办理**——注解的意义一半表达给编译器，一半表达给读者。

## 四、动手验证：容器真"喂"了证据

### 1）确认容器在跑（环境校验）

```bash
cd ~/Projects/javaweb && bash infra/start-all.sh   # 幂等：已在线的只会报"已在线"
```

### 2）@RequestBody 与注入对象协作的证据

```bash
curl -s -X POST http://127.0.0.1:8082/api/chat/send \
     -H 'Content-Type: application/json' -d '{"user":"S02读者","text":"你好"}'
```

真实输出：

```json
{"user":"S02读者","text":"你好"}
```

这条 MQ 是 W04 样本，拿到"返回即用户发的 echo"还需要多少底层？答案енно：`ChatController`、`@RequestBody`、`@RestController` 三个**本轮已讲或即将讲**的机关各自在岗位。

### 3）单例分享内存的字段——注入生命的证据

`ChatController` 的 `history` 是**成员变量**，但它是 singleton Bean（S03 详谈），因此所有请求共享这一个 List。真实实操：

```bash
curl -s -X POST http://127.0.0.1:8082/api/chat/send -H 'Content-Type: application/json' -d '{"user":"A","text":"msg1"}'
curl -s -X POST http://127.0.0.1:8082/api/chat/send -H 'Content-Type: application/json' -d '{"user":"B","text":"msg2"}'
# 两条都成功：因为 controller 是同一个对象，history 一直在累积。
```

### 4）从 demo-counter 看共享 Bean 里的底层连接

```bash
curl -s http://127.0.0.1:8083/api/counter
```

真实输出（本机计数会随历史调用递增）：

```json
{"n":2}
```

这次背后是 `StringRedisTemplate` Bean 以**一个**连接（连接池之一）承载所有请求——若是每次 `new`，每个请求都要新建连接再舍弃，Redis 会毫无意义地多端服务。**"Bean 的单例 + 注入的复用"就是这行数字能一直增长的成本解释。**

## 五、工程实录：踩坑与修复（真机实测）

### 实录：忘了 @Component——启动期 fail-fast 与一行修复

**问题**：`@Component PushNotifier` 的目标类**漏贴托管标记**，`HelloGate` 在构造器参数里要 `Notifier`：

```java
// 修复前（教学复现的临时类，已还原）：PushNotifier 上没有 @Component
@Component
public class HelloGate {
    public HelloGate(Notifier n) { log.info("注入到的 Notifier = {}", n.getClass().getSimpleName()); }
}
```

**现场复现**（demo-counter 真机 2026-09-15 12:49）：

```bash
cd ~/Projects/javaweb && source infra/env.sh
timeout 25 java -jar backend/demo-counter/target/demo-counter-1.0.0.jar 2>&1 | tail -12
```

真实输出（Spring Boot 3.5 原样话术）：

```text
***************************
APPLICATION FAILED TO START
***************************

Description:

Parameter 0 of constructor in com.javaweb.counter.temp.HelloGate required a bean of type 'com.javaweb.counter.temp.Notifier' that could not be found.


Action:

Consider defining a bean of type 'com.javaweb.counter.temp.Notifier' in your configuration.
```

**逐行解读（一行一个知识）**：
1. **"Parameter 0 of constructor"**——容器精确指到构造器的第 0 个参数：它算到了"你在**构造器注入**"这一步；
2. **"required a bean ... that could not be found"**：所有被扫描进来的 Bean 都试了，没有类型吻合者；
3. **Action 给了两个修法的提示**——"贴 `@Component`"或"在配置类里写 `@Bean` 生产者"（本项目的 KafkaTemplate / StringRedisTemplate 就是自动装配走 @Bean 路线受害者，S02 第二节依赖树看一眼便知）。

**diff 修复**（一字）：

```diff
 public class PushNotifier implements Notifier {
-    public String send(String msg) { return "push:" + msg; }
+    public String send(String msg) { return "push:" + msg; }
 }
+@Component         ← 补上：让容器"看见"这个类
```

实际是把 `@Component` 写在类上（import org.springframework.stereotype.Component），重编重起——
真机实测：`Started CounterApp in 1.382 seconds`，`curl /api/counter` → `{"n":6}`（启动绿 纯直通）。

**教学点**：这个失败发生在 **refresh 阶段**——所有 Bean 装配完才起来 Web 服务；没有"起了一半"的半死不活进程。
跟"构造器注入对字段注入的优势"呼应：**启动即全面装配，没有"忘注入"的运行时 null**——
像上节说的那样 `private final` 使 null 不可能诞生。

## 思考题
1. 构造器注入 vs 字段上贴 `@Autowired` 注入各有什么优缺点？至少答出：`final`、脱离 Spring 的可测性、循环依赖暴露时机三点。
2. 假如 `BookingService` 的四个依赖减到一个都不要，业务还能不依赖容器吗？精神上它还有存在的价值吗？（纯函数/无状态服务的意义）
3. `TaskRepo` 是接口却没 `@Repository`，那 `@Repository` 注解必须知道的场景什么时候才必须写？（提示：当你**不继承** `JpaRepository` 而手写 DAO 时）

## 练习题

1. 给 demo-todo 新增 `TaskStatsService`（@Service，依赖 `TaskRepo`），提供 `long total()`；在 `TaskController` 增加一个**构造器参数**并新增 `GET /api/tasks/stats`，返回 `{"total":N}`。启动后用 curl 验证。
2. 反例实验：把 `TaskController` 改成字段注入（不要构造器）`@Autowired private TaskRepo repo;`，然后把字段置成非 `final`。思考这**三处损失**（不可变性、依赖可测性、依赖显性）。

### 参考答案

1. 关键代码：

```java
// 新增 com/javaweb/todo/service/TaskStatsService.java
@Service
public class TaskStatsService {
    private final TaskRepo repo;
    public TaskStatsService(TaskRepo repo) { this.repo = repo; }
    public long total() { return repo.count(); }
}

// TaskController 改两处：构造器 + 一个路由方法
private final TaskStatsService stats;

public TaskController(TaskRepo repo, TaskStatsService stats) {
    this.repo = repo;
    this.stats = stats;
}

@GetMapping("/stats")
public Map<String, Long> stats() { return Map.of("total", stats.total()); }
```

验证：

```bash
curl -s http://127.0.0.1:8081/api/tasks/stats           # 添加代码后EXPECTED输出
# {"total":2}
```

2. 三处损失逐条：
- **不可变性没了**：非 final 字段可被任何代码中途改写；
- **可测性差**：脱离 Spring 容器的纯单元测试没法直接构造一个"带依赖的类"（字段注入只认容器或反射）；
- **依赖隐性化**：构造器签名是"我对外的依赖清单"，字段注入则把这清单藏进类内部——读者扫接口看不出它靠什么活。

## 本节小结
- 手动 new 的四个病：**测试难、复用难、替换难、单例难**。
- IoC 是权位，DI 是手段；**构造器注入 + final 字段**是本项目统一写法，一眼看出依赖树。
- 注解体系按层分工：`@Service` 业务、`@Component` 横切、`@Repository` 数据（JPA 接口自动生效）、`@RestController` 接口。
- 对象交由容器养着——养多久？几份？几时结算？这就是后续三课：**Bean 的生命周期与作用域**（S03）。

**下一站：S03 Bean 的生命周期与作用域。**
