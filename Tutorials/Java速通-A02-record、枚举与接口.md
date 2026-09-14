# Java速通-A02 · record、枚举与接口

> C++ 里你会为"一个只装数据的 struct"写构造函数、比较运算符、哈希；Java 16 之前更惨（还要写 getter/equals/hashCode）。record 一行全解决。这一站还讲 enum 和 interface——以及一个关键边界：**为什么 JPA 实体不能是 record**。

---

## 🎬 开篇三件套

### 1️⃣ 本站你走在大厅哪一格

票务大厅的窗口上贴着几张"单据模板"：下单要填"车次号"（BookReq）、注册要填"用户名+密码"（RegisterReq）——这些**只读数据壳**就是 record。而订单状态 `UNPAID/PAID/CANCELLED` 这种"有限几种取值"就是 enum 的地盘。本站你在大厅窗口前，学做单据模板。

### 2️⃣ 本站任务单

1. 会写 record，理解它自动生成的构件（构造器/访问器/equals/hashCode/toString）；
2. 会用 enum，知道它比 C++ enum class 强在哪；
3. 会定义接口、理解 default 方法；
4. 想清楚 JPA 实体（class Task）与 record 的边界：谁 mutable 谁 immutable；
5. 动手：写一个 `BookReq`，在 demo01 场景里对照使用。

### 3️⃣ 开工前自查

- [ ] A01 的类/构造器/引用语义已消化
- [ ] 知道 C++ 的 struct 与 enum class
- [ ] jshell 可用

---

## 🗂 本站名词卡

| 名词 | 人话 | C++ 对照 |
|---|---|---|
| **record** | 不可变数据壳：一行声明自动生成构造/访问器/equals/hashCode/toString | 带全成员初始化的 struct + 自动生成的 ==/hash |
| **不可变（immutable）** | 创建后字段不许改 | const 成员的 struct |
| **访问器** | `name()` 而不是 `getName()`（record 风格） | getter |
| **enum** | 类型安全的"有限取值集合"，可带字段和方法 | enum class 的加强版 |
| **interface** | 方法签名清单，谁 implement 谁兑现 | 抽象基类（但无字段） |
| **default 方法** | 接口里带默认实现的方法 | C++ 抽象类里的非纯虚函数 |
| **@Override** | 标注"我在重写父类/接口方法"，拼错编译器报警 | override 关键字 |

---

## 🧠 概念人话

### record：一行顶十行

```java
public record BookReq(Long tripId) {}
```

编译器自动给你生成：

1. **不可变字段** `private final Long tripId;`
2. **全参构造器** `new BookReq(1L)`
3. **访问器** `req.tripId()`（不是 `getTripId()`）
4. **equals + hashCode**（按所有字段）
5. **toString**（`BookReq[tripId=1]`）

C++ 等价物是"struct + 编译器生成的比较"，但 C++ struct 默认**可变**；record 天生 final。当你要"在方法间搬运一坨数据且不想被改"时，record 是首选。

### enum：会做事的常量

```java
enum OrderStatus { UNPAID, PAID, CANCELLED }
```

比 C++ enum class 强的地方：

- 自带 `values()`、`valueOf("PAID")`、`name()`、`ordinal()`；
- 可以带字段和方法（每个枚举值一个实例）；
- 是真正的类：能进集合、能 switch（Java 21 的 switch 还能带返回值）；
- 类型安全：`OrderStatus s = "PAID"` 编译不过，C++ 的 int 枚举混用问题消失。

### 接口与 default 方法

```java
interface Closeable2 {
    void close();                          // 抽象方法：实现者必须兑现
    default void closeQuietly() {          // default：白送一个默认实现
        try { close(); } catch (Exception ignored) {}
    }
}
```

C++ 里这叫抽象基类的非虚函数。Java 类可以 implements 多个接口（弥补单继承），接口之间还能互相 default 冲突仲裁。**A04 会看到**：try-with-resources 正是靠 `AutoCloseable` 这个单方法接口工作的。

---

## 🔍 真实代码走查

### record 在本项目遍地开花

**BookingController.java:27-28**——下单请求壳：

```java
27:     public record BookReq(Long tripId) {}
28:     public record OrderOnlyReq(String orderNo) {}
```

**AuthController.java:28-29**——注册/登录请求壳（还带校验注解）：

```java
28:     public record RegisterReq(@NotBlank String username, @NotBlank String password) {}
29:     public record LoginReq(@NotBlank String username, @NotBlank String password) {}
```

**BookingService.java:33**——服务层返回壳：

```java
33:     public record BookResult(String orderNo, int seatNo) {}
```

用法（BookingController.java:32-34）：

```java
32:     public Booking book(@RequestBody BookReq req, HttpServletRequest http) {
33:         Long uid = (Long) http.getAttribute("uid");
34:         return service.book(uid, req.tripId());   ← 访问器是 tripId() 无 get 前缀
```

Spring Boot 收到 JSON `{"tripId":1}` 时，自动用 record 的全参构造器反序列化；返回 `BookResult` 时自动读访问器序列化。**record + JSON 是天作之合**。

### 枚举的影子：String 状态的"穷人版"

Booking.java 的状态字段是 String：

```java
// backend/train/.../model/Booking.java
/** UNPAID=未支付 / PAID=已支付 / CANCELLED=已取消（含超时关单） */
public String status;
```

教学版故意用 String（省一层 JPA 映射配置），但注释里明说了取值集合——这就是"本该是 enum"的信号。生产代码会写 `@Enumerated(EnumType.STRING) OrderStatus status;`。**看到 String 字段取值有限，就该想到 enum。**

### 接口的震撼教育：TaskRepo 一行八件事

```java
// backend/demo-todo/.../repo/TaskRepo.java（全 8 行）
7: public interface TaskRepo extends JpaRepository<Task, Long> {
8: }
```

空接口继承 `JpaRepository`，立刻白得 `findAll()/findById()/save()/deleteById()/existsById()` 等几十个方法——Spring Data 在运行时**动态生成实现**。接口在这里不是"方法签名清单"，而是"能力申请表"。TaskController 里用到的每个 repo 方法（TaskController.java:23/29/34/44/45）都来自这行继承。

### 边界：JPA 实体为什么不能是 record

对照 Task.java（A01 已读）：

| 需求 | record 给的 | JPA 要的 |
|---|---|---|
| 无参构造 | ❌ 只有全参 final 构造 | ✅ 必须有无参构造（Task.java:20） |
| 字段可改 | ❌ 全 final | ✅ `t.done = !t.done`（TaskController.java:36） |
| 代理/懒加载 | ❌ final 类不好继承代理 | ✅ 框架要生成子类 |

TaskController.java:36 的 `t.done = !t.done;` 直接否决 record——**记录要被修改，record 天生不可变**。口诀：**"过路的 JSON 壳用 record，住店的数据实体用 class"**。

---

## 动手验证

### 实验 1：record 的自动构件（jshell 真实体验）

```java
jshell> record BookReq(Long tripId) {}
jshell> var r = new BookReq(1L)
jshell> r.tripId()
$3 ==> 1
jshell> r
$4 ==> BookReq[tripId=1]              ← 自动 toString
jshell> r.equals(new BookReq(1L))
$5 ==> true                           ← 按字段比较
jshell> r.tripId = 2L
|  错误：无法将变量 tripId 指定为...（final，不可赋值）
```

### 实验 2：enum 的开关

```java
jshell> enum Status { UNPAID, PAID, CANCELLED }
jshell> Status.PAID.name()
$7 ==> "PAID"
jshell> Status.valueOf("UNPAID") == Status.UNPAID
$8 ==> true
jshell> switch (Status.PAID) { case PAID -> "已支付"; default -> "其他"; }
$9 ==> "已支付"
```

### 实验 3：把 record BookReq 对照进 demo01

demo-todo 的 create 接口（TaskController.java:27）直接收整个 `Task` 当请求体——**能用，但不严谨**：调用方可以顺手塞一个 `id` 进来。改进版（真实可操作）：

```java
// 在 TaskController 里加：
public record TaskCreateReq(String title) {}          // 只暴露该暴露的

@PostMapping
public Task create2(@Valid @RequestBody TaskCreateReq req) {
    return repo.save(new Task(req.title()));           // 白名单式收参
}
```

重启 demo-todo 后验证：

```bash
$ curl -X POST http://127.0.0.1:8081/api/tasks -H 'Content-Type: application/json' -d '{"title":"record练习"}'
{"id":2,"title":"record练习","done":false}
```

对照原版 `create`（收 Task 全体）与新版（收 TaskCreateReq）：record 版**字段白名单**防了"客户端乱塞 id/越权字段"——这正是 train 站点用 BookReq 的用意。

---

## 思考题

1. record 的 equals 按字段比较，Task（class）继承 Object 的 equals 按引用比较。两个 `new Task("x")` 用 `==` 和 `.equals()` 各得什么？这会带来什么集合行为差异（剧透 A03）？
2. Booking.status 用 String 而非 enum，教学上"省了什么"，风险上"丢了什么"？
3. TaskRepo 空接口为什么能凭空长出 findAll？这和 C++ 模板的"编译期生成"有何不同？
4. `BookReq(Long tripId)` 的字段是 `Long` 不是 `long`，如果客户端发 `{}`（缺字段），Spring 会构造出什么？用 long 会发生什么？

## 练习题

**练习 1**：定义 `record SeatInfo(int seatNo, String orderNo) {}`，new 两个相同内容的实例，验证 equals==true、hashCode 相同、toString 格式。

**练习 2**：定义 `enum PayState { NONE, PAID, REFUNDED }`，写一个 static 方法 `String cn(PayState s)` 用 switch 表达式返回中文。

**练习 3**：给 TaskController 加 `record TaskCreateReq(String title) {}` 并按实验 3 完成改造，curl 实测；再故意发 `{"id":99,"title":"越权"}` 证明 id 被无视。

### 完整参考答案

**思考题 1**：`==` 都是 false（不同对象）；`.equals()` 对 Task 是 false（继承 Object 按引用），对 record 是 true（按字段）。后果：把 Task 放进 HashSet/当 Map 键时，"内容相同"的两个 Task 会被当成两个元素——查重失效。所以"要进集合的数据"优先 record。

**思考题 2**：省了 JPA 的 `@Enumerated` 映射配置和 enum 类定义。丢了：编译期取值检查（拼错 "UNPAID" 编译照过）、switch 穷尽检查、IDE 补全。风险在字符串比较处（如 `if ("UNPAID".equals(b.status))`）悄悄拼错。

**思考题 3**：Spring Data 在**运行时**为接口动态生成代理对象（字节码生成），方法名按约定翻译成 SQL。C++ 模板是编译期实例化，没有运行时生成这回事——这是"反射 + 字节码增强"的 Java 特色。

**思考题 4**：`{}` 反序列化成 `new BookReq(null)`，`tripId()` 返回 null，下游 `service.book(uid, null)` 会拿到 null 车次号（再被业务校验拦下）。若字段是 `long`，Jackson 无法把"缺失"映射成原始类型，直接抛反序列化异常（400）。**可空语义选 Long，必填语义选 long + 校验注解。**

**练习 1 参考**：

```java
jshell> record SeatInfo(int seatNo, String orderNo) {}
jshell> var a = new SeatInfo(5, "abc"); var b = new SeatInfo(5, "abc")
jshell> a.equals(b) && a.hashCode() == b.hashCode()
$4 ==> true
jshell> a.toString()
$5 ==> "SeatInfo[seatNo=5, orderNo=abc]"
```

**练习 2 参考**：

```java
jshell> enum PayState { NONE, PAID, REFUNDED }
jshell> static String cn(PayState s) { return switch (s) { case NONE -> "未支付"; case PAID -> "已支付"; case REFUNDED -> "已退款"; }; }
jshell> cn(PayState.PAID)
$8 ==> "已支付"
```

**练习 3 参考**：改造后 `curl -X POST .../api/tasks -d '{"id":99,"title":"越权"}' -H 'Content-Type: application/json'` 返回的 JSON 里 `id` 是数据库自增值（如 3），不是 99——TaskCreateReq 根本没有 id 字段，多余字段被 Jackson 默默丢弃。

---

## 本节小结
- record = 不可变数据壳，一行生成构造/访问器/equals/hashCode/toString；适合 JSON 请求/响应与跨层传值。
- enum = 类型安全的有限取值，能带字段方法，switch 穷尽检查。
- interface + default = 能力契约；TaskRepo 一行继承白得全套 CRUD。
- 边界口诀：**过路壳用 record，住店实体用 class**（可变性 + 无参构造是分水岭）。
- String 字段取值有限时，心里应响起 enum 的警铃。

## 下一站

[Java速通-A03-集合与泛型.md](Java速通-A03-集合与泛型.md)——List/Set/Map 三件套、泛型擦除的生活类比，以及和 C++ STL 的逐条对照：`vector→ArrayList`、`map→TreeMap`、`unordered_map→HashMap`。
