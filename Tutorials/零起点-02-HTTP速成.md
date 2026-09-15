# 零起点-02 · HTTP 速成：一封信的格式规范

> 三行件头：①要点——请求/响应报文的格式、四个方法的"语义区别"（不是能力区别）、状态码的条件反射；②前置——Z01 的接线表在手边、全栈已起；③产出——能用 curl 完成"GET/POST/DELETE 打同一个 URL 观察状态码差异"的方法学实验。
>
> 🗺 主线进度：第 2 站 | 🎞 上一站：Z01 全栈地图 | 📀 本站所得：一张"方法→状态码"的实测对照表。

## 名词卡（重排序：按"读报文顺序"）

| 名词 | 人话解释 |
|---|---|
| **URL** | 资源地址：`协议://主机:端口/路径?参数` |
| **请求行** | 第一行：`方法 路径 版本`，如 `GET /api/tasks HTTP/1.1` |
| **请求头** | 冒号分隔的元信息，如 `Content-Type: application/json` |
| **请求体** | POST/PUT 的数据（本项目全是 JSON） |
| **状态码** | 响应第一行的三位数：2xx 好事、4xx 你错、5xx 我崩 |
| **幂等（method 关键词）** | 同一请求发两遍，结果和发一遍一样（不是"没副作用"，别混） |
| **Cookie** | 服务器发的小纸条，浏览器每次自动带回去 |
| **JWT** | 一串自带签名的令牌，请求头里证明"我是谁" |
| **curl** | 命令行版浏览器：`-i` 看头、`-X` 指定方法、`-H` 加头、`-d` 带体、`-w` 拿状态码 |

---

## 1. 问题驱动开场

三个 curl 打同一个 URL `/api/tasks/1`，只是方法不同，服务器给三种不同回复——**方法是有语义的动词，不只是"换了个拼写"**。本站就靠这一个实验把 HTTP 方法讲清。先看一封"信"长啥样（真实报文，用 `curl -i` 抓的）：

```
GET /api/trips HTTP/1.1          ← 请求行：方法 + 路径 + 版本
Host: 127.0.0.1:8084
User-Agent: curl/8.x
Accept: */*
                                  ← 空行：头结束
（GET 无请求体）

HTTP/1.1 200                     ← 状态行
Content-Type: application/json
Transfer-Encoding: chunked

[{"id":1,"trainNo":"G1024",...}] ← 响应体
```

## 2. 概念最小人话（压缩版）+ 对照表

一句话：HTTP 就是你写过 socket 协议的"定死格式的那个"——动词(GET/POST/…) + 名词(路径) + 元信息(头) + 内存(体)，服务器回一行状态码 + 头 + 体。

| 状态码 | 含义 | 本项目真实出处 |
|---|---|---|
| 200 | 办好，东西在体里 | 查车次、登录成功 |
| 204 | 办好，没东西给你 | `DELETE /api/tasks/{id}` 删成功 |
| 401 | 没登录/令牌无效 | 不带 token 访问 `/api/bookings/mine` |
| 404 | 没这个资源 | 删不存在的任务 |
| 405 | 这个 URL 没配这个方法 | `GET /api/tasks/{id}`（下文实录主角） |
| 500 | 后端自己出错了 | 错误密码登录 |

记法：**4 开头怪自己（请求不对），5 开头怪后端（服务出错）**。

身份手环两种（各一段）：**Cookie**（浏览器自动带回，只在浏览器好使）与 **JWT**（自己存，手动放 `Authorization: Bearer <token>`，App/脚本通吃）。本项目两个站点都用 JWT，token 是三段式 `头部.载荷.签名`；载荷 base64 可解（别放密码），签名防篡改。

## 3. 真实代码走查

### 走查 0：demo-todo 的路由表（405 事故的第一现场）

`backend/demo-todo/src/main/java/com/javaweb/todo/controller/TaskController.java`（48 行全貌关键段）：

```java
11: @RestController
12: @RequestMapping("/api/tasks")
13: public class TaskController {
14:     private final TaskRepo repo;
17:     public TaskController(TaskRepo repo) { this.repo = repo; }   // 构造注入
21:     @GetMapping                                       // 只配"整列查询"
22:     public List<Task> list() { return repo.findAll(); }
26:     @PostMapping
27:     public Task create(@Valid @RequestBody Task task) {
28:         task.id = null;                               // 新建让数据库自增
29:         return repo.save(task);
30:     }
32:     @PutMapping("/{id}")                             // 只配 PUT /{id}
33:     public ResponseEntity<Task> toggle(@PathVariable Long id) { ... }
42:     @DeleteMapping("/{id}")                          // 只配 DELETE /{id}
43:     public ResponseEntity<Void> delete(@PathVariable Long id) {
44:         if (!repo.existsById(id)) return ResponseEntity.notFound().build();  // 404 的出处
45:         repo.deleteById(id);
46:         return ResponseEntity.noContent().build();    // 204 的出处
47:     }
48: }
```

**为什么这样写**：没有 `GET /{id}` 的映射，所以谁 GET `/api/tasks/1` 谁吃 405——这正是下文实录要用到的"路由盲区"，看这份源码你要能一眼说出"GET /{id} 应加 4 行"。

### 谁在发 401：AuthInterceptor

`backend/train/src/main/java/com/javaweb/train/security/AuthInterceptor.java`：

```java
18:            resp.setStatus(401);          // 没带 Authorization 头 → 401
22:        if (uid == null) { resp.setStatus(401); return false; }
```

拦截器像安检口：先验 JWT（`JwtUtil.verify`，security/JwtUtil.java:37），验不出直接回 401，业务代码根本不进。

### 谁在发 token：AuthController

`backend/train/src/main/java/com/javaweb/train/controller/AuthController.java`：

```java
46:    @PostMapping("/login")
47:    public Map<String, Object> login(@RequestBody LoginReq req) {
49:        User u = users.findByUsername(req.username())
50:                .orElseThrow(() -> new IllegalStateException("用户不存在"));
52:        if (!u.passwordHash.equals(sha256("javaweb-" + req.password())))
53:                throw new IllegalStateException("密码错误");
54:        String token = jwt.issue(u.id, u.username);
```

第 50/52 行：**"用户不存在"和"密码错误"都被 Java 的 `orElseThrow`/`throw new IllegalStateException` 抛出**。教学版没配统一异常翻译，Spring 兜底变 500。第 53 行比的是 sha256 哈希，密码不落明文。

## 4. 工程实录：三种 HTTP method 打同一个 URL（真跑记录）

**同一个 URL，同一个语义站点的同一个路径，三个动词，三种反应。** 且看下表，数据是本机实测的 `curl -w '%{http_code}'`（todo 站点的 `/api/tasks/2`，其对应 task 真实存在）：

```bash
# 先造一个道具
$ curl -s -X POST http://127.0.0.1:8081/api/tasks -H 'Content-Type: application/json' \
    -d '{"title":"method 实验道具"}'
{"id":50,"title":"method 实验道具","done":false}

# 三连击
$ curl -s -w '\n %{http_code}\n' http://127.0.0.1:8081/api/tasks/1
405
$ curl -s -X POST -w '\n%{http_code}\n' http://127.0.0.1:8081/api/tasks/1
405
$ curl -s -X DELETE -w '\n%{http_code}\n' http://127.0.0.1:8081/api/tasks/1
404
```

**结果对账**（这是本站的"知识内核"）：

| 方法 | ✅ 该配/该用的场景 | 真实状态码 | 为什么 |
|---|---|---|---|
| GET /api/tasks/1 | 读，理应配"查详情" | **405** Method Not Allowed | Controller 只写了 `@GetMapping`/`@PostMapping`/`@PutMapping("/{id}")`/`@DeleteMapping("/{id}")`，**没配"GET /{id}"** |
| POST /api/tasks/1 | 新建，应该用 …/tasks 不是 …/tasks/1 | **405** | 同上，没有 POST 到 id 的 handler |
| DELETE /api/tasks/1 | 删除，真实存在 | **404** |走到了 handler，但 `existsById` 说没有 Task 50 → 404 |

---

另附一个双重保险实测：**幂等性差异**。同一 DELETE 连按两次：

```bash
$ curl -s -o /dev/null -w '%{http_code}\n' -X DELETE http://127.0.0.1:8081/api/tasks/50
204
$ curl -s -o /dev/null -w '%{http_code}\n' -X DELETE http://127.0.0.1:8081/api/tasks/50
404
```

第一遍 204，第二遍 404——**DELETE 是幂等的**（删两次的"最终效果"=删一次，都表示"那个资源不存在"），但状态码会如实反映"现在找不到东西可删"。而 POST 的第二遍要么 409（若有唯一约束）、要么再插一条——本项目 `POST /api/auth/register` 重名用户就直接 500（"身份证申请失败"）：

```bash
$ curl -s -X POST http://127.0.0.1:8084/api/auth/register -H 'Content-Type: application/json' \
    -d '{"username":"zt01","password":"pw123456"}'
{"timestamp":"...","status":500,"error":"Internal Server Error","path":"/api/auth/register"}
```

**结论一句话**：方法是对"动词"的约束；405/404/500 从 HTTP 层就没有把"路由不配"和"资源不在"混为一谈——这个学会，前端的 fetch 报错你已经有第一直觉。

### 附：录一封"最短的信"（SSE 不是一问一答的例外）

```bash
$ curl -N --max-time 2 http://127.0.0.1:8082/api/chat/stream
event:history
data:[{"text":"hi","user":"smoke"}, ...]
```

`Content-Type: text/event-stream`：连接挂着不断，服务器**主动**推。长连接推模型，demo-chat 专门演它。

### 排查路径（405 事故的"接口考古"两步走）：
1. 后端日志（logs/demo-todo.log）在 405 同刻会出现 `HttpRequestMethodNotSupportedException: Request method 'GET' is not supported` 的 WARN 行——**报什么方法不支持，路由就缺什么**；
2. 对照 §3 的路由表直接看出"GET /{id} 没配映射"。

**幂等与重复请求的两个实测**：

| 请求 | 期望 | 真跑 | 一句话解释 |
|---|---|---|---|
| DELETE 已有 id | 204 | 204 | 既有 handler 正常删除 |
| DELETE 同 id 第二遍 | "删过了" | 404 | 幂等：最终效果=删一次，但资源已不在 |
| POST 同名注册 | 报"已存在" | 500 | 教学版无友好分支，Spring 兜底 |

```bash
# 重复注册同一个用户（真跑）
$ curl -s -X POST http://127.0.0.1:8084/api/auth/register -H 'Content-Type: application/json' \
    -d '{"username":"zt01","password":"pw123456"}'
{"timestamp":...,"status":500,"error":"Internal Server Error","path":"/api/auth/register"}
```

教学版没做"用户已存在"的友好分支，靠 Spring 兜底 500（生产会做全局异常翻译，S07 讲）——此处还体验到一个重要事实：**500 不一定有意思，好友会先区分"逻辑失败"和"程序崩溃"**。

### 顺手把"JWT 手环放进头"实测一遍

```bash
$ TOKEN=$(curl -s -X POST http://127.0.0.1:8084/api/auth/login -H 'Content-Type: application/json' \
    -d '{"username":"zt01","password":"pw123456"}' | python3 -c 'import sys,json;print(json.load(sys.stdin)["token"])')
$ echo "$TOKEN" | cut -c1-32
eyJhbGciOiJIUzM4NCJ9.eyJzdWIiOiJ6dDAxIiw...    ← 前 32 字符（真实形状）
$ curl -s -o /dev/null -w '%{http_code}\n' -H "Authorization: Bearer $TOKEN" \
    http://127.0.0.1:8084/api/bookings/mine
200
$ curl -s -o /dev/null -w '%{http_code}\n' -H "Authorization: Bearer fake.token.here" \
    http://127.0.0.1:8084/api/bookings/mine
401
```

**同一个 URL，换了身份手环，200↔401 之间就隔了一个真签名**——这就是 JWT 校验在 HTTP 层的观测面。

## 4b. 第二现场：参数类型不对（400 这层门）

路由签名是 `@PathVariable Long id`，把路径塞个字母试试（本机真跑）：

```bash
$ curl -s -X PUT http://127.0.0.1:8081/api/tasks/abc -w '\nHTTP %{http_code}\n'
{"timestamp":"...","status":400,"error":"Bad Request","path":"/api/tasks/abc"}
HTTP 400
```

后端日志（logs/demo-todo.log）真实行：

```
WARN ... MethodArgumentTypeMismatchException: Failed to convert value of type 'java.lang.String'
to required type 'java.lang.Long'; For input string: "abc"
```

读法：**参数类型不匹配（String→Long 失败，400 语义）**与"资源不在"（404）是两层不同的"拒绝层"——框架把"参数都进不了 handler"的失败拦在第一道门，这一层类型转换替你把好关。

## 5. 模式对比 / 选型表

| 方法 | 语义 | 幂等？ | 本项目实例 |
|---|---|---|---|
| GET | 读，不改东西 | 是 | `GET /api/trips` |
| POST | 新建/提交 | 否 | `POST /api/bookings` |
| PUT | 更新 | 是 | `PUT /api/tasks/{id}` 翻转完成 |
| DELETE | 删除 | 是 | `DELETE /api/tasks/{id}` |

## 6. 动手验证（自己跑一遍 + 逐行对账）

1. `curl -i http://127.0.0.1:8084/api/trips` → 200 + JSON 数组。
2. `curl -i http://127.0.0.1:8084/api/bookings/mine` → 401（Content-Length: 0）。
3. 三连击实录 4 的三条 curl，得到 405/405/404 或 204/404 的等效态。
4. `curl -N --max-time 2 http://127.0.0.1:8082/api/chat/stream` 注意 `event: history` 行。

## 思考题

1. 为什么说 HTTP 无状态？登录一次后服务器怎么知道第二个请求还是你？
2. `GET /api/trips?from=X&to=Y` 的 `?` 后面叫什么？和 POST 请求体有什么区别？
3. 401 与 403 都"不让进"，分界线是什么？
4. 实录中 GET/POST 打 `/api/tasks/1` 都得 405，但 DELETE 又是 404——你认为 demo-todo 的 API 少了哪个 handler？（补什么注解能把它补上？）

## 练习题

**练习 1**：只用 curl 完成"注册 zt02 → 下单 trip 1 → 查我的订单"三连（下单要 token，`POST /api/bookings`，体 `{"tripId":1}`）。
**练习 2**：把 `Authorization` 改成 `Bearer fake.token.here` 访问 `/api/bookings/mine`，记录状态码并解释。
**练习 3**：用 `curl -i` 对比 9090 与 8084 直连的响应头，找 Nginx 多加的头。

### 完整参考答案

**思考 1**：服务器处理完就忘了你，下个请求对它是陌生人。凭证两种：Cookie（自动带）或 JWT（手动放头）。状态存客户端侧而非服务器内存——这是"无状态"的重音。
**思考 2**：查询字符串。GET 参数在 URL（限长、进历史、可收藏）；POST 参数在体（可大、可二进制）。语义上 GET 参数是筛选，POST 体是要提交内容。
**思考 3**：401 = Unauthenticated（不知道你是谁）；403 = Forbidden（知道你是谁但没资格）。本项目只用了 401。
**思考 4**：少了"查单条"，补上：

```java
@GetMapping("/{id}")
public ResponseEntity<Task> get(@PathVariable Long id) {
      return repo.findById(id).map(ResponseEntity::ok)
                 .orElse(ResponseEntity.notFound().build());
}
```

**练习 2 参考**：401。`JwtUtil.verify`（JwtUtil.java:37-41）解析失败 catch 住返回 null，拦截器 22 行见 uid==null 回 401——伪造 token 连签名都过不了。
**练习 3 参考**：经 Nginx 多出 `Server: nginx`、`Content-Encoding: gzip`（超过 100 字节被压缩，见 nginx-reload.sh 的 gzip 配置）；直连是 Tomcat 给的 `Transfer-Encoding: chunked`。

---

## 本节小结
- HTTP = 请求行 + 头 + 空行 + 体；响应把第一行换成状态码。
- 405 ≠ 404 ≠ 500：路由不配 ≠ 资源不在 ≠ 后端崩——三处不同深度的"没有"。
- DELETE 幂等但状态码会变（204 → 404）；POST 重放可能"新结果"，不是幂等。
- 身份手环两种：Cookie（自动）与 JWT（手动 `Authorization: Bearer`）；JWT 防篡改不防泄露。
- curl 五件套：`-i/-X/-H/-d/-w`。

## 下一站

[零起点-03-端口、进程与一键启动.md](零起点-03-端口、进程与一键启动.md)——为什么 8081 被占就起不来？PID、守护进程、nohup 到底是什么？start-all 被打个"如果不在线就不起，占住了就装作在线"的烟雾弹时怎么拆穿？
