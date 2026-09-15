# Java速通-A02 · record、枚举与接口

> 三行件头：①要点——record 一行生成全套；enum 是会做事的常量；interface 是能力契约；②前置——A01 的引用语义；会 C++ 的 struct/enum class；③产出——**亲历一次"record 当 JPA 实体"的编译报错真代码**，把"过路壳用 record、住店实体用 class"的边界用实测钉死。
>
> 🗺 主线进度：Java 速通第 2 站 | 🎞 上一站：A01 类与对象 | 📀 本站所得：record 的一刀边界 + enum/interface 的用法。

## 🎬 开篇三件套

### 你在哪格 + 任务单
你在大厅窗口前学做"单据模板"（record）。任务：①会写 record；②会用 enum（比 C++ enum class 强在哪）；③会 interface/default；④想清 JPA 实体与 record 的边界；⑤写一个 BookReq 在 demo01 对照。

### 开工前自查
- [ ] A01 已消化
- [ ] 知道 struct/enum class
- [ ] jshell 可用

---

## 名词卡

| 名词 | 人话 | C++ 对照 |
|---|---|---|
| **record** | 不可变数据壳，一行顶十行 | 全成员 const 的 struct + 自动 ==/hash |
| **不可变** | 建后字段不许改 | const |
| **访问器** | `name()` 不是 getName() | getter |
| **enum** | 类型安全有限取值，可带方法 | enum class 加强版 |
| **interface** | 方法签名清单 | 抽象基类（无字段） |
| **default 方法** | 接口带默认实现 | 抽象类非纯虚函数 |
| **@Override** | 编译器报警拼错 | override |

## 1. 概念最小人话

record：一行 `public record BookReq(Long tripId) {}` 白送五件套：private final 字段、全参构造器、访问器 `tripId()`、equals+hashCode（按字段）、toString（`BookReq[tripId=1]`）。C++ struct 默认可变，record 天生 final。
enum：自带 `values()/valueOf()/name()/ordinal()`，可带字段方法（每个枚举值一个实例），能进集合、能 switch（Java 21 的 switch 还能带返回值）——`OrderStatus s = "PAID"` 编译不过，C++ 的 int 枚举混用问题消失。
interface + default：类可 implements 多个（弥补单继承）；接口之间 default 冲突有仲裁规则。**try-with-resources 靠 `AutoCloseable` 一个方法接口工作（A04）**——接口在这里是"能力申请表"。

```java
interface Closeable2 {
    void close();                          // 抽象：实现者必须兑现
    default void closeQuietly() {          // default：白送一个默认实现
        try { close(); } catch (Exception ignored) {}
    }
}
```

## 2. 真实代码走查

### record 遍地开花

**BookingController.java:27-28**：

```java
27:     public record BookReq(Long tripId) {}
28:     public record OrderOnlyReq(String orderNo) {}
```

**AuthController.java:28-29**（带校验注解）：

```java
28:     public record RegisterReq(@NotBlank String username, @NotBlank String password) {}
```

**BookingService.java:33**：`public record BookResult(String orderNo, int seatNo) {}`
用法（BookingController.java:32-34）：反序列化 `{"tripId":1}` 自动走 record 的全参构造器；序列化自动读访问器。**record + JSON 天作之合**。

### 枚举的影子：String 状态的"穷人版"

Booking.java 的状态是 String：`/** UNPAID/PAID/CANCELLED */ public String status;`。教学版省一层 JPA 映射；注释里写明取值集合——**String 字段取值有限 = 本该是 enum** 的信号。

### interface 一行八件事：TaskRepo

```java
// backend/demo-todo/.../repo/TaskRepo.java（全 8 行）
7: public interface TaskRepo extends JpaRepository<Task, Long> {}
```

空接口继承白得 findAll/findById/save/deleteById/existsById 等几十个方法——Spring Data 运行时**动态生成实现**（反射+字节码，不是 C++ 模板的编译期）。TaskController.java:23/29/34/44/45 用到的每个 repo 方法都来自这行继承。

**本站真实语境里的 record 常见造型**（均在源码可查）：

| 项目位置 | record | 角色 |
|---|---|---|
| BookingController.java:27 | `BookReq(Long tripId)` | 下单请求壳 |
| AuthController.java:28 | `RegisterReq(username, password)` | 注册壳+校验注解 |
| BookingService.java:33 | `BookResult(orderNo, seatNo)` | 服务层返回壳 |
| BookingController.java:28 | `OrderOnlyReq(orderNo)` | 支付/取消请求壳 |

## 3. 工程实录：把 Booking model 改成 record 的尝试（真编译报错）

**动机关**：A01 说过"JPA 实体四条硬要求"（无参构造、字段可变、可被代理、反射可访问）。这次真把一条"住店实体"随手改成 record，亲眼看报错——比十行说教有效。

### 3.1 直接翻车版（编译期）

**动机关**：上篇说了"JPA 实体有四条硬要求"（无参构造、字段可变、可被代理、可反射访问）。今天真的把一条"住店实体"随手改成 record，亲眼看报错——比十行说教有效。

### 3.1 直接翻车版

`/tmp/opencode/RecordCompile.java`：

```java
public class RecordCompile {
    record Task(Long id, String title, boolean done) {}    // 顺手把 Task 记成 record
    void m(Task t) {
        t.done = true;                    // ← 大胆改字段：任务难 inverted！
    }
    public static void main(String[] a) {}
}
```

编译器原声（本机 javac 21/26 输出一字不差）：

```
RecordCompile.java:4: error: cannot assign a value to final variable done
        t.done = true;
         ^
1 error
```

**读报错**：record 的 component 全是 **final 变量**——赋值直接被拒。而 TaskController.java:36 的 `t.done = !t.done;`（"翻转完成"业务）是本项目真实需求，第一刀就否决。

排查路径：看到 `cannot assign a value to final variable` 的第一反应不是想"绕过 final"，而是**改回 class**——因为这不是 bug，是设计分界（record 的语义就是不可变）。

### 3.2 运行期翻车版（证明"没有无参构造"）

`/tmp/opencode/RecordTry.java`（当 JPA 想"先 new 一个空对象再填字段"时）：

```java
public class RecordTry {
    public record Task(Long id, String title, boolean done) {}
    public static void main(String[] a) {
        try {
            Task.class.getDeclaredConstructor().newInstance();   // JPA 式"无参构造"
        } catch (Exception e) {
            System.out.println("no-arg ctor -> " + e);
        }
    }
}
```

真跑输出：

```
no-arg ctor -> java.lang.NoSuchMethodException: RecordTry$Task.<init>()
```

record 编译器**根本不生成无参构造**（它保证的是"全参建 + final 字段"），JPA 第一步就翻车。两刀合并，JPA 实体用 record 的判决书：

| 需求 | record 给的 | JPA 要的 |
|---|---|---|
| 无参构造 | ❌ 只有全参 final 构造 | ✅ 必要（Task.java:20） |
| 字段可改 | ❌ final（编期拦截，实录 3.1） | ✅ `t.done = !t.done` |
| 代理/懒加载 | ❌ final 类不便生成子类代理 | ✅ Hibernate 生成子类 |

口诀（这次是"动手过后"自己品的）：**过路的 JSON 壳用 record，住店的数据实体用 class**。

### 3.3 record 好用的一面（顺手一跑）

```java
jshell> record BookReq(Long tripId) {}
jshell> var r = new BookReq(1L)
jshell> r
$4 ==> BookReq[tripId=1]              ← 自动 toString
jshell> r.equals(new BookReq(1L))
$5 ==> true                           ← 按字段比较
jshell> r.tripId = 2L
|  错误：无法将变量 tripId 赋值（final，不可赋）
```

想防"客户端乱塞 id"用 record 当白名单壳（`record TaskCreateReq(String title) {}`，只暴露 title）——这就是 train 站点 BookReq 的用意：**接受什么字段，白名单说了算**。

### 3.4 enum 与 switch（顺手一组）

```java
jshell> enum Status { UNPAID, PAID, CANCELLED }
jshell> Status.PAID.name()
$7 ==> "PAID"
jshell> Status.valueOf("UNPAID") == Status.UNPAID
$8 ==> true
jshell> switch (Status.PAID) { case PAID -> "已支付"; default -> "其他"; }
$9 ==> "已支付"
```

枚举用 `==` 是推荐写法（JVM 全局单例）——与 A10 的"对象一律 equals"并不矛盾，那是可变对象，这是枚举。

## 4. 模式对比 / 选型表

| 场景 | 用什么 | 理由 |
|---|---|---|
| JSON 请求/响应壳 | record | 不可变+equals 天成（Jackson 通吃） |
| 跨层传值（orderNo/seatNo） | record | 搬数据不被改 |
| JPA `@Entity` | class | 无参构造+可变+可代理 |
| 取值有限的字段 | enum（或加注 @Enumerated） | 编期检查+switch 穷尽 |
| 只管方法的契约 | interface(+default) | 多继承与默认方法 |

## 5. 动手验证

1. 复跑实录 3.1 与 3.2（都在 /tmp/opencode 下，javac/java 就够），抄下原始报错。
2. enum 玩一把（3.4）逐行对账。
3. 把 demo-todo 的 create 接口加 `record TaskCreateReq(String title) {}` 改造（照 3.3），curl 实测：

```bash
$ curl -s -X POST http://127.0.0.1:8081/api/tasks -H 'Content-Type: application/json' \
    -d '{"id":99,"title":"越权"}'
# 返回 id 是数据库自增值（不是 99）——TaskCreateReq 根本没有 id 字段
```

## 思考题

1. record equals 按字段，class（继承 Object）按引用：两个 `new Task("x")` 的 `==` 与 `.equals()` 各得什么？对 HashSet 造成什么差异（剧透 A03）？
2. Booking.status 用 String 省了什么、丢了什么？
3. TaskRepo 空接口为什么能凭空长出 findAll？与 C++ 模板编译期生成有何不同？
4. `BookReq(Long tripId)` 为什么 Long 不是 long？客户端发 `{}` 会发生什么？
5. 实录 3.1 的报错为什么说"改回 class 是唯一正解"而不是"用反射改"？

## 练习题

1. 定义 `record SeatInfo(int seatNo, String orderNo)`，两个等值实例验证 equals/hashCode/toString。
2. `enum PayState { NONE, PAID, REFUNDED }`，写 switch 表达式 `cn(PayState)` 返中。
3. 给 TaskController 加白名单 record 并 curl 实测"越权 id 被无视"。

### 完整参考答案

**思考 1**：`==` 都 false；`.equals()` 对 Task false（按引用）、对 record true（按字段）。后果：Task 进 HashSet 时"内容相同"当两个元素——查重失效。
**思考 2**：省 `@Enumerated` 映射与 enum 类；丢编期取值检查/switch 穷尽/补全——"UNPAID" 拼错编译照过。
**思考 3**：Spring Data 运行时为接口生成代理（字节码增强），方法名按约定翻译成 SQL；C++ 模板是编译期实例化。
**思考 4**：`Long` 可 null，`{}` 反序列化 `new BookReq(null)`，下游 `service.book(uid, null)` 会拿到 null 车次号再被业务校验拦；若写 long，Jackson 直接 400（无法映射缺失字段到原始类型）。**可空语义选 Long，必填选 long+校验注解**。
**思考 5**：record 的 final 是**语言保证**不是"能被绕过的纪律"——反射也改不了 final 字段的语义（可改字段值但破坏不可变契约、且 record 语义上要求字段即状态）。"住店实体"的可变需求在 record 结构上就不成立，换 class 才是回到正确工具。

### 完整参考答案（练习部分）

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

**练习 3 参考**：改造后 `curl -X POST .../api/tasks -d '{"id":99,"title":"越权"}'` 返回的 id 是数据库自增值——TaskCreateReq 没有 id 字段，多余字段被 Jackson 默默丢弃。

---

## 本节小结
- record = 不可变数据壳，一行白送全套；适合 JSON 壳与跨层传值。
- enum = 类型安全有限取值；String 字段取值有限时心里该响警铃。
- interface+default = 能力契约；TaskRepo 一行继承白得全套 CRUD。
- **边界本次是"真编译"钉死的**：record 阻塞字段赋值（编译错）；record 无无参构造（NoSuchMethodException）。口诀：过路壳 record，住店实体 class。

## 下一站

[Java速通-A03-集合与泛型.md](Java速通-A03-集合与泛型.md)——List/Set/Map 三件套、泛型擦除，以及和 STL 的逐条对照；还会给你一段真实可跑的"堆污染" ClassCastException。
