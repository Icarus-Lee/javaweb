# S01 第一个 Spring Boot：空转跑起来

> 主线：本站你走在大厅的"检票入口"。什么都不做配置，门就自己开了——本课只回答一个问题：**一个没有任何 XML、没有任何配置类的 Java 项目，为什么 `main` 一跑就变成了 Web 服务器？**
>
> 学完本课你将能：读懂 `@SpringBootApplication` 的三层合成、说清"零配置"到底谁在做工、并亲手把 demo-todo 从零拉起到 curl 通。

## 本站名词卡

| 名词 | 一句话人话 |
|---|---|
| Spring Boot | Spring 的"启动器包装"：按默认值把 Spring 全家桶装配好 |
| `@SpringBootApplication` | 三合一注解：自动装配 + 组件扫描 + 配置类声明 |
| 自动装配（auto-configuration） | 框架看你 classpath 里有什么，就默默注册相应的 Bean |
| 组件扫描（component scan） | 从启动类所在包向下扫，把带 `@Component` 系注解的类全部实例化 |
| 内嵌服务器（embedded server） | Tomcat 不再单独部署，而是随 jar 一起 `java -jar` 启动 |
| starter | 一组"想用 A 必带 BCD"的依赖打包，例如 `spring-boot-starter-web` |
| `application.yml` | 唯一的默认值改写入口：`配置键: 值` |

## 一、先看：demo-todo 一共只有 4 个 Java 文件

```text
backend/demo-todo/src/main/java/com/javaweb/todo/
├── TodoApp.java                     # 启动类（本课主角）
├── controller/TaskController.java   # HTTP 接口层（S04 详讲）
├── repo/TaskRepo.java               # 数据访问（JPA 篇详讲）
└── model/Task.java                  # 数据实体
```

母子项目 `backend/pom.xml` 把 5 个模块（demo-todo/chat/counter + train + takeaway）组成 Maven reactor，一次构建一键出 5 个 jar。而 demo-todo 只是被 `infra/build.sh` 调用 `mvn package` 产出 `demo-todo-1.0.0.jar`，交由 `infra/start-all.sh` 启动——你可以把它看成一个"人畜无害的起点项目"：无鉴权、无 Redis、无 Kafka，**只用 Spring Boot 的最小闭环（Controller + JPA + 校验）**。

对照你写过的 C/C++：做一个 HTTP 服务要拼 socket 监听、解析 HTTP 报文、路由分发、数据库驱动接入……而这里的启动类全文如下：

```java
// backend/demo-todo/src/main/java/com/javaweb/todo/TodoApp.java（全文 11 行）
@SpringBootApplication
public class TodoApp {
    public static void main(String[] args) {
        SpringApplication.run(TodoApp.class, args);
    }
}
```

两个同款伙伴——demo-chat 与 demo-counter 的启动类也一样短：

```java
// backend/demo-chat/src/main/java/com/javaweb/chat/ChatApp.java（全文同款）
@SpringBootApplication
public class ChatApp {
    public static void main(String[] args) {
        SpringApplication.run(ChatApp.class, args);
    }
}
```

道理都不变，变的是"装了什么 starter、扫到了什么类"。

## 二、`@SpringBootApplication` 的三层会计

这个注解在源码里是三个注解的合体，三人各管一本账：

### 账本 1：自动装配（`@EnableAutoConfiguration`）

Spring 官方给每个 starter 都带了一份"装配剧本"，写的是**条件式注册**规则：

- `demo-todo/pom.xml` 引入 `spring-boot-starter-web` → classpath 上出现 Tomcat、Spring MVC、Jackson → 剧本命中 → 自动注册：
  - 内嵌 Tomcat（监听默认 8080）；
  - `DispatcherServlet`（所有 HTTP 请求的总机）；
  - Jackson 的 JSON 消息转换器（对象 ↔ JSON）；
- 引入 `spring-boot-starter-data-jpa` 且 classpath 出现 H2 → 注册数据源、JPA 事务管理器、HibernateSessionFactory。

读剧本的年龄基因是"**条件注解**"（`@ConditionalOnClass` 等），核心一句话：

> **你 jar 里带了什么货，框架见了就装什么。** 配置文件里用过键（如 `server.port`）就改默认值，没用过键就全默认。

这就是"零配置"的本质：**不是没有配置，而是配置被 starter 的默认值代写了。**

### 账本 2：组件扫描（`@ComponentScan`）

以**启动类所在包为根**，向下递归扫所有子包，把 `@Component` 系（`@RestController`、`@Service`、`@Repository`、`@Component`）的类全部登记成 Bean。

demo-todo 的根包是 `com.javaweb.todo`：

- `com.javaweb.todo.controller.TaskController` → 带 `@RestController` → 入册；
- `com.javaweb.todo.repo.TaskRepo`（一个 interface！）→ 由 Spring Data JPA 扫描 → 动态生成实现并入册。

**推论（铁律）**：启动类必须住在**最顶层包**，否则"外面的类扫不到"。train 系列严格照此布局：

```text
backend/train/src/main/java/com/javaweb/train/
├── TrainApp.java                  # root 包 = com.javaweb.train
├── controller/
├── service/
├── repo/
├── model/
├── security/
├── messaging/
└── config/
```

一次 `@SpringBootApplication` 扫描，七个包里所有注解类全部收手。

### 账本 3：启动类自己就是配置类（`@SpringBootConfiguration`）

你可以在 `TodoApp` 里直接写 `@Bean` 方法（相当于一个微型 `applicationContext.xml`）。教学项目里没有用到——本课先记住"启动类也是配置类"这个身份，S03 的候选 Bean 一课会呼应。

### 三本账衔接起来的一段"接力"

```text
starter-web 带 Tomcat ──▶ 自动装配注册 Tomcat（默认 8080）
application.yml         ──▶ server.port: 8081 只改一个数字
main()                  ──▶ SpringApplication.run → 容器 refresh
                        ──▶ 账本2 扫描 + 账本1 自动装配
                        ──▶ 内嵌 Tomcat 抬起 8081
                        ──▶ 日志 "Started TodoApp in X seconds"
```

## 三、application.yml：本项目唯二的默认值改写区

```yaml
# backend/demo-todo/src/main/resources/application.yml（全文 14 行）
spring:
  application:
    name: demo-todo
  datasource:
    url: jdbc:h2:file:./data/demo-todo;AUTO_SERVER=TRUE   # H2 文件库，随项目目录走
    driver-class-name: org.h2.Driver
    username: sa
    password: ""
  jpa:
    hibernate:
      ddl-auto: update      # 启动时按实体自动建/改表（教学用；生产用迁移工具）
    open-in-view: false
server:
  port: 8081                # ← 全文件唯一改写默认行为的一行
```

三读：

1. `datasource` 区的 URL 填了，自动装配就自动创建 H2 数据源 + Hibernate 配置——**你从没写过连接池的代码**，`HikariPool` 已在替你把关（S03 停机日志里出现过它）。
2. `ddl-auto: update`：启动时 Hibernate 检查 `Task` 实体带 `@Entity`/`@Id`，自动建表 `TASK`。这就是**首次启动无需建表脚本**的原因。
3. `server.port: 8081`：把 Tomcat 从 8080 挪到 8081（因为 9090/8084/8085 各有归属，本项目 5 个服务就是 5 个端口）。

train 与 takeaway 的 yml 长得同构，但多三段（本课不深谈）：

```yaml
# backend/train/src/main/resources/application.yml（节选）
  data:
    redis:
      host: 127.0.0.1
      port: 6379
  kafka:
    bootstrap-servers: 127.0.0.1:9092
    ...
app:
  jwt:
    secret: javaweb-demo-secret-key-please-change-in-prod-32b
    ttl-minutes: 120
```

`server:` 后接 `port: 8084`。键名与 starter 一一对应：`spring.data.redis` ↔ 数据 Redis 的自动装配剧本，`app.jwt` 是**自定义键**（由 `JwtUtil` 用 `@Value` 读——预览 IoC 的另一面，S02 详谈）。

## 四、动手验证：第一次"空转跑起来"

### 1）一键起全栈

```bash
cd ~/Projects/javaweb
bash infra/build.sh        # 首次需要：编译 5 个 jar + 2 个前端 dist
bash infra/start-all.sh
```

真实输出（本机实测）：

```text
== 1) Redis ==
redis: PONG
== 2) Kafka (KRaft 单节点) ==
kafka: up
== 3) 后端 ×5 ==
  demo-todo: up (8081)
  demo-chat: up (8082)
  demo-counter: up (8083)
  train: up (8084)
  takeaway: up (8085)
== 4) Nginx（静态站 + 反代）==
nginx: 已热重载
```

`start_jar()`（`infra/start-all.sh:38-46`）的巧思值得一读：

```bash
start_jar() { # name port jarfile
  local name=$1 port=$2 jar=$3
  (echo > /dev/tcp/127.0.0.1/$port) 2>/dev/null && { echo "  $name 已在线（$port）"; return; }
  ...
  for i in $(seq 1 40); do (echo > /dev/tcp/127.0.0.1/$port) && break; sleep 0.5; done
}
```

用 bash 内建的 `/dev/tcp/127.0.0.1/$port` 作 TCP 握手探测——**端口在线就意味着服务活着**，因此脚本幂等：重复执行只补缺席的服务，不会端口冲突。

### 2）demo-todo 通不通

```bash
curl -s http://127.0.0.1:8081/api/tasks
```

真实输出：

```json
[]
```

空数组——此刻数据库里一行待办都没有。提交一条走完整闭环：

```bash
curl -s -X POST http://127.0.0.1:8081/api/tasks \
     -H 'Content-Type: application/json' -d '{"title":"买牛奶"}'
```

真实输出：

```json
{"id":8,"title":"买牛奶","done":false}
```

（id 随数据库累积滚动，不同数字正常。）

### 3）官方冒烟：22 条断言全绿

```bash
python3 infra/smoke.py
```

真实输出（尾部）：

```text
== nginx (9090) ==
  ✓ train 静态站 200
  ✓ takeout 静态站 200
  ✓ 反代 /apitrain → 8084
  ✓ 控制台首页 200

smoke: 22 通过 / 0 失败
```

这 22 条覆盖 5 个服务、Kafka 在线、两条 API 主链与 nginx 反代。**本系列后续每课的动手验证，环境有怀疑时都可重跑它。**

## 思考题
1. 把 `TodoApp` 从 `com.javaweb.todo` 挪进 `com.javaweb.todo.app`，而 Controller 留在原处，`curl /api/tasks` 还通吗？为什么？（提示：`@ComponentScan` 以谁为根）
2. 删掉 `application.yml` 里 `server.port: 8081`，服务起在哪个端口？删掉整个 `datasource` 段会怎样（jpa 的数据源找不到）？
3. 你只改 `pom.xml`（加一个 starter），没加任何代码，`@SpringBootApplication` 会发生什么？"新依赖突然生效了"举例一个真实场景。

## 练习题

1. 仿照 `TodoApp` 结构写一个 `HelloApp`（包 `com.javaweb.hello`）：起在 9091，`curl http://127.0.0.1:9091/api/hello` 返回 `{"msg":"hi"}`。（Controller 怎么写可提前偷看 S04，或用本课构造出的最小 Controller 模板。）
2. 精读 `infra/start-all.sh` 的 `start_jar()`：写一段 bash（或伪码）说明"端口在线→跳过"的探测原理，并解释为何它让脚本天然**幂等**。
3. 打开 demo-todo 的启动日志 `logs/demo-todo.log`，找出三行：确认 Tomcat 行、确认 Hikari 数据源行、确认 `Started TodoApp`。它们分别对应本课三本账本中的哪一本？

### 参考答案

1. 只要把启动类放顶层包（如 `com.javaweb.hello.HelloApp`），controller 放 `com.javaweb.hello.controller` 即可被扫到；方法可直接写最小模板：

```java
@RestController
public class HelloController {
    @GetMapping("/api/hello")
    public Map<String, Object> hello() { return Map.of("msg", "hi"); }
}
```

`application.yml` 配 `server.port: 9091`。Maven 侧拷贝 demo-todo 的 pom 精简版即可。

2. `start_jar` 用 `echo > /dev/tcp/127.0.0.1/$port` 探测端口：连通成功 = 已有服务占用，脚本立刻 echo 并 return（跳过启动）；这是 bash 内建的 TCP 客户端能力。重复执行 `start-all.sh` 时，已在线的服务全部走"已在线（$port）"分支，只有缺席服务被补启——这就是**幂等**。

3. 日志里（真实行，关键字可匹配）：
- `Tomcat started on port 8081` → 账本 1（自动装配注册内嵌服务器）；
- `HikariPool-1 - Starting...`/`...Start completed.` → 账本 1（数据源自动装配）；
- `Started TodoApp in 2.8 seconds` → 账本 3（refresh 收官标志）。

## 本节小结
- `@SpringBootApplication` = 自动装配 + 扫描 + 配置类，三本账分工清楚。
- starter 是"关键字"：你 jar 里带的货决定装什么；`application.yml` 只做默认值微改。
- demo-todo 的最小闭环（11 行启动类 + 1 行 fork + 14 行 yml）是一切后续课程的"地基样板"。
- 服务器"空转"起来了——但 curl 进来是谁在工作？下一站拆**对象谁来造**。

**下一站：S02 IoC 与 DI：不再手动 new。**
