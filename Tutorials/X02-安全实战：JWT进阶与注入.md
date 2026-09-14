# X02 · 安全实战：密码哈希、SQL 注入与 JWT 重放实验

> **本节要点**：安全不是加一个"登录"就完事——**存密码的方式、拼 SQL 的方式、验 token 的方式**，每一处都可能成为突破口。本章三场实验全部基于真实代码：① sha256 与 BCrypt 的对照（为什么"加了盐前缀的 sha256"仍不够）；② 用恶意用户名实弹打登录接口，看 JPA 参数化如何天然免疫 SQL 注入；③ 招牌实验——**篡改 JWT payload 但保留原签名，看 401 如何当场落下**。
> **前置知识**：S13（JWT 与拦截器）、W01（users 表与 passwordHash）、X01（服务在线，smoke 全绿）。
> **产出**：能说清 sha256(明文) 与 BCrypt 的三条差距；能解释"参数化查询为什么从原理上免疫注入"；能完整复现 token 篡改→401 实验链。

> 🗺 **主线进度**：`… X01 部署手册 ─ ▶X02 安全实战◀ ─ X03 收官 ─ …`
> 🎞 **上一站发生了什么**：X01 把全栈部署上线并 22/22 验收。
> 📀 **本站你会得到**：
> - 三组真实实测输出（注入弹、篡改 token、四种鉴权形态）
> - 密码哈希选型对照表（sha256 vs BCrypt/argon2）
> - XSS/CSRF 快三问（一分钟一个）

---

**本站名词卡**

| 名词 | 英文 | 一句人话 | 在本项目哪里见到 |
|---|---|---|---|
| 密码哈希 | password hashing | 库里只存"密码的指纹"，不存密码本身 | `users.passwordHash` |
| 加盐 | salt | 每人掺一点随机料，防彩虹表 | 本项目用固定前缀（不足，见正文） |
| BCrypt | - | 慢哈希：算一次故意要花 0.1 秒 | 对照方案（练习 F 落地） |
| SQL 注入 | SQL injection | 用户输入被拼进 SQL 变成代码 | 实验二的主角 |
| 参数化 | parameterized query | SQL 模板与参数分家，输入永远只是数据 | JPA 的默认行为 |
| XSS | cross-site scripting | 恶意脚本借你的页面在别人浏览器里跑 | 快三问 1 |
| CSRF | cross-site request forgery | 借用户已登录的身份发伪造请求 | 快三问 2 |
| 重放 | replay | 拿偷来的凭证冒充原主 | 实验三的主角 |

---

## 1. 生活类比与动机：三把锁

把安全想象成仓库的三道锁：

- **密码哈希**是仓库**登记簿的保管方式**——登记簿被偷，小偷也不能直接冒充任何人；
- **SQL 注入防御**是**门牌号的书写规范**——访客说什么都得先抄在纸上再递进去，而不是让他喊话指挥你翻牌；
- **JWT 验签**是**出入证的防伪线**——证可以捡到（重放），但**改名字的证一验就穿**。

本章按"登记簿 → 门牌 → 出入证"的顺序，各开一场实验。

---

## 2. 密码哈希：sha256 的不够与 BCrypt 的够

### 2.1 现状走查（AuthController.java:38, 48, 53-59）

```java
u.passwordHash = sha256("takeaway-" + req.password());     // 注册：固定前缀当"盐"
...
if (!u.passwordHash.equals(sha256("takeaway-" + req.password())))   // 登录：同法再算一遍
    throw new IllegalStateException("密码错误");
```

这个方案有三层防御意图：不存明文 ✅；前缀让"纯 sha256 彩虹表"失效 ✅；比较用 equals 于十六进制串，无时序大差 ✅（严格说 equals 有时序侧信道，见思考题 3）。**但教学要诚实：它仍然不够。**

### 2.2 sha256 方案的三宗罪

| 问题 | 原理 | 后果 |
|---|---|---|
| **算得太快** | 现代 GPU 每秒可算数十亿次 sha256 | 拖库后爆破 123456 这种密码瞬间完成 |
| **盐是固定的** | 所有用户共享 "takeaway-" 前缀 | 两个密码相同的用户 hash 相同（用 hash 反推谁是同密码） |
| **没有成本参数** | 算法升级=全量迁移 | 硬件变快，防御原地踏步 |

### 2.3 BCrypt 的对症下药

```java
// 注册
String hash = BCryptPasswordEncoder().encode(rawPassword);   // 内置随机盐+成本因子
// 登录
boolean ok = encoder.matches(rawPassword, storedHash);       // 从 hash 里解析盐再验
```

| | sha256(+固定前缀) | BCrypt |
|---|---|---|
| 速度 | 纳秒级（对防御者是灾难） | 故意 ~100ms（成本因子可调） |
| 盐 | 无/固定 | 每用户随机盐，**编码进 hash 字符串本身** |
| 抗拖库爆破 | 弱 | 100ms × 千万次 = 数月 |
| 库里长啥样 | 64 位十六进制 | `$2a$10$N9qo8uLOickgx2ZMRZoMye...`（算法+成本+盐+hash 四段） |

**实测对比**（本机，一次 encode 的真实耗时感受）：

```
$ python3 -c "
import hashlib, time
t=time.time()
for _ in range(100000): hashlib.sha256(b'takeaway-123456').hexdigest()
print('sha256 10万次: %.3fs' % (time.time()-t))
"
sha256 10万次: 0.031s          ← 每秒 300 万次，GPU 上还要快几个量级
# BCrypt（Java）成本因子 10：单次约 80-100ms——差 8 个数量级
```

结论一句话：**密码哈希的考点不是"不可逆"，而是"不可批量逆"**——慢就是防御。

---

## 3. SQL 注入：JPA 为什么天然免疫

### 3.1 原理速成

注入成立的唯一条件：**用户输入被拼进 SQL 字符串再执行**。

```java
// 反面教材（千万别写）
String sql = "SELECT * FROM users WHERE username = '" + input + "'";
input = "alice' OR 1=1--"   →  SELECT * FROM users WHERE username = 'alice' OR 1=1--'
                               （-- 后全被注释，1=1 恒真 → 拖走全表）
```

参数化的本质：**SQL 模板与参数分两批发给数据库**，参数永远以"数据"身份出现，数据库驱动保证它不会被当作 SQL 语法解析——`'` 引号再嚣张也只是字符，不是结构。

### 3.2 本项目的免疫点（全部走查）

所有查询走 Spring Data JPA 的派生方法，底层是 JDBC PreparedStatement：

```java
// UserRepo
Optional<User> findByUsername(String username);      // ← 参数化
// OrderRepo / DishRepo 同族
```

方法名翻成 SQL 后，`username` 绑定为 `?1` 占位符——**代码里根本不存在"拼接"这个动作**，注入面在结构上不存在。

### 3.3 实验二：实弹打一遍（2026-09-14 实测）

```
$ curl -s -X POST 127.0.0.1:8085/api/auth/login -H 'Content-Type: application/json' \
    -d '{"username":"alice'"'"' OR 1=1--","password":"x"}'
{"timestamp":"2026-09-14T10:25:58.843+00:00","status":500,
 "error":"Internal Server Error","path":"/api/auth/login"}

$ grep -a "找不到\|用户不存在" logs/takeaway.log | tail -1
... IllegalStateException: 用户不存在        ← 注意！是"查无此人"，不是"查到了全表"
```

**逐层解读这个 500**：
1. 字符串 `' OR 1=1--` 被当作**一个完整的用户名**去匹配（参数化的直接证据）；
2. 数据库老老实实找叫这个名字的（不存在的）人 → 返回空 → `orElseThrow` 抛"用户不存在"；
3. 全局异常处理器兜底成 500（W04 讲过这个兜底）。
4. **如果它真能拖库**，你会看到的是 200 + 用户数据——所以这个 500 恰恰是安全的证明。

对照组（普通错密码，同样的 500 同样的"密码错误"路径）：

```
$ curl -s -X POST .../api/auth/login -d '{"username":"alice","password":"wrong"}'
{"status":500,"error":"Internal Server Error",...}   ← 日志：密码错误
```

**注意一个真实的安全瑕疵**：这两种失败对外表现完全一样（都是 500），但日志区分了"用户不存在"和"密码错误"——对外最好统一成一句"用户名或密码错误"，别帮攻击者**枚举有效用户名**（本项目的 500 泄露度低但方向不对，练习 F 修）。

---

## 4. 招牌实验三：篡改 token 重放 → 401 四连

### 4.1 实验设计

拿到 alice 的合法 token 后，**换 payload（uid 改成 999、role 改成 ADMIN）但保留原签名**——模拟"攻击者捡到 token 想改身份"。若服务端不验签或验签有洞，这里就是提权入口。

### 4.2 真实实测（2026-09-14）

```
$ TOK=eyJhbGciOiJIUzM4NCJ9.eyJzdWIiOiJhbGljZSIsInVpZCI6MSwicm9sZSI6IlVTRVIiLCJpYXQiOjE3ODkzODE1MzIsImV4cCI6MTc4OTM4ODczMn0.ikI43BzYM58OwL25mF5yhqUI7eHSPXMG-8IHY9s5oWYGNgWEiSmcNkCCkUvh-HIK

$ python3 -c "import base64,json; t='$TOK'.split('.');
  print(json.loads(base64.urlsafe_b64decode(t[1]+'==')))
  {"sub":"alice","uid":1,"role":"USER","iat":1789381532,"exp":1789388732}"

# 伪造 payload（uid=999, role=ADMIN），旧签名原样保留
$ FAKE="eyJhbGciOiJIUzM4NCJ9.$(新的payload段).ikI43BzYM58OwL25mF5yhqUI7eHSPXMG-8IHY9s5oWYGNgWEiSmcNkCCkUvh-HIK"

$ curl -s -o /dev/null -w "换payload留旧签名: %{http_code}\n" \
    127.0.0.1:8085/api/orders/mine -H "Authorization: Bearer $FAKE"
换payload留旧签名: 401                ← 验签失败，身份伪造被拦截
```

### 4.3 四种鉴权形态全测（同一接口 /api/orders/mine）

```
正常 token:                200      ← 合法身份
无 token:                  401      ← AuthInterceptor.java:19（没带 Bearer）
假 token（假签名）:        401      ← JwtUtil.verify 返回 null → 401
错误 scheme（Basic）:      401      ← 不以 Bearer 开头，直接拒
篡改 payload 留旧签名:     401      ← 本实验主角：签名对不上 payload
```

**原理回顾**（S13 已拆三段结构）：签名 = HMAC(密钥, header+payload)。改了 payload 一个比特，重算的 HMAC 就面目全非——服务端用**自己保管的密钥**重算比对，对不上即 401。**密钥不出网**，所以客户端永远无法为伪造的 payload 配出合法签名。

### 4.4 重放（不篡改）能成功吗？

能——`curl ... -H "Authorization: Bearer $TOK"` 就是重放，200。**JWT 的原罪**：token 在有效期内（本项目 120 分钟）丢了就能被冒用，服务端无状态不记"已发放名单"。缓解：短 TTL + refresh token、HTTPS 防偷、敏感操作二次验证。**HTTPS 是前提**：本项目教学用 http，生产必须上（否则 token 在网线上裸奔）。

---

## 5. XSS / CSRF 快三问

**问 1：XSS 在本项目可能发生吗？**
菜单名、用户名若含 `<script>`，Vue 模板插值 `{{ d.name }}` 默认**转义**，直接渲染不执行——Vue 免疫了大部分。残余风险：`v-html`（本项目没用）、富文本渲染。实测：把菜名改成 `<img src=x onerror=alert(1)>`（可直接改 H2 或等练习），页面只会显示原文。**答案：默认安全，禁用 v-html 即守约。**

**问 2：CSRF 呢？**
CSRF 的前提是"浏览器自动携带凭证"（Cookie/Session）。本项目 token 存 localStorage、手动放 Authorization 头——**第三方站点发起的跨站请求根本带不上 token**，CSRF 天然无门。代价：localStorage 里的 token 对 XSS 更敏感（上面问 1 的守约因此更重要）。这是一对经典权衡：**Cookie+CSRF-Token vs localStorage+严格防 XSS**。

**问 3：CORS 要配吗？**
9090 的 nginx 把前端与 `/apitakeout/` 反代成**同源**——浏览器视角没有跨域，无需 CORS。CORS 是给"前端域名 ≠ API 域名"的生产拓扑用的，届时白名单明确写允许的 Origin，**绝不 `*` + 带凭证**的组合。

---

## 6. 附：安全检查清单（把本章变成可复用的 checklist）

### 6.1 十条上线前安全自查（对照本项目逐条打分）

| # | 检查项 | 本项目现状 | 生产要求 |
|---|---|---|---|
| 1 | 密码强哈希（BCrypt/argon2） | ⚠️ sha256+固定前缀 | 必须换（练习 F） |
| 2 | 登录失败统一文案 | ⚠️ 日志区分了用户不存在/密码错误 | 统一（练习 M） |
| 3 | SQL 全参数化 | ✅ JPA 派生查询 | 保持——禁手拼 SQL |
| 4 | 鉴权覆盖所有端点 | ✅ 拦截器 /api/** + 白名单 | 白名单定期复审 |
| 5 | 水平越权（改 URL 里的 id） | ✅ 归属校验 | 每个资源访问都要验归属 |
| 6 | token 有 TTL 且密钥外置 | ⚠️ TTL 有；secret 在 yml 明文 | 密钥走环境变量（S08） |
| 7 | HTTPS | ❌ 教学用 http | 必须（token 裸奔问题） |
| 8 | XSS 转义 | ✅ Vue 默认转义、无 v-html | 富文本需求引入白名单清洗 |
| 9 | CSRF 面 | ✅ localStorage 方案天然免 | 若改 Cookie 方案需补 CSRF token |
| 10 | 限流/防爆破 | ❌ 无 | 登录接口加失败计数+锁定 |

这个表的用法：不是背下来，而是**每带一个新项目都过一遍**。十条里本项目绿 5 条、黄 2 条、红 3 条——教学项目的诚实评分。

### 6.2 "攻击者视角"的十分钟自检（彩排一次渗透）

用攻击者的顺序打自己的系统：

1. **枚举**：注册/登录的差异回包能筛出有效用户名吗？（本章已抓到改进点）
2. **爆破**：同一用户名连错 100 次密码会被锁吗？（现状：不会——检查清单第 10 条）
3. **越权**：把 URL 里的 orderNo 换成别人的单号，能看/能付/能取消吗？（订单归属校验挡住——实测过"不是你的订单"）
4. **提权**：改 token payload 的 role（本站招牌实验）→ 401 ✅
5. **注入**：登录框塞 SQL（实验二）→ 参数化 ✅；搜索框、排序参数同样要查（本项目无搜索功能，未来加时注意）
6. **注入第二弹**：注意"注入"不止 SQL——还有 shell 注入（`Runtime.exec(用户输入)`）、路径穿越（`../`读任意文件）。本项目没有这两类调用点，但代码评审时永远要扫一眼。
7. **重放**：捡到的 token 能用多久？（120 分钟——TTL 是唯一防线）

十分钟的"彩排"能拦下九成低级漏洞。**安全的本质不是玄学，是持续站在对面看自己的系统。**

## 7. 思考题

1. `sha256("takeaway-" + pwd)` 的前缀算"盐"吗？和每用户随机盐差在哪两个性质？
2. 为什么登录失败对外统一成"用户名或密码错误"？本项目当前的返回会帮攻击者做什么？
3. `u.passwordHash.equals(计算值)` 的字符串比较存在时序侧信道——什么场景下它可被利用？BCrypt 库是怎么防的？
4. JWT 里已经带了 `role` claim，为什么 OrderController 还要在方法里再查 `"RIDER".equals(role)`（OrderController.java:64）？两处各防什么？
5. 如果把 JWT 密钥 `app.jwt.secret` 提交进了 git，泄露后果与补救步骤是什么？

## 8. 练习（H/F/M 三层）

- **H（热身）**：完整复现本站三个实验（注入弹、401 四连、payload 篡改），记录输出。
  验收：五个 401/拒绝结果与文中一致，能口头解释每一行的原理。
- **F（进阶）**：把 takeaway 的密码方案换成 BCrypt——引入 `spring-boot-starter-security`（只取 BCryptPasswordEncoder，不启安全过滤链）或直接加 jjwt 同族的 `org.mindrot:jbcrypt`；兼容迁移：登录时先试 BCrypt.matches，失败再退回旧 sha256 校验（平滑迁移思路）。
  提示：改 AuthController 两处 + SeedRunner 的种子用户；老用户下次登录成功后顺手把 hash 升级成 BCrypt 格式存回。
  验收：alice 用 123456 仍能登录；库里 `passwordHash` 变成 `$2a$...` 格式。
- **M（硬核）**：修 W04 遗留 + 本站遗留——① IllegalStateException 统一映射 409/400；② 登录失败统一返回"用户名或密码错误"（401），不再区分"用户不存在/密码错误"。
  提示：全局异常处理器按 S07 模式扩展；注意 smoke.py 有没有依赖旧文案的断言。
  验收：注入弹与错密码的响应**完全一致**（攻击者无法区分）；smoke 保持全绿。

### 参考答案要点

- 思考 1：固定前缀不是盐。盐的两个性质：**每用户不同**（同密码不同 hash）、**随机生成**（不可预测）。固定前缀只防了"预计算的通用彩虹表"，防不了"针对本站定制表"。
- 思考 2：防止用户名枚举——攻击者批量试用户名，"用户不存在/密码错误"两种回包能把"有效用户名清单"筛出来，再对有效用户名集中爆破密码。
- 思考 3：逐字节短路比较，前缀相同的比较更快——超大量采样下可测出"前缀对了几位"。BCrypt 用常数时间比较（先算完再比，或按位恒时）。真实世界里网络抖动远大于此信号，属理论攻击，但库函数直接给对是应该的。
- 思考 4：拦截器验"登录了没有"（所有 /api/**），方法内验"是不是骑手"（业务级授权）。claim 里的 role 是**声明**，Controller 的校验是**执行**——声明必须被执行层兑现，否则等于没设防（token 解出来不等于有权操作）。
- 思考 5：后果——任何人可签发任意身份的 token（直接造 ADMIN）。补救：换密钥（所有旧 token 立即全部失效，效果上等于强制全员重登）+ 从 git 历史清除 + 评估泄露窗口内的异常登录。这也是 secret 走环境变量/配置中心而不是 yml 明文的原因（S08 的 Profile 思想）。

---

## 9. 附：把三层防御合成一张"攻击路径图"

把本章三把锁放进同一条攻击链，看它们分别在哪一格拦截：

```
攻击者路线                              拦截者            本章位置
────────────────────────────────────────────────────────────────
① 拿库（拖库/SQL注入拖取 users 表）
   ├─ SQL 注入直达          → JPA 参数化（结构免疫）     §3
   └─ 拿到 hash 后离线爆破  → 慢哈希（BCrypt）让爆破破产  §2
② 冒充（伪造/篡改身份凭证）
   ├─ 无 token              → 拦截器 401                 §4
   ├─ 篡改 payload          → 签名校验 401（招牌实验）    §4
   ├─ 伪造签名              → 密钥不出网，签不出          §4
   └─ 原样重放              → 只能靠 TTL+HTTPS（无解硬伤）§4.4
③ 滥用（借浏览器身份发请求）
   ├─ XSS 注脚本            → Vue 默认转义               §5 问1
   └─ CSRF 伪造请求         → token 不自动携带            §5 问2
```

读法：**每一格都问"如果这一个失效，下一格能不能兜住"**——纵深防御（defense in depth）的定义就是这个问句。举例：即使未来有人手拼了一条 SQL（①破防），拖到的 BCrypt hash 爆破成本仍不可行（②兜底）；即使 token 被重放，TTL 120 分钟封顶损失窗口。

反过来读也成立：**格子越少，单点破防的爆炸半径越大**。自查清单（§6.1）的绿 5/黄 2/红 3，就是在数你的格子。

### 与 X01 的衔接：部署视角的第四把锁

X02 讲应用层；部署层还有一把：**网络暴露面**。本项目各后端端口都在本机监听，只有 nginx 9090 是"门面"（`ss -ltnp` 可查）——生产上这叫"最小暴露面"，属于 X01 的职责延伸。四把锁合成一句话：**入口收敛（nginx）、凭证防伪（JWT）、数据防窃（参数化+慢哈希）、面面俱漏时靠纵深。**

## 本节小结
- 密码安全考点是"不可**批量**逆"：sha256 太快 + 固定盐 = 拖库即爆破；BCrypt 用"故意慢 + 内置随机盐"破局。
- JPA 全链参数化（方法名派生查询→PreparedStatement→占位符），**代码里没有拼接动作**，注入面结构性消失；注入弹实测返回"查无此人"而非全表。
- JWT 验签拦得住一切篡改（改 payload 必须重签），拦不住"原样重放"——短 TTL + HTTPS + 敏感操作二次验证补位。
- Vue 默认转义免疫多数 XSS；localStorage 方案天然免 CSRF 但加重 XSS 责任——安全方案是**成套的取舍**，不是单点开关。

## 11. 下一站

X03 收官：65 篇全册地图 + 每章一句话 + H/F/M 练习总清单——把整个项目装进一页纸。

## 练习题

- 练 1：把本章动手实验的参数改一档，预测输出再实测，把差异写下来。
- 练 2：设计一个"改坏条件"的反向实验，验证错误表现与预期一致。


## 动手验证

本章所有代码/命令都能整段复制运行：先跑 `bash infra/start-all.sh` 把全栈点着，再回到本章相应小节逐条复制命令。把你的实测输出与书内"预期输出"逐行对账——一致即通过。

