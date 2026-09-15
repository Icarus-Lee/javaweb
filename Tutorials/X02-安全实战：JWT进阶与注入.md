# X02 · 安全实战：密码哈希、SQL 注入与 JWT 验签的真机两连

> **本节要点**：安全不是加一个"登录"就完事——**存密码的方式、拼 SQL 的方式、验 token 的方式**处处是突破口。本章三场实验全部真机可复制：① sha256 与 BCrypt 的对照；② 恶意用户名实弹打登录接口，看 JPA 参数化如何免疫注入；③ 工程实录两连——**用被篡改（另签）的 secret 签发 token 打正规服务，401 如何当场落下**，以及**把 secret 换成过短字符串会导致 Java 端启动即崩（WeakKeyException 原文）**。
> **前置知识**：S13（JWT 与拦截器）、W01（users 表与 passwordHash）、X01（服务在线，smoke 全绿）。
> **产出**：能说清 sha256 与 BCrypt 的三条差距；能解释参数化查询为何结构上免疫注入；能独立完成"错误密钥签发 + 短密钥启动失败"的完整验证链。

> 🗺 **主线进度**：`… X01 部署手册 ─ ▶X02 安全实战◀ ─ X03 收官 ─ …`
> 🎞 **上一站发生了什么**：X01 从源码走到 23/23，还修了两件部署事故。
> 📀 **本站你会得到**：
> - 三组真实实测输出（注入弹、密钥两连、四种鉴权形态）
> - 密码哈希选型对照表（sha256 vs BCrypt/argon2）
> - XSS/CSRF/CORS 快三问

---

**本站名词卡**

| 名词 | 英文 | 一句人话 | 在本项目哪里见到 |
|---|---|---|---|
| 密码哈希 | password hashing | 库里只存"密码的指纹" | `users.passwordHash` |
| 加盐 | salt | 每人掺一点随机料，防彩虹表 | 本项目用固定前缀（不足，见正文） |
| BCrypt | - | 慢哈希：算一次故意要花 ~100ms | 对照方案（练习 F 落地） |
| SQL 注入 | SQL injection | 用户输入被拼进 SQL 变成代码 | 实验二的主角 |
| 参数化 | parameterized query | SQL 模板与参数分家，输入永远只是数据 | JPA 的默认行为 |
| 密钥 | secret | 验签用的只能服务端持有的比特串 | `app.jwt.secret` |
| 重放 | replay | 拿偷来的凭证冒充原主 | §4.4的主角 |
| WeakKeyException | - | jjwt 对"密钥太短"的启动拒绝 | 本站实录 B |

---

## 1. 一句人话开场

把安全想象成三把锁：：**密码哈希**是登记簿的保管方式（被偷也不能直接冒充）；**参数化**是门牌号的书写规范（访客说的话先抄在纸上再递进去）；**JWT 验签**是出入证的防伪线（证可以捡到重放，但改名字一验就穿）。


## 2. 密码哈希：sha256 的不够与 BCrypt 的够

### 2.1 现状走查（AuthController.java:38, 48）

```java
u.passwordHash = sha256("takeaway-" + req.password());      // 注册：固定前缀当"盐"
if (!u.passwordHash.equals(sha256("takeaway-" + req.password())))   // 登录：同法再算
    throw new IllegalStateException("密码错误");
```

三层防御意图都有：不存明文 ✅；前缀让纯 sha256 彩虹表失效 ✅；比较无时序大差 ✅（严格说 equals 有时序侧信道，思考 3）。**但仍然不够。**

### 2.2 sha256 三宗罪 vs BCrypt 对症下药

| 问题/维度 | sha256(+固定前缀) | BCrypt |
|---|---|---|
| 速度 | 纳秒级（对防御者是灾难） | 故意 ~100ms（成本因子可调） |
| 盐 | 无/固定（同密码同 hash） | 每用户随机盐，编码进 hash 本身 |
| 抗拖库爆破 | 弱 | 100ms × 千万次 = 数月 |
| 库里长相 | 64 位十六进制 | `$2a$10$N9qo...`（算法+成本+盐+hash 四段） |

```text
$ python3 -c "
import hashlib, time
t=time.time()
for _ in range(100000): hashlib.sha256(b'takeaway-123456').hexdigest()
print('sha256 10万次: %.3fs' % (time.time()-t))"
sha256 10万次: 0.031s        ← 每秒 300 万次；GPU 上再快几个量级
```

**考点不是"不可逆"，而是"不可批量逆"——慢就是防御。**

## 3. SQL 注入：JPA 为什么天然免疫

### 3.1 注入成立的唯一条件与反面教材

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
Optional<User> findByUsername(String username);      // ← 方法名翻成 SQL，username 绑定为 ?1
// OrderRepo / DishRepo 同族
```

**代码里根本不存在"拼接"这个动作**——注入面在结构上不存在。评审时刻的唯一守则：任何人手写 `@Query` 原生字符串拼接，立即叫停（`@Query("select ... where u.username = :name")` 绑定命名参数是允许的）。

### 实弹（2026-09-15 实测）：

```text
$ curl -s -X POST 127.0.0.1:8085/api/auth/login -H 'Content-Type: application/json' \
    -d '{"username":"alice'"'"' OR 1=1--","password":"x"}'
{"status":500,"error":"Internal Server Error","path":"/api/auth/login"}
$ grep -a "用户不存在" logs/takeaway.log | tail -1
IllegalStateException: 用户不存在        ← 是"查无此人"，不是"查到了全表"
```

**如果它真能拖库**，你看到的是 200 + 用户数据——这个 500 恰是安全的证明。（"注入"还有 shell 注入 `Runtime.exec`、路径穿越 `../`——本项目无此调用点，评审时永远扫一眼。）

## 4. 工程实录：JWT 密钥两连（2026-09-15 真机实录）

先复习 S13 的三段结构（本站实验全靠它立论）：

```text
header.payload.signature   （三段 base64url）
$ TOK=...；python3 -c "import base64,json;t=open('/tmp/token').read().split('.');
   print(json.loads(base64.urlsafe_b64decode(t[1]+'==')))"
{'sub':'alice','uid':1,'role':'USER','iat':...,'exp':...}
```

要点：header 与 payload **只是编码不是加密**——任何人都能读；安全全押在第三段签名 = HMAC(密钥, header+payload)。服务端用自己保管的密钥重算比对；**密钥不出网**，客户端无法为伪造 payload 配出合法签名。以下两连实验按这个指纹设计。

### 4.1 实录 A：篡改 secret 签发的 token → 401 当场落下

攻击剧本：攻击者猜测/偷到 Other secret（本实验我们真在 8095 起一个 `--app.jwt.secret=attacker-guessed-secret-please-change-1` 的实例，等价于"服务端密钥被换"），从那台实例拿一张合法 token（和正式服务器同一套业务、同一个 alice），然后打正规 8085：

```text
# 起"密钥被篡改"的一号（8095）
$ WTOK=$(curl -s -X POST 127.0.0.1:8095/api/auth/login -H 'Content-Type: application/json' \
    -d '{"username":"alice","password":"123456"}' | python3 -c 'import json,sys;print(json.load(sys.stdin)["token"])')
WTOK 前 20 字符: eyJhbGciOiJIUzI1NiJ9...     ← 注意：alg 不是 GBM 正规实例的 HS384/HS512，光看头就与正规实例不同，露馅

# 同一 token 打三个世界各一次：
$ curl -o /dev/null -w "%{http_code}" 127.0.0.1:8085/api/orders/mine -H "Authorization: Bearer $WTOK"
401        ← 正规库：验签失败，拦下
$ curl -o /dev/null -w "%{http_code}" 127.0.0.1:8095/api/orders/mine -H "Authorization: Bearer $WTOK"
200        ← 签发它的那台自然认
$ curl -o /dev/null -w "%{http_code}" 127.0.0.1:8095/api/orders/mine -H "Authorization: Bearer $TOK"
401        ← 反向同样互斥：正规 token 在"换密钥"的库里也是 401
```

**Java 端的真实验证路径**（JwtUtil.java:36-42）：`verify()` 里 jjwt 抛 `SignatureException`，被 catch 后返回 null——AuthInterceptor.java:21 `if (c == null) { resp.setStatus(401); return false; }`。**401 是过滤器层行为**：连 Controller 门都没摸到。底层数学：签名 = HMAC(密钥, header+payload)，密钥换了一比特，重算的 HMAC 就面目全非；**密钥不出网**，客户端永远无法为伪造 payload 配出合法签名。

### 4.2 实录 B：把 secret 换成过短字符串 → 启动即崩（Java 端原文）

```text
$ $JAVA_HOME/bin/java -jar backend/takeaway/target/takeaway-1.0.0.jar \
    --server.port=8095 --app.jwt.secret=shortsecret
（进程几秒后带原义报错退出）日志原文（行 121）：
Caused by: io.jsonwebtoken.security.WeakKeyException:
    The specified key byte array is 88 bits which is not secure enough for any JWT
    HMAC-SHA algorithm. The JWT JWA Specification (RFC 7518, Section 3.2) states that
    keys used with HMAC-SHA algorithms MUST have a size >= 256 bits ...
```

读法：错误发生在 **JwtUtil 构造函数**（JwtUtil.java:18-20 `Keys.hmacShaKeyFor(...)`）→ Spring Bean 创建失败 → 整个上下文取消刷新 → 全服务拒跑。**fail-fast 的教科书案例**：弱密钥宁要"服务都起不来"，也不要"带病服务"。X01 的排查指纹链（短 Duration + status=1 + journal 原文）同款复用。

### 4.3 四种鉴权形态全测（同一接口 /api/orders/mine）

```text
正常 token:              200      ← 合法身份
无 token:                401      ← AuthInterceptor.java:19（没带 Bearer）
假 token（假签名）:      401      ← JwtUtil.verify → null
错误 scheme（Basic）:    401      ← 不以 Bearer 开头
篡改 payload 留旧签名:   401      ← 签名对不上 payload
陌生（被篡改）密钥签发:  401      ← 本站实录 A
```

### 4.4 重放（不篡改）能成功吗？

能——token 在有效期内丢了就能被冒用（本项目 120 分钟）。缓解：短 TTL + refresh token、HTTPS 防窃、敏感操作二次验证。**HTTPS 是前提**：教学用 http，生产必须上（否则 token 在网线上裸奔）。

## 5. XSS / CSRF / CORS 快三问

- **XSS**：Vue 模板插值默认转义；残余风险是 `v-html`（本项目没用）。实测：把菜名改成 `<img src=x onerror=alert(1)>` 页面只显示原文。**默认安全，禁用 v-html 即守约。**
- **CSRF**：前提是浏览器自动带凭证；localStorage + 手动 Authorization 头天然免疫。代价：localStorage 对 XSS 更敏感（问 1 守约更重要）。权衡：Cookie+CSRF-Token vs localStorage+严格防 XSS。
- **CORS**：nginx 反代成同源（9090），浏览器无跨域无需配置；生产上白名单写明确 Origin，**绝不 `*` + 带凭证**的组合。

## 5.5 攻击者视角的十分钟自检（彩排一次渗透）

用攻击者的顺序打自己的系统——十分钟拦下九成低级漏洞：

```text
1. 枚举：注册/登录的差异回包能筛有效用户名吗？（本章已抓到改进点——统一 401 文案）
2. 爆破：同用户连错 100 次密码会锁吗？（现状不会——清单第 10 条）
3. 越权：URL 里的 orderNo 换成别人的单，能看/付/取消吗？（归属校验挡住——"不是你的订单"）
4. 提权：改 token payload 的 role → 401（原版招牌实验）
5. 密钥： 陌生密钥签发的 token → 401（本站实录 A）
6. 注入：登录框塞 SQL → 参数化 ✅；未来加搜索/排序参数时同样要查；另查 shell 注入与路径穿越调用点
7. 重放：捡到的 token 能用多久？（120 分钟——TTL 是唯一防线，配 HTTPS 才有下文）
```

**安全的本质不是玄学，是持续站在对面看自己的系统。**

## 6. 安全检查清单（十项，逐条打分）

| # | 检查项 | 本项目现状 | 生产要求 |
|---|---|---|---|
| 1 | 密码强哈希 | ⚠️ sha256+固定前缀 | 换 BCrypt（练习 F） |
| 2 | 登录失败统一文案 | ⚠️ 日志区分两种 | 统一（练习 M） |
| 3 | SQL 全参数化 | ✅ JPA 派生查询 | 保持，禁手拼 |
| 4 | 鉴权覆盖 | ✅ 拦截器 /api/** | 白名单定期复审 |
| 5 | 水平越权 | ✅ 归属校验 | 每个资源验归属 |
| 6 | token TTL/密钥外置 | ⚠️ TTL 有；secret 在 yml | 密钥走环境变量（S08） |
| 7 | HTTPS | ❌ | 必须 |
| 8 | XSS 转义 | ✅ | 富文本加白名单清洗 |
| 9 | CSRF | ✅ localStorage | 改 Cookie 需补 token |
| 10 | 限流/防爆破 | ❌ | 登录失败计数+锁定 |

绿 5 黄 2 红 3——教学项目的诚实评分；每带新项目过一遍。

### 6.1 BCrypt 迁移的最小代码形状（练习 F 的路标）

```java
// 依赖：org.mindrot:jbcrypt:0.4（或只引 spring-security-crypto 取 BCryptPasswordEncoder）
// 注册：
u.passwordHash = BCrypt.hashpw(req.password(), BCrypt.gensalt(10));
// 登录（平滑迁移：库里可能是旧格式）：
boolean ok = u.passwordHash.startsWith("$2a$")
    ? BCrypt.checkpw(raw, u.passwordHash)                       // 新：从 hash 里解析盐验
    : u.passwordHash.equals(sha256("takeaway-" + raw));        // 旧：退回原法
if (ok && !u.passwordHash.startsWith("$2a$")) { ...升级 hash 存回... } // 渐进升级
```

评分点（评审这样追问）：迁移期间**两种格式并存**是不是后门？——旧格式只在存量用户登录时比对成功并立即升级，新注册/升级后 only BCrypt；可以再配一个"截止日期后禁旧格式碰撞"的巡检。`$2a$10$...` 的四段结构（算法/成本/盐/摘要）让"成本因子"未来可整体调高——**成本参数是迁移的转轮**。

## 7. 攻击路径图（纵深防御的读法）

```text
① 拿库：SQL 注入直达 → 参数化结构免疫（§3）；拿到 hash 离线爆破 → 慢哈希破产（§2）
② 冒充：无 token → 拦截器 401；篡改 payload → 401；陌生密钥签发 → 401（实录 A）
③ 滥用：XSS → Vue 转义；CSRF → token 不自动携带
```

**每一格问"这一个失效，下一格能不能兜住"**——纵深防御的定义。举例：即使未来有人手拼了一条 SQL（①破防），拖到的 BCrypt hash 爆破成本仍不可行（②兜底）；即使 token 被重放，TTL 120 分钟封顶损失窗口。反读也成立：**格子越少，单点破防爆炸半径越大**——自查清单（§6）的绿 5/黄 2/红 3 就是在数你的格子。

### 7.1 与 X01 的衔接：部署视角的第四把锁

X02 讲应用层；部署层还有一把——**网络暴露面**。本项目后端各端口全部监听本机，只有 nginx 9090 是"门面"（`ss -ltnp` 一查便知）；生产叫"最小暴露面"。四把锁一句话：**入口收敛（nginx）、凭证防伪（JWT）、数据防窃（参数化+慢哈希）、都漏了靠纵深**。X01 实录 B 教的两级定位（`ss -ltnp` + /proc）就是"知道谁在听哪个门"的日常。

## 8. 思考题

1. `sha256("takeaway-" + pwd)` 的前缀算"盐"吗？与每用户随机盐差在哪两个性质？
2. 为什么登录失败对外统一成"用户名或密码错误"？
3. `equals` 的时序侧信道什么场景可被利用？BCrypt 怎么防？
4. JWT 已带 role claim，OrderController 为什么还要查 `"RIDER".equals(role)`（OrderController.java:64）？
5. 若 `app.jwt.secret` 进了 git，泄露后果与补救步骤？（结合实录 A：换密钥=全员旧 token 失效——效果上等同强制重登。）

### 参考答案要点

1：盐须每用户不同且随机；固定前缀只防通用彩虹表。2：防用户名枚举。3：逐字节短路比较，前缀相同比较更快；BCrypt 恒时比较。4：claim 是声明、Controller 校验是执行——声明必须被执行层兑现。5：任何人可签任意身份；换密钥+清 git 历史+评估窗口期异常。

## 9. 练习（H/F/M 三层）

- **H（热身）**：完整复现三个实验（注入弹、密钥两连、四种形态）。
- **F（进阶）**：换 BCrypt——`org.mindrot:jbcrypt`；平滑迁移：登录先 BCrypt.matches，失败退回旧 sha256；老用户登录成功后把 hash 升级存回。
  验收：alice 用 123456 仍能登录；库里变 `$2a$...`。
- **M（硬核）**：修两处：① IllegalStateException 统一映射 409/400；② 登录失败统一"用户名或密码错误"（401）。
  验收：注入弹与错密码响应完全一致；smoke 全绿。

## 10. 本节小结

- 密码考点是"不可批量逆"：sha256 太快+固定盐=拖库即破；BCrypt 故意慢+内置盐破局。
- 全链参数化：代码里没有拼接动作，注入面结构性消失；注入弹返回"查无此人"而非全表。
- 密钥两连实录：陌生密钥签发 401、短密钥启动即崩 WeakKeyException 原文——验签与 fail-fast 双闸。
- Vue 默认转义免疫多数 XSS；localStorage 免 CSRF 但加重 XSS 责任——安全是成套取舍。

## 11. 下一站

X03 收官：64 篇全册地图 + 每章一句话 + H/F/M 练习总清单——把整个项目装进一页纸。

## 动手验证

本章所有代码/命令都能整段复制运行：先跑 `bash infra/start-all.sh` 把全栈点着，再回到本章相应小节逐条复制命令。把你的实测输出与"预期输出"逐行对账——一致即通过。

## 练习题

- 练 1：把 app.jwt.secret 换成 24 位，验证 WeakKeyException 是否仍发生并总结阈值。
- 练 2：三角色对 /api/orders/{no} 各打一次（用户/骑手/陌生人），断言三种输出。

## 动手验证

复现“篡改密钥的兄弟实例签发 token → 打正规实例 401”三连实验，输出发你实验笔记里。
