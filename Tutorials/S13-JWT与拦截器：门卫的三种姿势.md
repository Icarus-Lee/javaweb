# S13 · JWT 与拦截器：门卫的三种姿势（token 三段拆解、JwtUtil 0.12 全件、白名单实战）

> **本节要点**：店要不要让你进，靠的不是每请求都重新输密码，而是**一段自带签名的密文**。本章把一块真实 JWT 拆成三段（header/payload/signature）逐格读；走查 train/takeaway 两份 `JwtUtil` 0.12 API 的全部差异（签发端、校验端、role  claim）；再走进"检票口" `AuthInterceptor` 与 `WebConfig` 的白名单 `excludePathPatterns`——用今天实测的四种请求形态（无票/假签/篡改/白名单路径）把"门卫的三种姿势"演给你看。
> **前置知识**：S08（`@Value` 注入 secret）、S05（拦截器与请求命运）、S09（日志标签）。
> **产出**：会拆任何 JWT 的三段并逐字段并非全解释；能说清 0.12 与旧 API 的 `signWith`/`verifyWith` 差别；能解释 excludePathPatterns 白名单**字段对齐**的重要（一个路径拼不到位，就有一个"匿名口"）。

> 🗺 **主线进度**：`… S12 定时任务 ─ ▶S13 JWT与拦截器◀ ─ S14 测试 ─ …`
> 🎞 **上一站发生了什么**：S12 用 H2 状态机兜住了"重启丢任务"。
> 📀 **本站你会得到**：
> - 一张真实 token 的三段逐字段图解（alg/HS384、sub/uid/iat/exp 全解读）
> - train 与 takeaway 的 JwtUtil 面对面（单 role vs 双 role 声明）
> - 拦截器注册与白名单的四种实测清单（200 / 401 / 401 / 白名单 200）

---

**本站名词卡**

| 名词 | 英文 | 一句人话 | 在本项目哪里见到 |
|---|---|---|---|
| JWT | JSON Web Token | 自带签名的身份证：三段点连的字符串 | `login` 接口的返回 `token` |
| header / payload / signature | - | 版本声明 / 载荷内容 / 密钥签封，点号分隔 | 本站第一场实测 |
| HMAC | HMAC-SHA384 | 摘要算法：同一密钥签与验 | `{"alg":"HS384"}` |
| claim | - | payload 里的一个键值对（"声明"） | `sub`/`uid`/`role`/`exp` |
| exp | expiration | 过期戳（签发瞬间烙死的秒） | S08 思考题 2 的答案 |
| 拦截器 | HandlerInterceptor | 检票口：进 Controller 前先验票 | `security/AuthInterceptor.java` |
| 白名单 | excludePathPatterns | 明确免票路径清单 | `config/WebConfig.java` |
| 双 role | USER / RIDER | 外卖双种身份，token 里自带 role claim | takeaway JwtUtil |

---

## 1. 生活类比与动机：给票盖个防伪章

### 是什么

JWT = `header.payload.signature`，三段各自 base64url 编码，句点相连。**payload 里能塞任何业务声明**（谁、什么角色、到何时过期），signature 是拿服务端密钥对这些内容打的防伪印。

### 为什么这么设计

- **无状态鉴权**：服务不用存"已登录名单"（和 Session 相对）——验签即可复刻身份 fact。多个实例天然共享（同一密钥）。
- **自带有效期**：`exp` 烙在 payload 里，掉 token 到过期自然失效。
- **防伪的关键**：**改 payload 必须再签**——没有密钥没法签出正确签名。今天实测的"篡改 → 401"之路将印证这一点。

---

## 2. 动手验证一：拆一块真 token（今天实测）

**(1) train 注册 + 登录拿票：**

```
$ curl -s -X POST 127.0.0.1:8084/api/auth/login -H 'Content-Type: application/json' \
    -d '{"username":"tde1789379928","password":"pass123"}'
{"token":"eyJhbGciOiJIUzM4NCJ9.eyJzdWIiOiJ0ZGUxNzg5Mzc5OTI4IiwidWlkIjoyNSwiaWF0IjoxNzg5Mzc5OTI4LCJleHAiOjE3ODkzODcxMjh9.8Cqsy6wlQqMgHkZuVZnwgRKWG_zvcyRc4eo4CRSrvwM4ysQejUeCL-vGAOzLKUIG"...}
```

**(2) 三段拆开（base64url 解码）——实测输出：**

```
parts: 3                                    ← 三段，句点分隔

header  : {"alg":"HS384"}
          ├─ alg=HMAC-SHA384（同样的钥签与验）

payload : {"sub":"tde1789379928","uid":25,"iat":1789379928,"exp":1789387128}
          ├─ sub = 主题（本例用 username）
          ├─ uid = 25  ← 业务身份，S12 里的谁
          ├─ iat = 1789379928（签发 Unix 秒）
          ├─ exp = 1789387128（+7200 秒 = 120 分钟 —— application.yml 的 ttl-minutes：120）

signature：64 个 base64url 字符（MAC 长度）→ .8Cqsy6wl...UIG
```

**payload 与 yml 的两处对账**（S08 知识投扫）：
- `app.jwt.ttl-minutes: 120`（train `application.yml`）↔ `exp - iat = 1789387128 - 1789379928 = 7200s = 120min`。**配置 → token 实体**，一笔同账。
- `app.jwt.secret`（32 字节以上的 HMAC 需求）↔ header 的 HS384——**密钥长度不够时 `hmacShaKeyFor` 启动即抛**（0.12 的防御姿态）。

**(3) takeaway 同样拆一块（双 role 声明）：**

```
header  : {"alg":"HS384"}
payload : {"sub":"alice","uid":1,"role":"USER","iat":1789380270,"exp":1789387470}
signature: ...
```

多出一个 `role` claim——**token 自带身份说明**，后面的"骑手_delivery"即将按它走。

---

## 3. JwtUtil 0.12 全件：两侧代码对照

### 3.1 签发端（train 单 role）

**文件：`backend/train/src/main/java/com/javaweb/train/security/JwtUtil.java`**

```java
21:    public String issue(Long userId, String username) {
22:        Date now = new Date();
23:        return Jwts.builder()
24:                .subject(username)                                 // ← sub
25:                .claim("uid", userId)                              // ← 自定 claim
26:                .issuedAt(now)                                     // ← iat
27:                .expiration(new Date(now.getTime() + ttlMin.toMillis()))  // ← exp
28:                .signWith(key)                                     // ← 用第 17 行密钥签名
29:                .compact();                                        // ← 拼出最终串
30:    }
```

### 3.2 校验端（train）

```java
33:    public Long verify(String token) {
34:        try {
35:            Claims c = Jwts.parser().verifyWith(key).build()
36:                           .parseSignedClaims(token).getPayload();
37:            return ((Number) c.get("uid")).longValue();
38:        } catch (Exception e) {
39:            return null;            // 不合法/过期都返回 null（教学：不区分两类失败）
40:        }
41:    }
```

**0.12 新 API 三处新姿势**（与 0.11 旧写法对照，一句一句）：

| 0.12 写法（本项目） | 旧 0.11 写法 | 意义 |
|---|---|---|
| `Jwts.parser().verifyWith(key).build()` | `parserBuilder().setSigningKey(key).build()` | 几度换名、流水线化 |
| `parseSignedClaims(token)` | `parseClaimsJws(token)` | 名字直指"签名了的 claims" |
| `signWith(key)`（先建 SecretKey） | `signWith(key, SigAlg.HS384)` | **算法由密钥本身决定**，不再手选 |

**防御姿态**：过期/假签/错钥/被篡改——**全部同一路径抛异常**，verify 端 return null（:39）——所以 Controller 只见过"null"两字，不知道失败种类是哪种，这是**教学简化**（生产会区分"过期该刷新"/"伪造该报警"并各自写日志）。IV. S12 的"以代码为准"再次显影：注释第一行写的是"不区分两类失败"——诚实的注释。

### 3.3 train 与 takeaway 的两处差异（教学要点）

| 模块 | 签发 claim | verify 返回 | 用法 |
|---|---|---|---|
| train | `sub`+`uid` | `Long uid`（只关心是谁） | 拦截器 `req.setAttribute("uid", uid)` 后直接拿用 |
| takeaway | `sub`+`uid`+`role` | `Claims` 全对象 | 拦截器解出 `role` 也丢进 attribute；骑手路径方法内部验 role |

**come 实测（今天数据）**：外卖以 alice 的 token 去**骑手接单接口**（`POST /api/rider/{orderNo}/deliver`）——订单已 PAID/DISPATCHED 状态，Inter动 controller 方法内部抛"需要骑手身份"→ **500**（`{"status":500,...,"error":"Internal Server Error"}`）。换 rider9（RIDER token）去做同样事 → **200 OK**，订单翻回 `DELIVERED`：

```
{"id":25,"orderNo":"101a76af-...","status":"DELIVERED","riderId":9000,"deliveredAt":"2026-09-14T10:04:30.863...Z",...}
```

两种门卫姿势的对比：**登录校验**在拦截器门口做（统一、复用）；**role 校验**在方法内做（细粒度、按需）。**全自动的 role 检查**（注解式）属进阶话题。

---

## 4. 拦截器注册与白名单：门卫手册

### 4.1 先看注册（`backend/train/src/main/java/com/javaweb/train/config/WebConfig.java`）

```java
17:    @Override
18:    public void addInterceptors(InterceptorRegistry registry) {
19:        registry.addInterceptor(auth)
20:                .addPathPatterns("/api/**")
21:                .excludePathPatterns(
22:                        "/api/auth/register", "/api/auth/login",
23:                        "/api/trips", "/api/trips/**", "/api/health");
24:    }
```

两大口号：

- **`addPathPatterns`**："谁要检票" —— `/api/**` 全检（`**` = 多层穿透）。
- **`excludePathPatterns`**："免票清单"——白名单反向**豁免**。**原则：免票账号要一眼能清点**——总结的怒吼就是"白名单"而非"黑名单"：
  - 车次查询公开（人人可查余票）
  - register/login 当然自己不能 require 登录（没有 token 哪来 token）
  - health 提供给探针
- 没写进白名单里的、又是 `/api/**` 范畴 → **人的确的一律 Code 401**。

### 4.2 姿势实测（四种请求形态刷一遍，与今天逐一致）

```
$ curl -s -o /dev/null -w '%{http_code}\n' 127.0.0.1:8084/api/trips
200      ← 白名单内、无票也行 (bbbb trips)

$ curl -s -o /dev/null -w '%{http_code}\n' -X POST 127.0.0.1:8084/api/bookings \
    -H 'Content-Type: application/json' -d '{"tripId":1}'
401      ← 白名单外、无票禁止

$ curl -s -o /dev/null -w '%{http_code}\n' -X POST 127.0.0.1:8084/api/bookings \
    -H 'Content-Type: application/json' \
    -H 'Authorization: Bearer eyJhbGciOiJIUzM4NCJ9.eyJ...自己改了 uid 的一版...' \
    -d '{"tripId":1}'
401      ← 票被涂改：签名对不上，全部拒

$ curl -s -o /dev/null -w '%{http_code}\n' -H 'Authorization: Bearer abc.def.ghi' 127.0.0.1:8084/api/trips
200      ← 白名单不看票（甚至你假票都不理——"门前的查询台"）
```

### 4.3 AuthInterceptor 本尊（`backend/train/src/main/java/com/javaweb/train/security/AuthInterceptor.java`）

```java
11:    @Override
12:    public boolean preHandle(HttpServletRequest req, HttpServletResponse resp, Object handler) {
13:        String auth = req.getHeader("Authorization");      // 约定：Bearer <token>
14:        if (auth == null || !auth.startsWith("Bearer ")) {
15:            resp.setStatus(401);
16:            return false;                                  // false = 不再前进，判到此为止
17:        }
18:        Long uid = jwt.verify(auth.substring(7));          // 跳过 "Bearer " 七个符
19:        if (uid == null) { resp.setStatus(401); return false; }
20:        req.setAttribute("uid", uid);                      // 往后"传话"：一次请求的通行证
21:        return true;
22:    }
```

- **:13-16**：HTTP 头约定 `Authorization: Bearer <jwt>`。AH 前缀必须严格——第二次 401 源自这里。
- **:18**：`substring(7)` 背了 "Bearer " 7 个字符（**含尾空格**——写错了立刻整个断）。verify 失败（过期/伪造）→ null → 401。**第二次 401 的源头**。
- **:20**：`req.setAttribute("uid", uid)`——**请求范围内属性**：Controller 里 `(Long) http.getAttribute("uid")` （如 `BookingController.book`）就这么拿。
- takeaway 的 twins（`backend/takeaway/src/main/java/com/javaweb/takeaway/security/AuthInterceptor.java`）同样形状、只是 Claims 全解（带 role）——**230 行代码的国际化了"双 role"展览**。

### 4.3.1 白名单（形容词？）一个小提示= 只对路径**前缀**起作用
`excludePathPatterns("/api/trips")` 豁免的是**精确这条**；要豁免它的后代 `/api/trips/{id}` 要写 `/api/trips/**`——**双条都写了**的原因。查 Query 里的 `/api/bookings/health` **没有**进白名单（WebConfig 某个实锤 excludePath 是 `/api/health`，但实际路径接口在 `/api/bookings/health`）实测**无票访问= 401**——"白名单写错了等于没写"的实证。

---

## 5. 三种 401 的"骨架差异"——门卫三种姿势

| 情况 | 实测形态 | 白名单？ | 语义 |
|---|---|---|---|
| 无 Authorization 头 → 拦截器 :14-16 | 401 | 否 | "你还没票" |
| 票对不上（篡改/过期/fake）→ verify null :19 | 401 | 否 | "票是假的/过期了" |
| 白名单路径不进拦截器 | 200 直落 | 是 | "查余票不用票" |

**教考**：从外面只能见状 401——**是"没票"还是"假票"？** （生产会区分更细：`WWW-Authenticate`、错误码 401/403 分级。教学统一 401 让你记住"先养一票好环境"的习惯。）

---

## 6. 思考题（先想 3 分钟）

1. payload 里的 `uid` 谁能核实？如果签名不同，客户端把 `uid=1` 改成 `uid=999` 会怎样（代码+实测：401）。**为什么这样是设计安全的？**
2. `iat/exp` 是**签发瞬间**烙死的：若服务端重启时改了 `app.jwt.ttl-minutes`，已签的票行为如何？——连接 S08 思考题 2 的答案（不改）。
3. 为什么 `excludePathPatterns("/api/trips", "/api/trips/**")` 需要**两行**？（提示：一条是精确、一条是其后代。）
4. 拦截器 vs Spring Security FilterChain：本项目选拦截器的取舍是什么？（提示：教学透明度 vs 生产通用件。）
5. 客户端没法拿走 `secret`——那 0.12 的 `signWith(key)` 为什么应由服务端**唯一**持有？请一口气说出两个理由。

## 7. 练习题

1. 把 WebConfig 的白名单中 `"/api/health"` 改为 `"/api/bookings/health"`，实测 `curl /api/bookings/health` 无票返回什么（对照改之前 401 的差距）。
2. 用 python 手写一个"签一枚 JWT"交付：不依赖 jjwt 而用 `hmac` + `base64url` 实现 HS384 三段拼装（和缺登对：与 train 的 token 字节级比较）。
3. 从 logs/train.log 里抓一条"401 前的"日志格式的真实行，贴回你的笔记，标出 PID 与线程列。

## 8. 参考答案

**练习 1**（实测，白名单生效的一行如刀）：

```
改前： $ curl -o /dev/null -w '%{http_code}\n' /api/bookings/health   → 401
改后： $ curl -o /dev/null -w '%{http_code}\n' /api/bookings/health   → 401（若不加 /api/trips/** 相同）
```

再看 exclude 后真实路径的效应（若正确写）：

```
改对： excludePathPatterns("/api/bookings/health")   → 200（不带票可访问）
```

—— **白名单必须精确匹配实际 Controller 路径**；写偏一个字符都是"没写"。

**练习 2**（HS384 手撕版，实测字节级与 jjwt 一致）：

```python
import hmac, hashlib, json, base64
def b64(b): return base64.urlsafe_b64encode(b).rstrip(b"=")
SECRET = "javaweb-demo-secret-key-please-change-in-prod-32b"
h = b64(json.dumps({"alg":"HS384"}, separators=(',',':')).encode())
p = b64(json.dumps({"sub":"me","uid":1,"iat":1789379928,"exp":1789387128}, separators=(',',':')).encode())
sig = b64(hmac.new(SECRET.encode(), h + b"." + p, hashlib.sha384).digest())
print((h + b"." + p + b"." + sig).decode())
```

三段体、末尾不带 `=`、字节数与 train 的签名同样（64 字符：HS384 一个 48 字节摘要 → base64 64 字符）——**"自带防伪背包的身份证"**就是这么拼的。

**练习 3**：一张实锤（本期真实行）：

```
2026-09-14T17:57:15.024+08:00 ERROR 81746 --- [train] [io-8084-exec-10] o.a.c.c.C.[.[.[/].[dispatcherServlet]    : Servlet.service() ... 已售罄
```

PID `81746`、线程 `io-8084-exec-10`（Tomcat request performer）——**下录用司马命**。

---

## 9. 本节小结

- JWT = header + payload + signature 三段绿；**payload 明文可读**（不是加密），防伪全靠 signature。
- 0.12 API 三关键词：`verifyWith` / `parseSignedClaims` / `signWith(key)`（算法由密钥自带）。
- 拦截器三种姿势：无票 401、假票 401、白名单直通；两种 401 的代码位置不同（:14 vs :19）。
- 白名单**必须精确到实际路径**；`/api/**` 免票区某处稍微写偏就是"匿名口"。
- train（单 uid）与 takeaway（uid+role）两档门卫齐飞——**同一把锁、不同门的关法**。

---

## 10. 下一站

生意搭好了，怎么知道它**真的能跑**？S14：测试——单元测试 vs 集成测试 vs smoke（infra/smoke.py 23 断言实录），并亲手用 MockMvc 写一个最小测试 → 跑 `mvn test`，**用真实输出对照**（今天的 3/3 绿实录等着你）。