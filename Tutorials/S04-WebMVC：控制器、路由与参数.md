# S04 WebMVC：控制器、路由与参数

> 主线：本站你走到"前台接待"。前几课知"对象谁来造"，本课解决**一次 HTTP 请求怎么精准落到你的方法上**——路径里 `/{id}` 从哪来？`?from=上海虹桥` 怎么进参数？`{"title":"买牛奶"}` 怎么变成 Java 对象？
>
> 学完本课你能：看懂项目里全部路由与参数注解的真实用法；用 curl 演示 401/404/405/415/200/204 六种真实情形；理解"拦截器递参数"这种非 HTTP 渠道。

## 问题出发

今早调试时的两发"意外"：`PUT /api/tasks/abc` 想改个不存在的 id（我手滑打了字母）——
Spring 没炸 500，而是精准 400；`PATCH /api/tasks` 试新方法——立刻 405。
**两枪都由"参数与路由的绑定层"打出的**，报错 JSON 里"业务话术真空"。
本篇讲"一次 HTTP 怎么精准落到你的方法上"，实录节把这两发打回并逐层解读——
它们也是 S07 收编"默认错误 JSON"的现场证据。

## 本站名词卡

| 名词 | 一句话人话 |
|---|---|
| `DispatcherServlet` | 前台总机：所有 HTTP 请求先到它，再按路由分派 |
| `@RestController` | 方法返回值全部自动转 JSON 的控制器（前后端分离首选） |
| `@Controller` | 传统控制器（返回视图名，配模板渲染用） |
| `@RequestMapping` | 类/方法级路由前缀挂载 |
| `@GetMapping/@PostMapping/@PutMapping/@DeleteMapping` | 指定 HTTP 方法+路径的快捷注解 |
| `@PathVariable` | 取 URL 路径里的占位变量 |
| `@RequestParam` | 取查询串（`?key=value`）里的值 |
| `@RequestBody` | 把请求体 JSON 反序列化为 Java 对象 |

## 一、@RestController vs @Controller

```java
// backend/demo-todo/.../controller/TaskController.java:11-13
@RestController
@RequestMapping("/api/tasks")
public class TaskController {
```

- **`@Controller`**：Server 渲染套路。方法返回 "index" 这类字符串 → 静态页/模板；返回对象需要**显式**加 `@ResponseBody` 才落 JSON。
- **`@RestController`**（= `@Controller` + `@ResponseBody` 精神）——**每个方法返回值自动 JSON 序列化**。本项目 5 个后端全是 API 服务，因此整个项目清一色 `@RestController`。

## 二、本项目在用的四种参数写法（每个都有真实 curl 证据）

### 1）@PathVariable：取路径段

两类用法同在一条方法链上：

```java
// backend/demo-todo/.../TaskController.java:32-47（节选）
@PutMapping("/{id}")
public ResponseEntity<Task> toggle(@PathVariable Long id) { ... }

@DeleteMapping("/{id}")
public ResponseEntity<Void> delete(@PathVariable Long id) { ... }
```

`/{id}` 占位 → 同名参数注入，Spring 自动 String→Long。实测：

```bash
curl -s -X POST http://127.0.0.1:8081/api/tasks -H 'Content-Type: application/json' -d '{"title":"toggle测试"}'
# {"id":9,"title":"toggle测试","done":false}           ← @RequestBody 收 JSON（下一步介绍）

curl -s -X PUT http://127.0.0.1:8081/api/tasks/9
# {"id":9,"title":"toggle测试","done":true}            ← 请求直接命中 @PutMapping("/{id}")，@PathVariable 收 9

curl -s -o /dev/null -w 'HTTP=%{http_code}\n' -X DELETE http://127.0.0.1:8081/api/tasks/9
# HTTP=204

curl -s -o /dev/null -w 'HTTP=%{http_code}\n' -X PUT http://127.0.0.1:8081/api/tasks/99999
# HTTP=404   ← id=99999 在数据库不存在，走 notFound() 分支
```

takeaway 的订单链有更典型写法，orderNo 在**路径中段**：

```java
// backend/takeaway/.../OrderController.java:32-35
@PostMapping("/orders/{orderNo}/pay")
public Order pay(@PathVariable String orderNo, HttpServletRequest http) { ... }
```

### 2）@RequestParam：取查询串

```java
// backend/train/.../TripController.java:25-30
@GetMapping
public List<Map<String, Object>> list(@RequestParam(required = false) String from,
                                      @RequestParam(required = false) String to) {
```

两种调用形态（真实实测）：

```bash
# a) 不带参数（两个 reqParam required=false），走"查全部船次"
curl -s http://127.0.0.1:8084/api/trips
# [{"id":1,"trainNo":"G1024","from":"上海虹桥",...,"stock":3},{...共 5 条...}]

# b) URL 编码后方能针对中文查询串
curl -s 'http://127.0.0.1:8084/api/trips?from=%E4%B8%8A%E6%B5%B7%E8%99%B9%E6%A1%A5&to=%E8%8B%8F%E5%B7%9E'
# [{"id":1,"trainNo":"G1024","from":"上海虹桥","to":"苏州","depart":"08:00","arrive":"08:35","price":42,"total":3,"stock":3}]
```

另一个带**默认值**的样本在审计接口：

```java
// backend/train/.../BookingController.java:53-54
@GetMapping("/audits")
public List<AuditLog> audits(@RequestParam(defaultValue = "5") int n) { ... }
```

### 3）@RequestBody：请求体 JSON → 对象

```java
// backend/demo-todo/.../TaskController.java:26-30
@PostMapping
public Task create(@Valid @RequestBody Task task) {
    task.id = null;               // 新建：让数据库自增（覆盖客户端乱传的 id）
    return repo.save(task);
}
```

`{"title":"买牛奶"}` 由 Jackson 反序列化成 `Task`，`@Valid` 是 S06 的主角。**踩坑实测**（两种喂法都 会失败）：

```bash
# a) 喂非法 JSON
curl -s -w '\nHTTP=%{http_code}\n' -X POST http://127.0.0.1:8081/api/tasks \
     -H 'Content-Type: application/json' -d '{bad json'
# {"timestamp":"...","status":400,"error":"Bad Request","path":"/api/tasks"}
# HTTP=400           ← Jackson 解析失败，Spring 自动回 400

# b) 喂表单格式（Content-Type 未声明 json）
curl -s -w '\nHTTP=%{http_code}\n' -X POST http://127.0.0.1:8081/api/tasks -d 'title=abc'
# {"timestamp":"...","status":415,"error":"Unsupported Media Type","path":"/api/tasks"}
# HTTP=415           ← Content-Type 不对，进不到反序列化
```

### 4）HttpServletRequest.getAttribute：拦截器递来的"手牌"

**参数不是 HTTP 直接带来的**，而是先期通过拦截器解析并塞进请求：

```java
// backend/train/.../BookingController.java:31-35
@PostMapping
public Booking book(@RequestBody BookReq req, HttpServletRequest http) {
    Long uid = (Long) http.getAttribute("uid");    // ← AuthInterceptor 已提前塞好
    return service.book(uid, req.tripId());
}
```

对应拦截器实现（train 侧）：

```java
// backend/train/.../security/AuthInterceptor.java:14-25
@Override
public boolean preHandle(HttpServletRequest req, HttpServletResponse resp, Object handler) {
    String auth = req.getHeader("Authorization");   // 约定：Bearer <token>
    if (auth == null || !auth.startsWith("Bearer ")) {
        resp.setStatus(401); return false;
    }
    Long uid = jwt.verify(auth.substring(7));
    if (uid == null) { resp.setStatus(401); return false; }
    req.setAttribute("uid", uid);
    return true;
}
```

注册拦截器并在豁免簿上开闸（**查询公开，下单需登录**）：

```java
// backend/train/.../config/WebConfig.java:16-22
registry.addInterceptor(auth)
        .addPathPatterns("/api/**")
        .excludePathPatterns(
                "/api/auth/register", "/api/auth/login",
                "/api/trips", "/api/trips/**", "/api/health");
```

实测 401 与 200 一对：

```bash
# a) 没带 token
curl -s -o /dev/null -w 'HTTP=%{http_code}\n' -X POST http://127.0.0.1:8084/api/bookings \
     -H 'Content-Type: application/json' -d '{"tripId":1}'
# HTTP=401    ← AuthInterceptor 拒绝，controller 都没进

# b) 登录 → 带 token 下单
U="stu$(date +%s)"
curl -s -X POST http://127.0.0.1:8084/api/auth/register -H 'Content-Type: application/json' \
     -d "{\"username\":\"$U\",\"password\":\"pass123\"}"
# {"username":"stu1789379712","id":9}
TOK=$(curl -s -X POST http://127.0.0.1:8084/api/auth/login -H 'Content-Type: application/json' \
     -d "{\"username\":\"$U\",\"password\":\"pass123\"}" | python3 -c 'import json,sys;print(json.load(sys.stdin)["token"])')
echo "${TOK:0:40}..."
# eyJhbGciOiJIUzM4NCJ9.eyJzdWIiOiJzdHUxNzg...

curl -s -X POST http://127.0.0.1:8084/api/bookings -H "Authorization: Bearer $TOK" \
     -H 'Content-Type: application/json' -d '{"tripId":1}'
# {"id":5,"orderNo":"78287226-d8af-40a0-a22c-c1ef88ffd3d2","userId":9,"tripId":1,"seatNo":5,
#  "status":"UNPAID","createdAt":"2026-09-14T09:55:17.748751461Z","paidAt":null}
```

**四个注解各自的岗位**一目了然：`@PostMapping` 定路由；`@RequestBody` 收订单体；`Authorization` 头被拦截器换算成 `uid`；`getAttribute("uid")` 再接出来。

### 另味：record 当迷你输入 DTO

```java
// backend/train/.../BookingController.java:27-28
public record BookReq(Long tripId) {}
public record OrderOnlyReq(String orderNo) {}
```

一行 record 搞定"只带一个字段的输入对象"，访问 `req.tripId()` 比 getter 短。S05 详谈 DTO 与 record 的配合。

## 三、动手验证：smoke.py 同款三条 curl

`infra/smoke.py` 中 demo-todo 三连打总揽本课知识，等价 curl 版：

```bash
# ① POST 创建（@RequestBody + JSON 头）
curl -s -X POST http://127.0.0.1:8081/api/tasks \
     -H 'Content-Type: application/json' -d '{"title":"冒烟任务"}'
# {"id":10,"title":"冒烟任务","done":false}

# ② PUT 翻转（@PathVariable，用 ① 的 id）
curl -s -X PUT http://127.0.0.1:8081/api/tasks/10
# {"id":10,"title":"冒烟任务","done":true}

# ③ DELETE 删除（@PathVariable + 204 无体）
curl -s -o /dev/null -w 'HTTP=%{http_code}\n' -X DELETE http://127.0.0.1:8081/api/tasks/10
# HTTP=204
```

另有一条补充验证——demo-chat 的 SSE 通路也靠 `@RestController`+特殊 produces：

```java
// backend/demo-chat/.../ChatController.java:21-23
@GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public SseEmitter stream() {
```

`produces = TEXT_EVENT_STREAM_VALUE` 是**不常见的 MIME 声明**——告诉"返回的是长连接事件流"（不是 JSON）。一条实测（流式，有体）：

```bash
curl -s -m 3 -N http://127.0.0.1:8082/api/chat/stream &
curl -s -X POST http://127.0.0.1:8082/api/chat/send -H 'Content-Type: application/json' \
     -d '{"user":"S04读者","text":"你好"}'
# 第一次输出：event:msg  data:{"user":"S04读者","text":"你好"}
```

（服务端主动说话——SSE 的具体机制另在 SSE 课程拆解，此处只见"produces 与普通 JSON 接口的差别"一眼。）

## 三点五、工程实录：踩坑与修复（真机实测）

### 实录：绑定层与路由层的两个"框架答话"（谁答的、何时答的）

**现场复现**（2026-09-15 真机）：

```bash
# ① id 类型不匹配：{id} 是 Long，喂了个 "abc"
curl -s -w '\nHTTP=%{http_code}\n' -X PUT http://127.0.0.1:8081/api/tasks/abc

# ② 路由在、方法不在：/api/tasks 只有 G/POST/PUT/Delete 四个映射
curl -s -w '\nHTTP=%{http_code}\n' -X PATCH http://127.0.0.1:8081/api/tasks -H 'Content-Type: application/json' -d '{}'
```

真实输出：

```json
{"timestamp":"2026-09-15T01:55:44.740+00:00","status":400,"error":"Bad Request","path":"/api/tasks/abc"}
HTTP=400
```

```json
{"timestamp":"2026-09-15T01:55:44.748+00:00","status":405,"error":"Method Not Allowed","path":"/api/tasks"}
HTTP=405
```

**逐行解读（两枪有先后次序）**：

| 情形 | 谁答的 | 何时答的 | 前端拿到什么 |
|---|---|---|---|
| 405 | `DispatcherServlet`（路由查表） | controller 方法**根本没被找到** | 框架默认 JSON，无业务话术 |
| 400 | `HandlerMethodArgumentResolver`（绑定阶段） | 方法已匹配，参数解析炸（`MethodArgumentTypeMismatchException`） | 框架默认 JSON，无业务话术 |

对照 S06 的"手写校验"一族，本项目的三条"错答"线路都在：

- `@Valid` 校验失败 → 400（入口层，S06 主线）；
- `@PathVariable` 类型不匹配 → 400（更早的绑定层，本实录）——语义都是"客户端给的东西不合法"；
- 未知路径/错误方法 → 404/405（连方法都没进）。

**排查路径**：启动日志里这几枪**一行 ERROR 都没有**（正常分流），默认 JSON 由
`BasicErrorController` 兜底接答——这正是 S05/S07 的考点：**前端只看到 `{"error":"Bad Request"}`，
哪个业务环节错了、该提示什么，一概不计**。
**一句收束**：绑定层比业务层答得更早、也答得体面（状态码对）；话术则要等 S07 的
`GlobalExceptionHandler` 补一条 `MethodArgumentTypeMismatchException → 400 + "id 必须是数字"` 才能补齐。

## 四、思考题

1. 类上 `@RequestMapping("/api/tasks")` 与方法 `@PutMapping("/{id}")` 拼出来的完整路径是什么？若类上去掉 `@RequestMapping`，方法上要怎么写才能等价？
2. 一个方法**同时**用了 `@RequestBody BookReq req` 与 `HttpServletRequest http`，Spring 怎么笺别两参数来自不同渠道？（提示：注解决定"来源分派器"）
3. `@PathVariable String orderNo` 在 takeaway `get` 方法与 `@RequestParam` 旁边同方法混用（另一方法 `list`）——两种注解"放行"的 URL 形态各是什么？请各举一个真实 curl。

## 五、练习题

1. 给 demo-todo 加 `GET /api/tasks/search?q=`：按 title 模糊过滤列表；用 curl 分别带参与不带参验证（`required=false` 观点下不作 400 断言）。
2. 给 train `TripController` 增加路径过滤接口 `GET /api/trips/{trainNo}`（仅匹配车次号）：`@PathVariable` 取 trainNo。实体数据查询库内 trainNo（G1024、G1025 等）。用 `curl` 验证其中一趟完美命中。

### 参考答案

1. 关键码：

```java
@GetMapping("/search")
public List<Task> search(@RequestParam(required = false) String q) {
    var all = repo.findAll();
    if (q == null || q.isBlank()) return all;
    return all.stream().filter(t -> t.title.contains(q)).toList();
}
```

验证：

```bash
curl -s 'http://127.0.0.1:8081/api/tasks/search?q=%E7%89%9B%E5%A5%B6'
# [{"id":8,"title":"买牛奶","done":false}]
curl -s -o /dev/null -w 'HTTP=%{http_code}\n' 'http://127.0.0.1:8081/api/tasks/search'
# HTTP=200   ← required=false，缺参不报错，返回全部
```

2. 关键码：

```java
@GetMapping("/{trainNo}")
public TrainTrip byTrainNo(@PathVariable String trainNo) {
    return repo.findByTrainNo(trainNo).orElseThrow(
            () -> new IllegalArgumentException("车次不存在"));
}
```

验证（repo 需补一个 `findByTrainNo` 方法）：

```bash
curl -s http://127.0.0.1:8084/api/trips/G1024
# {"id":1,"trainNo":"G1024","from":"上海虹桥","to":"苏州",...}
```

## 本节小结
- `@RestController` = 所有方法回 JSON；`@Controller` 留给模板渲染。
- `@PathVariable` 取路径、`@RequestParam` 取查询串、`@RequestBody` 收请求体；Content-Type 不对回 415，JSON 坏回 400。
- "拦截器递参数"是带权限链的真实模式：`Authorization` 头解析得 uid → `req.setAttribute` → controller 接手。
- 五六七课（REST 响应 / 校验 / 全局异常）分别拆：HTTP 怎么**答**才对、参数进来怎么**验**才严、出事时怎么**说**才体面。

**下一站：S05 REST 与 DTO：对外接口的颜值。**
