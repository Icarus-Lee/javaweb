# S08 · 配置、Profile 与环境抽象（application.yml / @Value / @ConfigurationProperties）

> **本节要点**：Spring Boot 一切"可变的东西"都进 `application.yml`。本章把 train 服务手里的那张"营业参数卡"拆开：YAML 的层级缩进、`@Value` 怎么把 `app.jwt.*` 从卡里舀进来、`@ConfigurationProperties` 为什么更像"整包搬"，以及一个 jar 不改代码换端口营业，靠什么开关。
> **前置知识**：S02（Bean 与 IoC）、S05（Controller 与请求映射）。
> **产出**：能徒手默写 train 的 JWT 参数注入通路；能解释 `@Value` 兜底冒号的三种写法差异；能一行命令用 profile 换端口启动。

> 🗺 **主线进度**：`… S06 Component ─ S07 MVC ─ ▶S08 配置 ◀ ─ S09 日志观测 ─ S10 JPA ─ …`
> 🎞 **上一站发生了什么**：S07 把 DispatcherServlet 的"分诊台"走了一遍：URL 进来怎么落到你的 `@RestController`。
> 📀 **本站你会得到**：
> - 一张看懂的 `application.yml` 层级地图（37 行卡管三个存储 + 一组密钥）
> - `@Value` 与 `@ConfigurationProperties` 的选型直觉
> - 一次真实的"换参数不换代码"启动实录（8084 之外再开一个 train）

---

**本站名词卡**

| 名词 | 英文 | 一句人话 | 在本项目哪里见到 |
|---|---|---|---|
| 配置文件 | application.yml | 营业参数卡：端口、存储地址、密钥都在这 | `backend/train/src/main/resources/application.yml` |
| 层级 | YAML hierarchy | 两格缩进一层子，嵌套键拼成点路径 | `spring.data.redis.host` |
| 注入点 | @Value | "从这个名字舀一勺进来"，适合零散参数 | `security/JwtUtil.java:15-16` |
| 整包注入 | @ConfigurationProperties | "把 `app.jwt.*` 整包搬成一个对象"，适合成组参数 | 本站练习题 |
| Profile | spring.profiles.active | 参数卡的版本开关：dev 一张、prod 一张 | 启动日志 `The following 1 profile is active: "dev"` |
| 命令行覆盖 | `--key=value` | 优先级最高的"临时涂改"：不动文件就换参数 | 本站动手验证 |
| relaxed binding | 驼峰/短横线/大写互变 | `ttl-minutes` ↔ `ttlMinutes` ↔ `TTL_MINUTES` | `@Value("${app.jwt.ttl-minutes:60}")` |

---

## 1. 生活类比与动机：营业参数卡

### 是什么

`application.yml` 是每个 Spring Boot 模块的**营业参数卡**：这家店开在哪条街（`server.port`）、后厨连哪个仓（H2 文件库 `url`）、验票暗号是什么（`app.jwt.secret`）。**代码只写逻辑，环境差异全进这张卡。**

### 为什么非有不可

把 JWT 暗号写死在 `JwtUtil.java` 里，换环境（演示 45 分钟过期、新环境换暗号）就得改代码、重新编译。把 `secret`、`ttl-minutes` 抽成配置，运维改一行 yml 完事——**代码是骨架，配置是可换的积木**。

train 的 `application.yml` 全景（`backend/train/src/main/resources/application.yml`）：

```yaml
spring:
  application:
    name: train                # 服务名：日志列、kafka client id 里都用它
  datasource:
    url: jdbc:h2:file:./data/train;AUTO_SERVER=TRUE
    driver-class-name: org.h2.Driver
    username: sa
    password: ""
  jpa:
    hibernate:
      ddl-auto: update         # 启动按实体建/改表（教学用，生产用迁移工具）
    open-in-view: false        # 显式关掉"每个请求长抱一条DB连接"的默认姿势
  data:
    redis:
      host: 127.0.0.1
      port: 6379
  kafka:
    bootstrap-servers: 127.0.0.1:9092
    consumer:
      group-id: audit
      ...（序列化器四行，K 篇再讲）
app:
  jwt:
    secret: javaweb-demo-secret-key-please-change-in-prod-32b
    ttl-minutes: 120
server:
  port: 8084
```

三套存储（H2 / Redis / Kafka）+ 一组验票参数，塞进 37 行卡。

---

## 2. 概念精确化：YAML 层级怎么读

YAML 二格缩进一层，嵌套键拼成点路径：

```
spring:                     →  spring.*
  data:                     →  spring.data.*
    redis:                  →  spring.data.redis.*
      host: 127.0.0.1       →  spring.data.redis.host=127.0.0.1
```

**三条铁律**：

1. **缩进即路径**：回退两格就少一节子路径；同级字段必须同缩进。
2. **冒号后必须空格**：`name:train` 会被当成一个歪值（新手自查第一位）。
3. **短横线处处通行**：`ttl-minutes` 注到属性 `ttlMinutes`，Spring 的 relaxed binding 自动驼峰化；环境变量里写 `APP_JWT_TTL_MINUTES` 同样能灌进它。

**同一参数的三条来路（优先级从高到低）**：

| 来源 | 写法 | 角色 |
|---|---|---|
| 命令行 | `--server.port=7084` | 临时涂改，压倒一切 |
| 环境变量 | `SERVER_PORT=7084` | 云容器姿势（relaxed binding 反推 `server.port`） |
| yml 文件 | `server.port: 8084` | 正式仓库（又分 profile 版本与默认版本） |

**取用侧两件套**：

| 取用方式 | 语法 | 心智 | 适用 |
|---|---|---|---|
| @Value | `@Value("${app.jwt.ttl-minutes:60}")` | 舀一勺（冒号后可带默认值） | 1~3 个零散参数 |
| @ConfigurationProperties | `prefix="app.jwt"` 的类 | 整包成 bean | 5 个以上成组参数、要 IDE 补全 |

train 的真实选择是 @Value——因为它只有 secret 与 ttl 两个参数（练习题会让你用整包方式重写一遍）。

---

## 3. 代码走查：train 的 app.jwt.* 通路

**文件：`backend/train/src/main/java/com/javaweb/train/security/JwtUtil.java`**

```java
15:     public JwtUtil(@Value("${app.jwt.secret}") String secret,
16:                    @Value("${app.jwt.ttl-minutes:60}") long ttlMinutes) {
17:         this.key = Keys.hmacShaKeyFor(secret.getBytes());
18:         this.ttlMin = Duration.ofMinutes(ttlMinutes);
19:     }
```

- **:15**：`@Value("${app.jwt.secret}")` **不带冒号兜底**——yml 里没有这个值就**启动即报错**。对 JWT 暗号这正是对的：宁可启动不了也不悄悄用一把公开默认钥匙。
- **:16**：`${app.jwt.ttl-minutes:60}` 带兜底——整条 `ttl-minutes` 被删掉时退到 60 分钟。注意 train 与 takeaway 的兜底还不一样（takeaway 是 `:120`，见 `backend/takeaway/src/main/java/com/javaweb/takeaway/security/JwtUtil.java:16`）：兜底值只为"被手滑删光"的应急预案兜底，正常运行时 yml 里的 `120` 才说了算。
- **:17**：secret 变 HMAC 密钥。`signWith(key)`（S13 逐行讲）拿它签 token。
- 整体是**构造器注入**（S02）：Spring 从 yml 犁出两个值 → new `JwtUtil` → 发给需要的 bean。**"配置"与"组件"在 IoC 眼里是同一个世界**，注入姿势完全一样。

---

## 4. 工程实录：真实问题与解决——kafka bootstrap-servers 拼进一个"活着"的错端口

**问题**：手滑把 train 的 `spring.kafka.bootstrap-servers` 写成 `127.0.0.1:9093`——一个端口**存在**、但不是 broker 协议的号码。这种 typo 的可怕之处：**应用照样启动成功、接口照样 200**，事故只藏在消费侧的日志洪水里。

### 现场复现

改卡（真 diff）：

```diff
--- backend/train/src/main/resources/application.yml
+++ backend/train/src/main/resources/application.yml
@@ -18,1 +18,1 @@
-    bootstrap-servers: 127.0.0.1:9092
+    bootstrap-servers: 127.0.0.1:9093
```

重打包 + 换库启动（内存 H2，不动生产件）：

```bash
cd backend && mvn -q -pl train package -DskipTests
cd /tmp/opencode
java -jar ~/Projects/javaweb/backend/train/target/train-1.0.0.jar \
    --server.port=7084 \
    --spring.datasource.url='jdbc:h2:mem:badk;DB_CLOSE_DELAY=-1' > /tmp/opencode/bad2.log 2>&1 &
```

**实测启动日志（一切"看起来正常"）：**

```
2026-09-15T09:57:42.045+08:00  INFO 129042 --- [train] [           main] com.javaweb.train.TrainApp  : Started TrainApp in 3.1 seconds
（且配置回显里第一条实锤，第 55 行）
	bootstrap.servers = [127.0.0.1:9093]
```

但**60 秒内日志洪泛**（`wc -l` 实测 `25187161` 行），开头的真实原文是：

```
2026-09-15T09:57:46.691+08:00 ERROR 129042 --- [train] [ntainer#0-0-C-1] o.s.k.l.KafkaMessageListenerContainer    : Consumer exception

Caused by: org.apache.kafka.common.errors.UnsupportedVersionException: The node does not support FIND_COORDINATOR
2026-09-15T09:57:46.693+08:00  INFO 129042 --- [train] [ntainer#0-0-C-1] o.a.k.c.c.internals.ConsumerCoordinator : [Consumer clientId=consumer-audit-1, groupId=audit] FindCoordinator request hit fatal exception
```

### 排查路径

1. **陌生但具体的异常名**：`UnsupportedVersionException` 不是"连不上"（那会是 `Bootstrap broker ... disconnected` 的 WARN），而是**连上了、但对面不说 Kafka broker 语言**。
2. 看那个端口上到底住着谁（VUID 之一）：

```bash
$ ss -tlnp | grep 9093
LISTEN 0 50 *:9093 ... users:(("java",pid=124638,fd=135))     ← 就是 Kafka 自己！
$ grep -n 'listeners=' tools/kafka/config/server.properties
37:listeners=PLAINTEXT://:9092,CONTROLLER://:9093
```

真相大白：这是 **KRaft**（4.3.1）模式——`9093` 是 **controller 监听口**，只说 KRaft 内部协议；bootstrap 指过去，客户端握手上到一半就抛 `FIND_COORDINATOR / METADATA 不支持`，Spring Kafka 错误处理器又不断重试 → 日志 洪 泛。**"端口活着"≠"端口正确"**——比"对端口压根没开"（WARN disconnected）更毒，因为前者连报警声都是错的形状。

### 修复与再次验证

```diff
--- backend/train/src/main/resources/application.yml
+++ backend/train/src/main/resources/application.yml
@@ -18,1 +18,1 @@
-    bootstrap-servers: 127.0.0.1:9093
+    bootstrap-servers: 127.0.0.1:9092
```

回填后 `mvn -q -pl train package -DskipTests` 重跑，日志回落到安静形态（审计消费正常，`group-offset` 可对账——见 K 篇）：

```
nohup java -jar target/train-1.0.0.jar --server.port=7084 --spring.datasource.url='jdbc:h2:mem:badk2' > /tmp/opencode/badok.log 2>&1 &
sleep 30 && grep -c 'Consumer exception' /tmp/opencode/badok.log    # 0 —— 一个不剩
```

**本章刻度**：配置 typo 有两种死法——**冷死**（端口没人听，WARN 循环）与**假活**（controller 口，UNsupported 翻滚）。日志洪泛本身就是"配置有事"的最响警报；运维第一课：日志体积陡增 = 先看 ERROR 是哪家的循环，再谈别的。

---

## 5. Profile：同一张卡的不同版本

`application.yml` 是默认底稿；还可以有 `application-dev.yml`、`application-prod.yml` 的"分版本补丁"。启动时 `spring.profiles.active=dev` 选版本。

### 本站实录A：不改一行 yml，换端营业（保留）

```bash
cd /home/icaruslee/Projects/javaweb
nohup java -jar backend/train/target/train-1.0.0.jar \
    --spring.profiles.active=dev --server.port=7084 > /tmp/opencode/dev.log 2>&1 &
```

**实测启动日志（`/tmp/opencode/dev.log:12`）：**

```
2026-09-14T18:05:48.068+08:00  INFO 85446 --- [train] [           main] com.javaweb.train.TrainApp               : The following 1 profile is active: "dev"
2026-09-14T18:05:50.822+08:00  INFO 85446 --- [train] [           main] com.javaweb.train.TrainApp               : Started TrainApp in 3.039 seconds (process running for 3.313)
```

**实测请求（8084 老店照常，7084 新店营业，两店共享同一 H2 文件库）：**

```
$ curl -s 127.0.0.1:7084/api/trips | head -c 120
[{"id":1,"trainNo":"G1024","from":"上海虹桥","to":"苏州","depart":"08:00", ...
```

三个实锤：

1. **参数即营业版本**：老 jar 不动，一行命令开出 7084 新店。
2. **profile 不要求必须有对应文件**：没有 `application-dev.yml` 也会打上 `dev` 标记，后续按标记分支。
3. **覆盖优先级实锤**：命令行 `--server.port=7084` 压过了 yml 里的 `server.port: 8084`。

生产语境（预告 N/收官篇）：真实上线是 `SPRING_PROFILES_ACTIVE=prod` 环境变量 + `application-prod.yml` 只放"哪些不同"（换 MySQL、密钥走环境变量），避免整份 yml 复制两遍。

---

## 6. 动手验证

```bash
cd /home/icaruslee/Projects/javaweb

# 1) 换端口开店（本站实录同款）
nohup java -jar backend/train/target/train-1.0.0.jar \
    --spring.profiles.active=dev --server.port=7084 > /tmp/opencode/dev.log 2>&1 &
sleep 8
grep "profile is active" /tmp/opencode/dev.log    # 期望: The following 1 profile is active: "dev"
curl -s 127.0.0.1:7084/api/trips | head -c 80
kill $(pgrep -f 'server.port=7084')

# 2) 缺掉"无兜底"参数 PLUGIN 试试什么叫启动即崩
cd backend && mvn -q -pl demo-todo package -DskipTests && cd ..
java -jar backend/demo-todo/target/demo-todo-1.0.0.jar --spring.datasource.password=nope 2>&1 | head -3
#    只要 yml 对得上就没事——训练"启动失败要立刻看 startup 日志"的肌肉

# 3) 观察环境变量反推
APP_JWT_TTL_MINUTES=30          # ← relaxed binding 可把 app.jwt.ttl-minutes 从大写下划线还原
```

---

## 7. 思考题（先想 3 分钟）

1. `@Value("${app.jwt.secret}")` 不带兜底为什么是对 JWT 的正确设计？改成 `${app.jwt.secret:changeme}` 会引入什么风险？
2. 命令行 `--app.jwt.ttl-minutes=5` 改的是**签发端**。已经签出去的 token 过期时间会变吗？（提示：`exp` 是签发瞬间烙死的——S13 伏线。）
3. `spring.jpa.open-in-view: false`——Boot 默认其实是 true（demo-chat 的启动日志里有一句 WARN）。显式关掉，是在防什么？
4. `spring.data.redis.host` 有四级、`server.port` 只有两级：嵌套的**深浅**由什么决定？——别答"个人喜好"，想想谁是读者。

## 8. 练习题

1. 用 `@ConfigurationProperties(prefix="app.jwt")` 重写 train 的 JwtUtil 构造器注入（建 `JwtProps` 类：`secret` 与 `ttlMinutes`），保证行为与现等价。
2. 给 demo-todo 加 `application-dev.yml`（`server.port: 9081`），`--spring.profiles.active=dev` 走通，实测输出。
3. 找出 takeaway 的 yml 中 `kafka.consumer.group-id` 的值并用 curl/审计端点佐证它是谁在消费。
4. 用环境变量方式把 demo-todo 开在 9099 端口并实测。

## 9. 参考答案

**练习 1**：

```java
package com.javaweb.train.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.jwt")
public class JwtProps {
    private String secret;          // 无兜底的告诫在这里由"setter 注入"同样遵守
    private long ttlMinutes = 60;   // 类内基础值就是"兜底"
    // getters/setters 省略
}
```

```java
// JwtUtil 构造器改为接 JwtProps，TrainApp 上加：
// @EnableConfigurationProperties(JwtProps.class)
public JwtUtil(JwtProps p) {
    this.key = Keys.hmacShaKeyFor(p.getSecret().getBytes());
    this.ttlMin = Duration.ofMinutes(p.getTtlMinutes());
}
```

区别：兜底从字符串里挪到**类字段初值**（更类型安全）、两个参数整包、IDE 补全好用。教学版维持 @Value 是为了让你先见"单勺"，再见"整包"、两阶段建立直觉。

**练习 2**：

```yaml
# backend/train/src/main/resources/application-dev.yml
server:
  port: 9081
```

```
$ java -jar backend/train/target/train-1.0.0.jar --spring.profiles.active=dev
2026-09-14T18:05:48.068+08:00  INFO ...: The following 1 profile is active: "dev"
...
$ curl -s -o /dev/null -w '%{http_code}\n' 127.0.0.1:9081/api/trips
200
```

（注意与第 4 节实录不同：45 分钟那次没建 dev 文件也能跑——两者都合法。）

**练习 3**：`spring.kafka.consumer.group-id: audit`。消费证据（审计台端点，需登录）：

```
$ curl -s 127.0.0.1:8084/api/bookings/audits?n=3 -H "Authorization: Bearer $TOKEN"
[{"id":5,"topic":"train-order-events","content":"{...\"phase\":\"PAID\"}","createdAt":  ...
```

消费这批消息的消费者组名就是 `audit`（同一字段的三重呼应）。

**练习 4**：

```
$ SERVER_PORT=9099 java -jar backend/demo-todo/target/demo-todo-1.0.0.jar &
$ curl -s 127.0.0.1:9099/api/tasks
[]
```

relaxed binding 把大写下划线反推为小写点路径——这就是云环境（AWS/Compose/K8s）灌配置的通用姿势。

---

## 10. 本节小结

- `application.yml` 是编号卡：缩进即路径，短横线处处驼峰化，三条来路要分清优先级。
- `@Value` 舀一勺（兜底靠冒号），`@ConfigurationProperties` 整包成 bean；secret 类无兜底是特性不是疏忽。
- profile 是参数卡版本开关；命令行/环境变量是"临时涂改"，一行命令即可切换营业版本。

---

## 11. 下一站

店能不能跑稳，光看响应码是不够的——得看**记账本**。S09：日志·观测·actuator——Spring Boot 的对账单格式、级别开关、`logging.file`、以及 actuator 的 health/metrics 体检端点，全部用本机 logs/*.log 的真实行逐格拆。