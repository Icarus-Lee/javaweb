# S03 Bean 的生命周期与作用域

> 主线：本站你走到"人事处"。S02 讲了"入职由谁办"（容器统一办）；本课讲两件更细的事：**编制类型（singleton / prototype）**与**入职/离职手续（`@PostConstruct` / `@PreDestroy`）**，以及一条重要的失败案例——**注入时多个同类候选怎么办**。
>
> 学完本课你将能：判断一个 Bean 该用哪种作用域；看懂优雅停机日志里的销毁顺序；从容处理"两个实现争一个位置"的启动失败。

## 问题出发

"两个实现争一个位置"不是教材习题，我在写本课实录的当天就原生踩到：demo-counter 里同时挂了
`PushNotifier` 与 `EmailNotifier` 两个 `@Component`，注入点一开，容器**当场拒绝发货**——
连覆写一句自我介绍的机会都没有。加上记其后的第二条：`pkill -f` 自杀式匹配把治理自己的**worker 脚本也给杀了**。
本篇讲"编制与生死"，在第三与第五节各给一条真机上可以在 10 分钟内复现的实录。

## 本站名词卡

| 名词 | 一句话人话 |
|---|---|
| 作用域（scope） | 容器为一类 Bean 保留"几份编制" |
| singleton | 全容器一份（默认），所有请求共用 |
| prototype | 每次索取都新造一份，容器不负责销毁 |
| refresh | 容器一次完整"开张"：造 Bean → 注入 → 回调 → 就绪 |
| `@PostConstruct` | "入职体检"：注入完成后自动执行一次 |
| `@PreDestroy` | "离职交接"：容器销毁该 Bean 前自动执行一次 |
| 候选 Bean（candidate） | 能满足同一次注入的若干同类 Bean |
| `@Primary` | 多候选时的"默认当选者" |
| `@Qualifier("名字")` | 按名字精确点名注入 |

## 一、singleton 与 prototype

### singleton：默认编制

**容器里只有一份**，所有注入与所有 HTTP 请求共享同一实例。Web 系统绝大多数 Bean 必然如此——Controller、Service、Repo 都被并发请求共用。

本项目真实证据：`demo-chat` 的历史记录就是 singleton Bean 的**成员字段**：

```java
// backend/demo-chat/.../ChatController.java:17-18
private final List<Map<String, String>> history = new java.util.concurrent.CopyOnWriteArrayList<>();
private final List<SseEmitter> viewers = new CopyOnWriteArrayList<>();   // 所有在线"观众席"
```

两条 curl 发给同一服务器，哪怕隔几秒甚至几分钟，`history` 一直累积——因为自始至终用的都是**同一个 `ChatController` 实例**：

```bash
curl -s -X POST http://127.0.0.1:8082/api/chat/send -H 'Content-Type: application/json' -d '{"user":"S03","text":"第一次"}'
# {"user":"S03","text":"第一次"}
curl -s -X POST http://127.0.0.1:8082/api/chat/send -H 'Content-Type: application/json' -d '{"user":"S03","text":"第二次"}'
# {"user":"S03","text":"第二次"}
```

再注意集合类型：**`CopyOnWriteArrayList` 而非 `ArrayList`**。因为 singleton Bean 是被**多个 HTTP 线程同时调用**的，集合必须线程安全。这是"singleton 并发共存"的一个真实落点。

### prototype：每次一份新的

```java
@Component
@Scope("prototype")
public class TempTask { ... }
```

每次注入/每次手工 `getBean(TempTask.class)` 都造一个**新**实例；而且容器**不再负责销毁它**（没有对应的 `@PreDestroy` 回调链）——"临时工"自己负责收尾。

**判断口诀：组件（Controller/Service/Config）默认 singleton；"对象性/消耗性"强的对象才考虑 prototype。** 本项目 5 个服务**没有**一个 prototype Bean，因为教学场景中所有"每次不同的东西"（订单、座位号、UUID）都以**方法返回值**产生，而不是靠"每次 new 一个 Bean"。

### refresh：容器的一次开张

`SpringApplication.run()` 内部会做完整 refresh：

```text
解析配置 → 造 BeanDefinition（图纸）→ 实例化 Bean → 依赖注入
       → @PostConstruct 回调 → 容器就绪 → 对外服务
```

启动日志里两行之间发生的所有事，就是 refresh：

```text
... Starting TodoApp using Java 21 ...
... Started TodoApp in 2.8 seconds (process running for 3.1)
```

## 二、@PostConstruct / @PreDestroy：两道生命周期钩子

生命周期上一句话总结：

```
构造器（字段赋值）→ 依赖注入 → @PostConstruct → 服务（并发请求）→ 容器关闭 → @PreDestroy
```

**为什么需要 `@PostConstruct` 而不是在构造器里直接干？** 因为构造器执行时**依赖尚未注入**（Spring 先调用构造器、再赋字段）。要做"依赖就绪之后"的事（连接预热、读配置、预加缓存），必须等注入完成 → 放 `@PostConstruct`。

一个可直接抄的骨架：

```java
// 示例：AppWarmUp.java（放任意 Spring Boot 项目即可跑）
@Component
public class AppWarmUp {
    private static final Logger log = LoggerFactory.getLogger(AppWarmUp.class);

    @PostConstruct
    public void warm() {
        log.info("[warm] 启动预热：缓存加载、配置校验、健康自检……");
    }

    @PreDestroy
    public void bye() {
        log.info("[warm] 收尾：释放资源、统计数据落盘……");
    }
}
```

### 真实证据：优雅停机日志（`@PreDestroy` 的舞台）

本课程编写时真实发生过一次 demo-todo 被正常停止，日志尾部按序出现（本机实录）：

```text
2026-09-14T17:55:31.376+08:00  INFO 72509 --- [demo-todo] [ionShutdownHook] o.s.b.w.e.tomcat.GracefulShutdown : Commencing graceful shutdown. Waiting for active requests to complete
2026-09-14T17:55:31.378+08:00  INFO 72509 --- [demo-todo] [tomcat-shutdown] ... : Graceful shutdown complete
2026-09-14T17:55:31.381+08:00  INFO 72509 --- [demo-todo] [ionShutdownHook] j.LocalContainerEntityManagerFactoryBean : Closing JPA EntityManagerFactory for persistence unit 'default'
2026-09-14T17:55:31.383+08:00  INFO 72509 --- [demo-todo] ... com.zaxxer.hikari.HikariDataSource : HikariPool-1 - Shutdown initiated...
2026-09-14T17:55:31.384+08:00  INFO 72509 --- [demo-todo] ... com.zaxxer.hikari.HikariDataSource : HikariPool-1 - Shutdown completed.
```

逐行解读：

1. **Graceful shutdown 开始**：Tomcat 停止接收新流量，等在途请求盖完章；
2. **Graceful shutdown complete**：端口不再对外；此时"销毁序列"全面开工；
3. **JPA 上下文关闭**：Hibernate 会话工厂管理先退场；
4. **Hikari 连接池关**：所有数据库连接归还——这两个动作排最后，**因为还有 Bean 在销毁回调里可能仍需访问数据库**。

你的 `@PreDestroy` 钩子就插在这串销毁序列中。若某 Bean 需要最后落盘统计，这就是它的时机。

## 三、自动装配的失败案例：多候选，容器拒绝发货

### 失败本身（最小复现）

```java
public interface Greeter { String greet(); }

@Component public class GreetCN implements Greeter { public String greet() { return "你好"; } }
@Component public class GreetEN implements Greeter { public String greet() { return "hello"; } }

@Component
public class Demo {
    public Demo(Greeter g) { System.out.println(g.greet()); }   // 💥 两个候选，注入器懵了
}
```

启动即失败（真实异常关键字）：

```text
NoUniqueBeanDefinitionException: expected single matching bean but found 2: greetCN,greetEN
```

注意时机：**发生在启动期**（refresh 阶段）。这正是我们希望的方式——一旦能启动，之后每个请求都是稳定的候选，不会运行到一半"突然多了一位"。

### 修复 ① ：`@Primary`（默认当选者）

```java
@Primary @Component public class GreetEN implements Greeter { ... }
```

语义："多数场景默认用它；点名者除外。"适合**家族里明确的多数派**。

### 修复 ② ：`@Qualifier`（按名字点名）

```java
@Component
public class Demo {
    public Demo(@Qualifier("greetCN") Greeter g) { ... }
}
```

Bean 默认名 = 类名首字母小写（`GreetCN` → `greetCN`）。适合**此地例外**。

### 选择经验（一句定则）

> **默认派用 `@Primary`（少给读者认知负担）；一时一事的例外用 `@Qualifier`。**

本项目业务代码没有出现多候选冲突——因为各层通过**不同接口**清晰隔离（TrainRepo、BookingRepo、AuditLogRepo 各自独立）+ JPA Bean 名天然不同。**良好的接口划分，是让候选冲突根本无从谈起的最优办法。**

## 四、动手验证（本机实测路径）

### 1）环境基站

```bash
cd ~/Projects/javaweb && bash infra/start-all.sh
```

### 2）"启动即就绪数据"——CommandLineRunner（@PostConstruct 思想的兄弟）

takeaway 首次启动能直接登录 seed 账户 alice/rider9——种子数据在启动流程里被塞进库，不是外部 SQL 脚本：

```bash
curl -s -m 5 -X POST http://127.0.0.1:8085/api/auth/login \
     -H 'Content-Type: application/json' -d '{"username":"alice","password":"123456"}'
```

真实输出（token 截断显示）：

```json
{"token":"eyJhbGciOiJIUzM4NCJ9.eyJzdWIiO...","username":"alice"}
```

### 3）优雅停机观察（会真停掉一个服务，做完务必恢复）

```bash
cd ~/Projects/javaweb
cat logs/demo-todo.pid
kill $(cat logs/demo-todo.pid)        # SIGTERM → 触发优雅停机链
tail -6 logs/demo-todo.log
```

真实输出（顺序严格）：

```text
... GracefulShutdown        : Commencing graceful shutdown. Waiting for active requests to complete
... Graceful shutdown complete
... Closing JPA EntityManagerFactory for persistence unit 'default'
... HikariPool-1 - Shutdown initiated...
... HikariPool-1 - Shutdown completed.
```

然后**务必**恢复：

```bash
bash infra/start-all.sh
curl -s -o /dev/null -w 'HTTP=%{http_code}\n' -m 5 http://127.0.0.1:8081/api/tasks    # HTTP=200
```

### 4）prototype 思想自测（不写代码的验证）

问自己：若把 `ChatController` 干成 prototype，SSE 推送会怎样？——思考后看本章末尾参考答案的第 2 条。

## 五、工程实录：踩坑与修复（真机实测）

### 实录 1：两个候选 Bean——从容器拒答到 @Primary 复绿（含真实 diff）

**现场复现**（demo-counter 真机 2026-09-15 12:50；临时教学类已还原）：

```bash
cd ~/Projects/javaweb/backend && source ../infra/env.sh
timeout 25 java -jar demo-counter/target/demo-counter-1.0.0.jar 2>&1 | head -50
```

真实输出（摘要，WARN 那行一字未改）：

```text
WARN ... Exception encountered during context initialization - cancelling refresh attempt:
  UnsatisfiedDependencyException: Error creating bean with name 'helloGate' ...
  Unsatisfied dependency expressed through constructor parameter 0:
  No qualifying bean of type 'com.javaweb.counter.temp.Notifier' available:
  expected single matching bean but found 2: emailNotifier,pushNotifier
...
APPLICATION FAILED TO START
Description:
Parameter 0 of constructor in ...HelloGate required a single bean, but 2 were found:
	- emailNotifier: defined in URL [.../EmailNotifier.class]
	- pushNotifier: defined in URL [.../PushNotifier.class]
```

**读法**：这报错甚至把**两个候选人的定义出处（jar 内 class 路径）**都出示了——多候选时排查零盲区。
**修复（diff 前后）**：

```diff
- public class PushNotifier implements Notifier {
+ @Primary            // import org.springframework.context.annotation.Primary;
+ public class PushNotifier implements Notifier {
```

**修复后真机输出**：

```text
INFO ... com.javaweb.counter.temp.HelloGate : 注入到的 Notifier = PushNotifier
INFO ... CounterApp : Started CounterApp in 1.382 seconds (process running for 1.645)
```

修复后 `curl :8083/api/counter` → `{"n":6}`（服务直通复绿）。

### 实录 2：自杀式 pkill——一脚踩进作者同款坑

**问题**：起服务/重生服务的习惯写法 `pkill -f demo-counter`——**把自己的启动外壳一并杀了**：
`pkill -f` 匹配的是**命令行整行**，我的 bash 测试命令行本身含 "demo-counter"（jar 路径），
于是 pkill 把当前 shell 进程一起干掉，`timeout 25 java …` 随即挂了，log 也半途而废。
（我复现"启动失败"时亲眼见到：一条 `tail` 结果里混杂着旧日的 ConnectionWatchdog 与本次的
`ClassNotFoundException: ch.qos.logback...`——**半途被杀的进程日志就是这种"叠影"**，先洗牌再读。）

**修复**：进程匹配**永远别用裸词**——把首字母换成 `[)]` 变体（拼接变量）或用 pid 文件：

```bash
pkill -f "[d]emo-counter"      # 拼接变量：命令行里没有"demo-counter"字面量，不会再命中自己
# 或
kill $(cat logs/demo-counter.pid)
```

**一句收束**：`pkill -f` 是正则匹配整条命令行，而**我自己就在那条命令行里**——和 SQL 注入同一个心法：
凡"匹配他人"的代码，先想想会不会匹配到自己。

## 思考题
1. 为什么 demo-todo 的 `TaskRepo` Bean **没有**写 `@PreDestroy` 关数据库连接，也从不泄漏？连接的生命周期由谁在管？（提示：HikariDataSource 是谁的 Bean，被谁的销毁序列关掉）
2. 若把 `ChatController` 改为 prototype，浏览器 SSE 还能收到吗？（剧透：`viewers` 与 `broadcast()` 是它的成员——多个直播间的观众席无法互通。）
3. `@PostConstruct` 与"在构造器里直接 new 一个 list 字段初始化"的差别在哪？给出一个"只有前者能干、后者没法干"的场景。

## 练习题

1. 给 demo-counter 增加**启动预置秒杀库存**：`CommandLineRunner` 或 `@PostConstruct`（选一种）向 Redis 写 `demo:seckill:stock=10`；重启后先 `POST /api/seckill` 验证能扣。
2. 复现并修复候选冲突：在 demo-counter 写两个实现同一接口 `Notifier` 的 `@Component`（`MailNotifier` / `SmsNotifier`），另有 `Demo` 注入 `Notifier` 观察启动失败；分别用 `@Primary` 版与 `@Qualifier` 版改至可启动（两次都是真实编译运行验证）。

### 参考答案

1. 推荐版（`CommandLineRunner`，比 `@PostConstruct` 更晚、更安全——网络必就绪）：

```java
@Component
public class SeckillWarmUp implements CommandLineRunner {
    private final StringRedisTemplate redis;
    public SeckillWarmUp(StringRedisTemplate redis) { this.redis = redis; }

    @Override
    public void run(String... args) {
        redis.opsForValue().setIfAbsent("demo:seckill:stock", "10");
        System.out.println("[warm-up] seckill stock=10 ready");
    }
}
```

重启后验证：

```bash
curl -s -X POST http://127.0.0.1:8083/api/seckill
# {"ok":true,"left":9}
```

2. 冲突启动失败意象（真实报错关键字）：

```text
NoUniqueBeanDefinitionException: expected single matching bean but found 2: mailNotifier,smsNotifier
```

修复版 1（`@Primary`）：

```java
@Primary @Component public class MailNotifier implements Notifier { ... }
```

修复版 2（`@Qualifier`）：

```java
public Demo(@Qualifier("smsNotifier") Notifier n) { ... }
```

两个版本**举办的舞台是不是同一个环境**，你想清楚——前者的选择是"全局默认"，后者是"此一注入点**特判**"。

## 本节小结
- 默认编制 singleton；prototype 是"每次一份、容器不管收尸"的特殊编制。
- 生命周期链：**构造器 → 注入 → `@PostConstruct` → 服务 → `@PreDestroy`**；优雅停机日志按序可验。
- 多候选 = 启动期失败；`@Primary` 提供默认、`@Qualifier` 处理例外，规约是"少给读者认知负担"优先。
- 下~系列课程起，跟踪输入输出的机制：HTTP 到方法的旅程。

**下一站：S04 WebMVC：控制器、路由与参数。**
