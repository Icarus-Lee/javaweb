# 零起点-02 · HTTP 速成：一封信的格式规范

> 会 C/C++/Python 的你发过 socket 吧？HTTP 就是"约定好格式的 socket 文本流"。这一站把格式拆开，并用 curl 对着活的服务实测每一项。

---

## 🎬 开篇三件套

### 1️⃣ 本站你走在大厅哪一格

你已拿到影视城地图（Z01）。现在你扮**游客本人**，学习怎么写"递给窗口的条子"——HTTP 请求；以及怎么读懂"窗口递回来的回执"——HTTP 响应。本站的道具是 `curl`：一个替你手写条子的命令行浏览器。

### 2️⃣ 本站任务单

1. 手写/读懂一个 HTTP 请求报文和响应报文；
2. 记住 GET/POST/PUT/DELETE 四个方法各干什么；
3. 看见状态码 200/401/404/500 能条件反射说出含义；
4. 理解请求头 `Authorization: Bearer ...`、`Content-Type: application/json`；
5. 知道 Cookie 和 JWT 两种"身份手环"的区别；
6. 用 curl 对本项目真实服务逐项验证。

### 3️⃣ 开工前自查

- [ ] 全栈已起（`bash infra/start-all.sh`，23 断言绿）
- [ ] 终端有 `curl`（`curl --version` 能出版本号）
- [ ] 读过 Z01 的接线表

---

## 🗂 本站名词卡

| 名词 | 人话解释 |
|---|---|
| **HTTP** | HyperText Transfer Protocol，浏览器与服务器的"传话格式"，一问一答、说完即忘（无状态） |
| **URL** | 资源的地址：`协议://主机:端口/路径?参数` |
| **请求行** | 报文第一行：`方法 路径 版本`，如 `GET /api/trips HTTP/1.1` |
| **状态码** | 响应第一行的三位数字：2xx 成功、4xx 你的错、5xx 我的错 |
| **请求头/响应头** | 冒号分隔的元信息行，如 `Content-Type: application/json` |
| **请求体** | POST/PUT 携带的数据，本项目全是 JSON |
| **Cookie** | 服务器发给浏览器、浏览器每次自动带回的小纸条（浏览器专属） |
| **JWT** | JSON Web Token，一串自带签名的令牌，放请求头里证明"我是谁" |
| **curl** | 命令行版浏览器，`-i` 看响应头、`-X` 指定方法、`-H` 加头、`-d` 带请求体 |

---

## 🧠 概念人话

### 一封请求长什么样

你发 `curl http://127.0.0.1:8084/api/trips` 时，真正过网线的是：

```
GET /api/trips HTTP/1.1          ← 请求行：方法 + 路径 + 协议版本
Host: 127.0.0.1:8084             ← 请求头：我要找谁
User-Agent: curl/8.x             ←          我是谁家的浏览器
Accept: */*                      ←          我能看懂什么格式
                                 ← 空行：头结束，下面是体
（GET 没有请求体）
```

服务器回：

```
HTTP/1.1 200                     ← 状态行：版本 + 状态码
Content-Type: application/json   ← 响应头：我给你的东西是什么格式
Transfer-Encoding: chunked       ←          长度未知，分块发送
Date: Mon, 14 Sep 2026 09:53:47 GMT

[{"id":1,"trainNo":"G1024",...}] ← 响应体
```

### 方法 = 动词

| 方法 | 语义 | 本项目实例 |
|---|---|---|
| GET | **读**，不改变任何东西 | `GET /api/trips` 查车次 |
| POST | **新建**/提交 | `POST /api/bookings` 下单 |
| PUT | **更新** | `PUT /api/tasks/{id}` 翻转完成状态 |
| DELETE | **删除** | `DELETE /api/tasks/{id}` |

### 状态码 = 快递面单

| 码 | 含义 | 本项目真实出处 |
|---|---|---|
| 200 | 办好了，东西在体里 | 查车次、登录成功 |
| 204 | 办好了，没东西给你 | `DELETE /api/tasks/{id}` 删成功 |
| 401 | 没登录/令牌无效 | 不带 token 访问 `/api/bookings/mine` |
| 404 | 没这个资源 | 改不存在的任务 |
| 500 | 后端自己出错了 | 给不存在的用户登录（教学版偷懒了） |

> 记法：**4 开头怪自己（请求不对），5 开头怪后端（服务出错）**。

### 两种身份手环：Cookie vs JWT

- **Cookie**：登录后服务器发 `Set-Cookie: session=abc123`，浏览器存起来，之后每次请求**自动**带回。只在浏览器里好使。
- **JWT**：登录成功后后端发一串签过名的令牌，客户端自己存，每次请求放进头里 `Authorization: Bearer <token>`。不依赖浏览器，App/小程序/脚本都能用。

本项目两个站点都用 **JWT**（因为要用 curl 和 Python 模拟客户端，Cookie 反而麻烦）。token 长这样（真实输出截取）：

```
eyJhbGciOiJIUzM4NCJ9.eyJzdWIiOiJ6dDAxIiw...
```

三段用 `.` 隔开：头部.载荷.签名。载荷里明文写着你是谁（base64 可解，**别往里放密码**），签名保证你没改过它。

---

## 🔍 真实代码走查

### 谁在发 401：AuthInterceptor

`backend/train/src/main/java/com/javaweb/train/security/AuthInterceptor.java`：

```java
18:            resp.setStatus(401);          // 没带 Authorization 头 → 401
22:        if (uid == null) { resp.setStatus(401); return false; }
```

拦截器像大厅安检口：请求进来先验 JWT（`JwtUtil.verify`，见 `security/JwtUtil.java:37`），验出 uid 就盖个章放进请求属性，验不出直接回 401，根本到不了业务代码。

### 谁在发 token：AuthController

`backend/train/src/main/java/com/javaweb/train/controller/AuthController.java`：

```java
46:    @PostMapping("/login")
47:    public Map<String, Object> login(@RequestBody LoginReq req) {
48:        User u = users.findByUsername(req.username())
49:                .orElseThrow(() -> new IllegalStateException("用户不存在"));
50:        if (!u.passwordHash.equals(sha256("javaweb-" + req.password())))
51:                throw new IllegalStateException("密码错误");
52:        String token = jwt.issue(u.id, u.username);
53:        return Map.of("token", token, "username", u.username);
54:    }
```

第 52 行签发 token，第 53 行把它装进 JSON 响应体。注意密码**不存明文**：第 50 行比的是 sha256 哈希。

---

## 动手验证（全部一行命令，真实输出）

### 实验 1：GET + 200

```bash
$ curl -i http://127.0.0.1:8084/api/trips
HTTP/1.1 200
Content-Type: application/json
Transfer-Encoding: chunked
Date: Mon, 14 Sep 2026 09:53:47 GMT

[{"id":1,"trainNo":"G1024","from":"上海虹桥","to":"苏州","depart":"08:00",
  "arrive":"08:35","price":42,"total":3,"stock":3}, ...]
```

一个 JSON 数组，每趟车一个对象。`-i` 让 curl 把响应头也打出来。

### 实验 2：401——不带手环进贵宾厅

```bash
$ curl -i http://127.0.0.1:8084/api/bookings/mine
HTTP/1.1 401
Content-Length: 0
```

状态码 401，体是空的——安检口直接把你请出去了。

### 实验 3：注册 → 登录 → 带手环再进

```bash
$ curl -X POST http://127.0.0.1:8084/api/auth/register \
    -H 'Content-Type: application/json' \
    -d '{"username":"zt01","password":"pw123456"}'
{"username":"zt01","id":6}

$ TOKEN=$(curl -s -X POST http://127.0.0.1:8084/api/auth/login \
    -H 'Content-Type: application/json' \
    -d '{"username":"zt01","password":"pw123456"}' \
    | python3 -c 'import sys,json;print(json.load(sys.stdin)["token"])')

$ curl -i -H "Authorization: Bearer $TOKEN" http://127.0.0.1:8084/api/bookings/mine
HTTP/1.1 200
Content-Type: application/json

[]
```

三步走：注册（POST + JSON 体）→ 登录拿 token → 把 token 放进 `Authorization` 头。同一个 URL，带上手环就从 401 变 200。

### 实验 4：排练厅的 PUT 与 DELETE

```bash
$ curl -X POST http://127.0.0.1:8081/api/tasks -H 'Content-Type: application/json' -d '{"title":"学HTTP"}'
{"id":1,"title":"学HTTP","done":false}

$ curl -X PUT http://127.0.0.1:8081/api/tasks/1
{"id":1,"title":"学HTTP","done":true}

$ curl -i -X DELETE http://127.0.0.1:8081/api/tasks/1
HTTP/1.1 204
```

观察 204：成功但无响应体。

### 实验 5：SSE——不是"一问一答"的例外

```bash
$ curl -N --max-time 2 http://127.0.0.1:8082/api/chat/stream
event:history
data:[{"text":"hi","user":"smoke"}, ...]
```

`Content-Type: text/event-stream`：连接保持不断，服务器**主动**持续推数据。这是 HTTP 上的长连接推模型，demo-chat 排练厅专门演它。

---

## 思考题

1. 为什么说 HTTP 是"无状态"的？登录一次之后，服务器怎么知道第二个请求还是你？
2. `GET /api/trips?from=上海虹桥&to=苏州` 里 `?` 后面部分叫什么？它和 POST 的请求体有什么区别？
3. 401 和 403 都表示"不让进"，区别是什么？
4. JWT 的载荷是 base64 明文，为什么不算泄密？它防的是什么？

## 练习题

**练习 1**：只用 curl，完成"注册 zt02 → 下单 trip 1 → 查我的订单"三连（下单需要 token，路径 `POST /api/bookings`，体 `{"tripId":1}`）。

**练习 2**：把 `Authorization` 头的值改成 `Bearer fake.token.here` 再访问 `/api/bookings/mine`，记录状态码并解释。

**练习 3**：用 `curl -i` 观察 `GET http://127.0.0.1:9090/apitrain/trips` 与直连 8084 的响应头差异，找出 Nginx 添加了哪些头。

### 完整参考答案

**思考题 1**：服务器处理完一个请求就把你忘了，下一个请求对它而言是陌生人。所以需要"凭证"：Cookie（浏览器自动带）或 JWT（手动放头里）。服务器每次验凭证还原你的身份——状态存在客户端手里，而非服务器内存里。

**思考题 2**：查询字符串（query string）。GET 的参数在 URL 里（有长度限制、进浏览器历史、能被收藏）；POST 的参数在请求体里（可大、可二进制、不进历史）。语义上：GET 参数是"筛选条件"，POST 体是"要提交的内容"。

**思考题 3**：401 = Unauthenticated，"我不知道你是谁"（没登录/令牌无效）；403 = Forbidden，"我知道你是谁，但你没资格"（已登录但权限不够）。本项目只用到了 401。

**思考题 4**：JWT 的目的是**防篡改**不是**保密**。签名让服务器能发现载荷被改过（改了签名就对不上）；任何人都能 base64 解出载荷内容，所以用户名、过期时间可以放，密码绝不能放。

**练习 1 参考**：

```bash
$ curl -s -X POST http://127.0.0.1:8084/api/auth/register -H 'Content-Type: application/json' \
    -d '{"username":"zt02","password":"pw123456"}'
{"username":"zt02","id":7}
$ TOKEN=$(curl -s -X POST http://127.0.0.1:8084/api/auth/login -H 'Content-Type: application/json' \
    -d '{"username":"zt02","password":"pw123456"}' | python3 -c 'import sys,json;print(json.load(sys.stdin)["token"])')
$ curl -s -X POST http://127.0.0.1:8084/api/bookings -H "Authorization: Bearer $TOKEN" \
    -H 'Content-Type: application/json' -d '{"tripId":1}'
{"id":1,"orderNo":"3f2a...","userId":7,"tripId":1,"seatNo":1,"status":"UNPAID",...}
$ curl -s -H "Authorization: Bearer $TOKEN" http://127.0.0.1:8084/api/bookings/mine
[{"id":1,...,"status":"UNPAID",...}]
```

**练习 2**：401。`JwtUtil.verify`（JwtUtil.java:37-41）解析失败时 catch 住返回 null，拦截器第 22 行看到 uid==null 就回 401。伪造 token 连签名都过不了。

**练习 3**：经 Nginx 的响应会多出 `Server: nginx`、`Content-Encoding: gzip`（JSON 超过 100 字节被压缩，见 nginx-reload.sh 第 38-40 行 gzip 配置）等头；直连 8084 则是 `Transfer-Encoding: chunked` 由 Tomcat 直接给出。

---

## 本节小结
- HTTP = 请求行 + 头 + 空行 + 体；响应同理，第一行换成状态码。
- GET 读 / POST 增 / PUT 改 / DELETE 删；2xx 好、4xx 怪请求、5xx 怪后端。
- 身份手环两种：Cookie（浏览器自动）与 JWT（手动放 `Authorization: Bearer`）。
- curl 四件套：`-i` 看头、`-X` 定方法、`-H` 加头、`-d` 带体。
- 本项目真实体验了 200/204/401/500 与 SSE 长连接。

## 下一站

[零起点-03-端口、进程与一键启动.md](零起点-03-端口、进程与一键启动.md)——为什么 8081 被占就起不来？PID、守护进程、nohup 到底是什么？把 start-all.sh 逐行拆给你看。
