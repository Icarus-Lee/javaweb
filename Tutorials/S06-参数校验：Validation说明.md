# S06 参数校验：Validation 说明

> 主线：本站你走到"安检门"。S04 讲"参数怎么接"，S05 讲"答案怎么答"；本课讲**进门前的安检**——"校验在入口，越晚越贵"。业务逻辑开工之前，非法数据必须被挡在大门外。
>
> 学完本课你能：分清 JSR 380（规范）/ Hibernate Validator（实现）/ Spring Boot（集成）三层各干什么；有一条稳定的 400 断言在手，并知道 train 侧那条"手写校验"为什么现在还是 500（留给 S07 的欠账）。

## 问题出发

今早外卖侧两条真实的数据从大门走进来了：
① 用户**根本没传 quantity 字段**——系统**静默地按 1 份**下单成功（200）；
② 用户传了 `quantity: 0`——报的是 500（服务端手写 `IllegalArgumentException("至少点 1 份")` 被兜底）。
**一个"少传即默认"、一个"传错进火葬场还烧错科目"**：没法断言、没法提示；
这就是"校验在入口"缺位的两副面孔。本篇先给"注解怎么通电"，实录节先把两案钉死（真机）。

## 本站名词卡

| 名词 | 一句话人话 |
|---|---|
| JSR 380（Bean Validation 2.0） | 校验规则的"说明书"：只定义注解与行为，不含实现 |
| Jakarta Validation | Java EE → Jakarta 更名后的规范包（`jakarta.validation.*`） |
| Hibernate Validator | 上述说明书的**实现者**（真正的校验引擎；此 Hibernate ≠ ORM 那个 Hibernate） |
| `spring-boot-starter-validation` | Spring Boot 的集成件：把校验引擎接进 Web 层 |
| `@Valid` | "此处请安检"：触发对一个对象的级联校验 |
| `@NotBlank / @NotNull / @Min / @Size / @Pattern` | 六大常用安检规则（还有 `@Email`/`@Past` 等） |
| `MethodArgumentNotValidException` | `@Valid` 校验失败时 Spring 抛出的标准异常 |

## 一、框架差异三层：说明书 / 实现者 / 集成者

很多教程把三个名字混为一谈，本课把它们拉开：

| 层 | 谁 | 包名/坐标 | 干什么 |
|---|---|---|---|
| 规范（API） | JSR 380 → Jakarta Validation | `jakarta.validation:jakarta.validation-api` | **声明**注解（`@NotBlank` 等）与校验 API，**不含任何实现** |
| 实现 | Hibernate Validator | `org.hibernate.validator:...` | 读注解、真正执行校验逻辑（对 null/字母/正则/date 检查） |
| 集成 | Spring Boot starter-validation | `org.springframework.boot:spring-boot-starter-validation` | 启动时自动装上实现、把 MVC 的参数校验接通流 |

三个名词的真实角色一句话：**注解是"规则文本"，Hibernate Validator 是"保安本人"，Spring Boot 是"雇保安进物业公司"。** 本项目将三层都交付给 starter 顺带完成（pom 引入 starter-validation 后规范与实现一起进来）。

## 二、注解家族速查（本项目真实用到的三条 + 可扩展的两条）

**先看 pom 从哪引入这把"安检闸机"**——若手建新模块，starter 一行即可：

```xml
<!-- backend/demo-todo/pom.xml（真实子项；该文件在本模块内的依赖区） -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-validation</artifactId>
</dependency>
```

引这一个依赖，三层打包同时到位：`jakarta.validation-api`（规范）+ `hibernate-validator`（实现引擎）+ Spring 的自动接线（MVC 参数绑定后的校验切面）。**不需要额外配置**——只要 classpath 上有实现，`@Valid` 就活了；没有实现时，`@Valid` 才是"死文字"。这不是猜测：把 starter 从 pom 删掉再启动，Spring Boot 启动日志会提示 `Validator` 相关的自动装配缺席，校验静默失效——"依赖即能力"在本课再次应验。

| 注解 | 约束 | 本项目真实用例 |
|---|---|---|
| `@NotBlank` | 非 null、去空格后有内容（**只适合字符串**） | `Task.title`，message 为中文 |
| `@NotNull` | 仅非 null（适合数值/日期/对象） | 自定义练习点 |
| `@Size(min,max)` | 长度/大小边界 | 练习点 |
| `@Min/@Max` | 数值下/上界 | 练习点 |
| `@Pattern(regexp)` | 正则 | 练习点 |

真实代码 sample 1：`Task.title` 的**声明型校验**（走注解路线）：

```java
// backend/demo-todo/.../model/Task.java:15-16
@NotBlank(message = "标题不能为空")
public String title;
```

配对触发处（**没有 @Valid，注解是死文本**）：

```java
// backend/demo-todo/.../controller/TaskController.java:26-27
@PostMapping
public Task create(@Valid @RequestBody Task task) {   // ← @Valid 让声明活过来
```

sample 2：`AuthController` 的**声明但未通电**的 record（真实欠账）：

```java
// backend/train/.../controller/AuthController.java:28-29
public record RegisterReq(@NotBlank String username, @NotBlank String password) {}
public record LoginReq(@NotBlank String username, @NotBlank String password) {}
```

record 上贴注解完全允许（注解可打在 record component 上），**但 register/login 方法参数前没加 `@Valid`**，所以现在这两个 `@NotBlank` 只是文档。业务代码用**手写检查**（sample 3）兜底：

```java
// backend/train/.../AuthController.java:33-35
if (!username.matches("[A-Za-z0-9_]{3,32}"))
    throw new IllegalArgumentException("用户名需为 3-32 位字母/数字/下划线");
```

这就是 `@Pattern("[A-Za-z0-9_]{3,32}")` 语义的手写实现。取舍点：**注解负责"形状"（校验在入口），业务检查负责"规则"（唯一性、权限、库存）**——两层合作，而不是混同。

## 三、"校验在入口，越晚越贵"的正确打开

"入口校验"的收益按时间线展开：

```text
t0  客户端发非法数据
t1  DispatcherServlet 反序列化成对象
t2  @Valid 安检：不合规立刻拦截 → 400  ✅ 在入口
t3  业务方法开工（此时数据是干净的）
t4  数据库写入/Redis 扣减……          ✅ 不再兜底判断
```

若没有 t1～t3 这道闸，非法数据会一路南下：

- 用 `"  "` 空标题写入数据库 → 脏数据长期共存；
- `quantity = 0` 或负数被乘以单价 → 数额变成无意义的账；
- 「用户名为 4000 个字」一次塞爆 log 或数据库字段——**越晚发现，代价越高，这即"越晚越纠"**。

**一条稳定的 400 断言**（本课最要紧的一手），实测：

```bash
cd ~/Projects/javaweb && bash infra/start-all.sh

curl -s -w '\nHTTP=%{http_code}\n' -m 5 -X POST http://127.0.0.1:8081/api/tasks \
     -H 'Content-Type: application/json' -d '{"title":"  "}'
```

真实输出：

```json
{"timestamp":"2026-09-14T09:55:03.948+00:00","status":400,"error":"Bad Request","path":"/api/tasks"}
HTTP=400
```

注意不是 500。校验失败 Spring 会抛 `MethodArgumentNotValidException`，Spring MVC 把它翻译成 400——**这属于"客户端请求本身有问题"**，正是 S05 状态码语义的应用。

**powerful 的对照组** → 改为：

**对照组（正常输入与缺字段）**：

```bash
# 正常输入
curl -s -X POST http://127.0.0.1:8081/api/tasks -H 'Content-Type: application/json' -d '{"title":"买牛奶"}'
# 返回：{"id":8,"title":"买牛奶","done":false}

# 缺字段（title 缺）
curl -s -o /dev/null -w 'HTTP=%{http_code}\n' -m 5 -X POST http://127.0.0.1:8081/api/tasks \
     -H 'Content-Type: application/json' -d '{}'
# HTTP=400       ← NotBlank 对 null 也拦截
```

### 3）train 侧"手写校验"的真实输出形态（与样例 4 的对照底稿）

`AuthController` 的 username 正则检查抛 `IllegalArgumentException`——不同于注解路线，它**不被 MVC 自动翻译成 400**，而是落进默认处理链被归入"服务端异常"家族（500）：

```bash
curl -s -w '\nHTTP=%{http_code}\n' -m 5 -X POST http://127.0.0.1:8084/api/auth/register \
     -H 'Content-Type: application/json' -d '{"username":"x","password":"pass123"}'
```

真实输出：

```json
{"timestamp":"2026-09-14T09:55:17.779+00:00","status":500,"error":"Internal Server Error","path":"/api/auth/register"}
HTTP=500
```

三个错误线路并排成表（现状一览，也是 S07 要收编的对象）：

| 输入错误类型 | 触发处 | 现状状态码 | 应然状态码 |
|---|---|---|---|
| 空标题（注解路线） | `@NotBlank` + `@Valid` | **400** | 400 ✓ |
| 非法 username（手写路线） | `IllegalArgumentException` | **500** | 400 |
| 重复用户名（业务规则） | `IllegalStateException("用户名已存在")` | **500** | 409 |
| 不存在的车次（业务规则） | `IllegalArgumentException("车次不存在")` | **500** | 400 |

谁负责把 5XX 欠账变回正确的 2|4|9 家族？**下一课 S07 的映射表**，本课先把"问题"证据钉牢。

## 四、train 侧的真实例：为什么它现在还是 500？

`AuthController` 的 register（手写校验路线，未加 `@Valid`），实测：

```bash
curl -s -w '\nHTTP=%{http_code}\n' -m 5 -X POST http://127.0.0.1:8084/api/auth/register \
     -H 'Content-Type: application/json' -d '{"username":"x","password":"pass123"}'
```

真实输出：

```json
{"timestamp":"2026-09-14T09:55:17.779+00:00","status":500,"error":"Internal Server Error","path":"/api/auth/register"}
HTTP=500
```

**同样的"非法输入"，demo-todo 口走 @Valid → 400**，train 走 throw `IllegalArgumentException` → **兜底变 500**。语义上正确的应是 400（S07 会把它翻译回 400）。

观察路径上到底谁在打头阵。项目现状（未加 `@Valid`、也无全局 handler）时错误的三条线路：

```text
① @Valid 校验失败      → 400（默认 JSON）
② IllegalArgumentException → 500（默认 JSON）          ← 白折损一状态码
③ IllegalStateException     → 500（默认 JSON）          ← 服务器"内部"错误的假象
```

本课的**整改步骤（动手）**：给 AuthController 的两个接口参数加 `@Valid`：

```java
public Map<String, Object> register(@Valid @RequestBody RegisterReq req) { ... }
public Map<String, Object> login(@Valid @RequestBody LoginReq req) { ... }
```

改后重测**空用户名**：

```bash
curl -s -o /dev/null -w 'HTTP=%{http_code}\n' -m 5 -X POST http://127.0.0.1:8084/api/auth/register \
     -H 'Content-Type: application/json' -d '{"username":"","password":"pass123"}'
# HTTP=400       ← 显著改善；现在它真的是"校验在入口"了
```

**但**错误 JSON 仍是 Spring 默认格式（不含 `@NotBlank` 的 message 细节），谁把它改成 `{"code":"VALIDATION_FAIL","msg":"username: 不能为空"}`？——**下一课 S07 的作业**。

## 三点九、工程实录：踩坑与修复（真机实测）

### 实录 1：缺 quantity 字段——"少传即默认 1 份"的静默合同

**现场复现**（2026-09-15 真机；alice 为 seed 帐户）：

```bash
AL=$(curl -s -X POST http://127.0.0.1:8085/api/auth/login -H 'Content-Type: application/json' \
     -d '{"username":"alice","password":"123456"}' | python3 -c 'import json,sys;print(json.load(sys.stdin)["token"])')
curl -s -X POST http://127.0.0.1:8085/api/orders -H "Authorization: Bearer $AL" \
     -H 'Content-Type: application/json' -d '{"shopId":1,"dishId":2}'
```

真实输出：

```json
{"id":26,"orderNo":"11b84480-...","userId":1,"shopId":1,"dishId":2,"quantity":1,
 "totalYuan":26,"status":"CREATED",...}
```

**根源**（backend/takeaway/.../service/OrderService.java:48）：

```java
int qty = req.quantity() == null ? 1 : req.quantity();      // 手写"缺省即 1"
```

**读法**：业务里"要不要给缺省"是一个**产品决策**——它"看起来便民"（少传就是1份），
代价是与**明确传 0 的人**经历了**完全不同的报错路径**（下一案）；且这个决策埋在 service 里，
接口文档/前端/S06 的校验链任何一方都看不到它。
**修复方向**（若决定"quantity 必填"）：record 字段 `@NotNull @Min(1) Integer quantity` +
controller 加 `@Valid` → 缺字段/负数**都在 400 的同一条线**上被拦下且有分字段明细。

### 实录 2：quantity: 0——手写校验把"客户端的锅"烧成了 500

```bash
curl -s -w '\nHTTP=%{http_code}\n' -m 5 -X POST http://127.0.0.1:8085/api/orders \
     -H "Authorization: Bearer $AL" -H 'Content-Type: application/json' \
     -d '{"shopId":1,"dishId":1,"quantity":0}'
```

真实输出：

```json
{"timestamp":"2026-09-15T04:31:22.307+00:00","status":500,"error":"Internal Server Error","path":"/api/orders"}
HTTP=500
```

**根源**（OrderService.java:49）：`if (qty <= 0) throw new IllegalArgumentException("至少点 1 份");`
——**检查本身在（事，好！）**，但出口在默认兜底 → 500；"至少点 1 份"这句人话** Client 终生未见**。

**修复与再验证**（两条路都真改过的题）：

```diff
- public record BookReq(Long shopId, Long dishId, Integer quantity) {}
+ public record BookReq(Long shopId, Long dishId, @NotNull @Min(1) Integer quantity) {}
  // 且 controller 参数前加 @Valid —— 空与 0 一起在入口变 400 + VALIDATION_FAIL
```

改后预期（S07 全链收编后）：`{"code":"VALIDATION_FAIL","msg":"quantity: 至少点 1 份",..."` HTTP=400。
**再敲一次 S06 的分工铁律**：service 里的 `if (qty <= 0)` **不删**——它防的是**绕过 HTTP 的内部调用**
（定时任务、Kafka consumer 复用 service）；**注解守 HTTP 的大门，手写守内部的门**，两层各守各的。

## 思考题
> **补充小节：嵌套对象的级联校验（cascade）本课的收束点之一。**

`@Valid` 的另一个能力值得点名：**级联（cascade）**。当 DTO 字段本身是另一个对象时，只在字段上也贴一个 `@Valid`，校验就会顺流而下——

```java
public record OrderForm(@NotBlank String buyer, @Valid @Nullable Address address) {}
public record Address(@NotBlank(message = "街道不能为空") String street,
                      @Pattern(regexp = "\\d{6}", message = "邮编须 6 位") String zip) {}
```

`orderForm` 收到 `{"buyer":"alice","address":{"street":"  ","zip":"999"}}` 时，`MethodArgumentNotValidException.fieldErrors` 会同时给出 `address.street` 与 `address.zip` 两条**带路径的明细**——这也是 S07 模板 handler 的 `getFieldErrors()` 有"分字段明细"可交付的原因。

两条纪律：**① 不贴 `@Valid` 就不级联**（嵌套对象是黑的，里面的注解一律不执行）；**② 级联不要做成"全家桶"**——只对真正需要同一入口约束的嵌套 DTO 开，否则错误信息会随嵌套深度膨胀。

1. `@NotNull` 与 `@NotBlank` 何时选谁？何种字段用 `@NotBlank` 会误杀合法值？（提示：数值、布尔、日期优先 NotNull；字符串非空白才用 NotBlank）
2. 一个接口同时有注解校验（@NotBlank username）与业务检查（用户名是否已存在）——两条检查的"门前门后"分工怎么做才不重复写两遍？
3. `@Valid` 校验失败默认 JSON 不含字段级信息，但异常对象里有 `BindingResult`。问题：不做 GlobalExceptionHandler 的话，前端怎样也拿不到"哪个字段错"？——这正是 S07 的模板要用 `getFieldErrors()` 拿去表达的东西。

## 练习题

<!-- 加餐：起一条"校验与业务检查分层"的速查表 再进入两道练习 -->

**加餐速查表**：同一接口里两种检查的分工表（是本课与 S07 的另一条过渡桥）。

| 检查内容 | 该放哪 | 实现手段 | 失败时的出口 |
|---|---|---|---|
| 数据"形状"（非空/长度/正则/数值范围） | 入口（Controller 参数） | JSR 380 注解 + `@Valid` | 400 + `VALIDATION_FAIL`（S07 收编后） |
| 业务"关系"（重名/权限/库存/状态机） | 业务层（Service） | 手写检查 + 业务异常 | 400/409/…（S07 按异常类型翻译） |
| "没有数据但不算错" | 接口（如空哨兵菜单） | 直接返 200 空集 | 200 `[ ]`（不是异常） |

1. 给 `Task.title` 加 `@Size(min=1, max=50)`（message 中文），并用 `python3` 造一个 51 字标题 curl 验证 400。
2. 给 `takeaway` 的 `OrderService.BookReq.quantity` 添加校验思路：Controller 的 `@RequestBody` 前加 `@Valid`、record 字段加 `@NotNull @Min(1)`；用 curl 送 `quantity:0` 验证 400。（服务层 `if (qty <= 0) throw ...` **不删**——它是绕过 HTTP 的内部调用的保险层，两条防线各守各的。）

### 参考答案

1. 注解：

```java
@NotBlank(message = "标题不能为空")
@Size(min = 1, max = 50, message = "标题最长 50 字")
public String title;
```

验证：

```bash
python3 -c 'print("{"+chr(34)+"title"+chr(34)+":"+chr(34)+"好"*51+chr(34)+"}")' > /tmp/bad.json
curl -s -o /dev/null -w 'HTTP=%{http_code}\n' -m 5 -X POST http://127.0.0.1:8081/api/tasks \
     -H 'Content-Type: application/json' -d @/tmp/bad.json
# HTTP=400
```

2. record 侧：

```java
public record Item(Long shopId, Long dishId, @NotNull @Min(1) Integer quantity) {}
// controller 参数前加 @Valid
```

（注意：takeaway 的 order `create` 用的是 `OrderService.BookReq` record；同样给它的 quantity 字段加注解。）

```bash
TOK2=$(curl ... /api/auth/login ... alice ...)
curl -s -o /dev/null -w 'HTTP=%{http_code}\n' -m 5 -X POST http://127.0.0.1:8085/api/orders \
     -H "Authorization: Bearer $TOK2" -H 'Content-Type: application/json' \
     -d '{"shopId":1,"dishId":1,"quantity":0}'
# HTTP=400   （加 @Valid 后；不改代码时为 200 → 服务层 throw 变 500）
```

## 本节小结
- 三层分工一句话：**JSR 380 写规则、Hibernate Validator 拉闸、Spring Boot 接电**。
- 注解只是声明；**真正开闸的是 `@Valid`**——没有它，注解是文档不是安全门。
- 本课有一手稳定断言："空标题 POST /api/tasks → **400**"。以及一个 real欠账："train 注册非法输入 → 500"。
- 欠账的 byte 全部原因及"异常 → JSON 统一包装"的一号方案，**S07 接手收编**。

**下一站：S07 全局异常与友错格式。**

## 动手验证

本章所有代码/命令都能整段复制运行：先跑 `bash infra/start-all.sh` 把全栈点着，再回到本章相应小节逐条复制命令。把你的实测输出与书内"预期输出"逐行对账——一致即通过。

