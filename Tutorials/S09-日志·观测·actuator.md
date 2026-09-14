# S09 · 日志·观测·actuator（logback 格式、级别开关、logging.file、health/metrics）

> **本节要点**：请求回 500 只是结果，你要的是**过程**。本章把 Spring Boot 的"记账本"拆开：logback 一行日志七个字段的 anatomy、级别层级（DEBUG/INFO/WARN/ERROR）、`logging.file.name` 怎么让 Spring 自己写文件（而不是靠 shell 重定向）、以及 actuator 的 health/metrics 怎么把"店况体检"暴露成 HTTP 端点。全部用本机 `logs/*.log` 与一次实测 actuator 的真实输出逐格拆。
> **前置知识**：S08（配置与命令行覆盖）。
> **产出**：能读取一行日志说清"谁、何时、在哪个线程、以什么级别说了什么"；会开/关一个包的 DEBUG；会给 demo-todo 装上 actuator 并实测四个端点。

> 🗺 **主线进度**：`… S08 配置 ─ ▶S09 日志观测◀ ─ S10 JPA ─ S11 事务 ─ …`
> 🎞 **上一站发生了什么**：S08 用 `--server.port=7084` 换端启动了第二个 train——那个启动日志里的 `INFO ... profile is active: "dev"` 就是本章主角。
> 📀 **本站你会得到**：
> - 一行日志七字段的精确读法
> - `logging.level.*` 级别开关（含 Hibernate SQL 可见性的开关实录）
> - actuator 从依赖到端点的四步实录（health 带组件详情、64 个 metrics、未开端点的 404）

---

**本站名词卡**

| 名词 | 英文 | 一句人话 | 在本项目哪里见到 |
|---|---|---|---|
| 日志门面/实现 | slf4j / logback | 记账用垫板（门面）/ 真正的笔（实现） | Maven 依赖树里两者都在 |
| 日志级别 | log level | 事件的"嗓门"：TRACE→DEBUG→INFO→WARN→ERROR | `logs/train.log` 里 `INFO`/`WARN`/`ERROR` |
| logger 名 | logger name | 记账本的"户头"，通常是类全名（缩写版） | `com.javaweb.train.TrainApp` |
| root 级别 | `logging.level.root` | 全场默认嗓门（默认 INFO） | 不用配它就静默 |
| 包级覆盖 | `logging.level.<pkg>` | 只给某家户"调大嗓门" | `--logging.level.org.hibernate.SQL=DEBUG` |
| 文件落盘 | logging.file.name | 让 Spring 别只写控制台，也要写文件 | 本站实测 `--logging.file.name=...` |
| actuator | actuator | 体检端点：health 是体温计，metrics 是体重秤 | 本章实测 `/actuator/health` 等 |
| 端点白名单 | exposure.include | 体检套餐选择器：默认只开放 health | `exposure.include=health,metrics` |

---

## 1. 生活类比与动机：对账单

### 是什么

日志是**带时间戳的事件流水**：谁（logger）、何时（时间戳）、在哪个线程（线程名）、以多大声（级别）、说了什么（消息）。出错时排障的 90% 从"看日志"开始。

### 为什么这个格式非看不可

拿 train 今天的真实一行（`logs/train.log`）：

```
2026-09-14T17:56:43.070+08:00  INFO 81746 --- [train] [ntainer#0-0-C-1] org.apache.kafka.clients.NetworkClient   : [Consumer clientId=consumer-audit-1, groupId=audit] Node -1 disconnected.
```

七个字段依次是：

| 字段 | 本例 | 读法 |
|---|---|---|
| 时间戳（ISO+时区） | `2026-09-14T17:56:43.070+08:00` | 精确到毫秒 |
| 级别（右对齐 5 格） | ` INFO`（WARN/ERROR 占更宽） | **对齐是刻意的**：肉眼扫"不是 INFO 的行"更快 |
| PID | `81746` | 同机多进程对号入座（`kill 81746` 直接用） |
| 应用名 | `[train]` | 来自 yml 的 `spring.application.name` |
| 线程 | `[ntainer#0-0-C-1]`（Kafka listener 容器线程的截断名） | 出问题先看在哪个线程：请求线程、定时线程、Kafka 消费线程行为差异巨大 |
| logger（截断到 40 格） | `org.apache.kafka.clients.NetworkClient` | **谁写的**这一行，级别开关就按这名字配 |
| 消息 | `Connection to node -1 ... could not be established` | 正文（可能是异常栈的第一行） |

读法总结：**"谁 + 何时 + 何处 + 什么声量 + 什么话"**。这一行是 Kafka 不可达时的告警实锤——正是我们在 S11 前排障时喝到的一口。

---

## 2. 概念精确化：级别是"嗓门"，配置是"门槛"

每个日志条目带级别。带级别开关后，**低于阈值的直接不写**：

```
TRACE < DEBUG < INFO < WARN < ERROR
（默认 threshold=root=INFO：低于 INFO 的 DEBUG/TRACE 全部哑音）
```

**本项目三种风格的真实行**（全部出自今天实测）：

```
INFO : Tomcat initialized with port 8084 (http)                        ——正常生牌
WARN : Bootstrap broker 127.0.0.1:9092 disconnected                    ——小事故自查：有降级方案或会重试
ERROR: Servlet.service() ... threw exception [..已售罄] with root cause ——真事故：跟栈
```

**级别开关语法**（这是今天最重要的实测命令）：

```
--logging.level.<logger名>=<级别>
```

- logger 名是**前缀匹配**：`com.javaweb` 一键管住自己全部；`org.hibernate.SQL` 只管 SQL 一家。
- 级别可调多细：把某个类单独抬到 DEBUG，别的一律不动。

**两块文件墙的区别（重要）**：本项目 `logs/*.log` 是**shell 重定向**（`start-all.sh` 里 `nohup java -jar ... > logs/train.log`）放进去的——**不是 logback file appender**。让 Spring 自己写文件要用：

```yaml
logging:
  file:
    name: logs/spring.log      # Spring 自己会创建文件并滚动管理
```

两者差别：shell 重定向连**标准输出/系统报错**都灌进文件（包括 JVM 报错），logback 写文件只写**框架日志**、可配滚动（按天/按大小）与历史清理。生产必须 logback 方案。

---

## 3. 动手验证一：让 Hibernate 的 SQL 打出来（debug 级别覆盖）

demo-todo 平时只显示 app 内的 INFO。现在把 org.hibernate.SQL 这户人家抬到 DEBUG，把 SQL 暴露：

```bash
cd /tmp/opencode
nohup java -jar ~/Projects/javaweb/backend/demo-todo/target/demo-todo-1.0.0.jar \
  --server.port=8093 --spring.datasource.url='jdbc:h2:mem:logdemo2' \
  --logging.file.name=/tmp/opencode/logdemo2.log \
  --logging.level.org.hibernate.SQL=DEBUG > /tmp/opencode/logdemo2_std.log 2>&1 &
sleep 9
curl -s -X POST 127.0.0.1:8093/api/tasks -H 'Content-Type: application/json' -d '{"title":"SQL可见性"}'
```

**实测（把关屏喝水，日志进文件 `logdemo2.log`，40 行内见证了这次"SQL可就看见了"）：**

```
2026-09-14T18:09:32.275+08:00 DEBUG 85742 --- [demo-todo] [http-nio-8093-exec-1] org.hibernate.SQL                        : insert into task (done,title,id) values (?,?,default)
2026-09-14T18:09:32.315+08:00 DEBUG 85742 --- [demo-todo] [http-nio-8093-exec-2] org.hibernate.SQL                        : insert into task (done,title,id) values (?,?,default)
```

两次 POST，两行 insert——**一行 = 一条 INSERT**。`values(?,?,default)` 里的 `?` 是参数化标记（防注入），`default` 对应 `id`（IDENTITY 由库自增）。S10 的 JPA 教程会在这行 SQL 里继续深挖。

顺手证据：源自 spring 本身的印痕那一条 INFO（`logdemo.log` 首行，同型，同格式）：

```
2026-09-14T18:08:25.567+08:00  INFO 85644 --- [demo-todo] [main] com.javaweb.todo.TodoApp                 : Starting TodoApp v1.0.0 ...
```

---

## 4. actuator：给店装体检端点

### 四步实录（本机全部实测）

1）**加依赖**（`backend/demo-todo/pom.xml`）：

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-actuator</artifactId>
</dependency>
```

2）**重新打包并换端启动**（内存 H2，不干扰生产件）：

```bash
mvn -q -pl demo-todo package -DskipTests
nohup java -jar demo-todo/target/demo-todo-1.0.0.jar --server.port=8091 \
    --spring.datasource.url='jdbc:h2:mem:actudemo' \
    --management.endpoints.web.exposure.include=health,metrics \
    --management.endpoint.health.show-details=always > /tmp/opencode/actu.log 2>&1 &
```

3）**实测 health**（`curl 127.0.0.1:8091/actuator/health`）——组件级体检：连 H2 是好、硬盘还剩多少都报给你看：

```json
{"status":"UP","components":{
    "db":{"status":"UP","details":{"database":"H2","validationQuery":"isValid()"}},
    "diskSpace":{"status":"UP","details":{"total":486163906560,"free":406925053952,
        "threshold":10485760,"path":"/home/icaruslee/Projects/javaweb/.","exists":true}},
    "ping":{"status":"UP"},
    "ssl":{"status":"UP","details":{"validChains":[],"invalidChains":[]}}}}
```

四个下游零件（db/disk/ping/ssl）**各守一署**；只要其中一个 DOWN，整体就是 DOWN。生产运维拿它当"救生阀"：LB 探针只看这一个端点就明白全家几何。

4）**实测 metrics**：`curl .../actuator/metrics` 返回 **64 个度量**，前 8 个是：

```
application.ready.time, application.started.time, disk.free, disk.total,
executor.active, executor.completed, executor.pool.core, executor.pool.max
```

点一个看细：

```
$ curl -s 127.0.0.1:8091/actuator/metrics/jvm.memory.used
{"name":"jvm.memory.used","description":"The amount of used memory",
 "baseUnit":"bytes","measurements":[{"statistic":"VALUE","value":236989688.0}],
 "availableTags":[{"tag":"area","values":["heap","nonheap"]}, ...]}
```

`236989688 bytes ≈ 226 MB`——这就是 JVM 刚启动完的内存用量。metrics 随时可 `Prometheus` 化，但白名单要先开。

**没白名单的端点会怎样（实测）**：

```
$ curl -s -o /dev/null -w '%{http_code}\n' 127.0.0.1:8091/actuator/env
404          ← 我们只开放了 health,metrics；env 会泄漏配置，必须不默认开
```

### 白名单意识（生产三大戒）

- 默认只开 `/actuator/health`；`env`/`heapdump` 这类端点**绝不**默认开（`env` 会晒出你的密钥）。
- 上生产给 actuator 端点单独开一个管理端口（`management.server.port`）并防火墙拦内部。
- `show-details=always` 在健康检查后面**站的是运维**，别给公网看组件细节。教学版为了给你看细节才开的。

---

## 5. 动手验证：查 logs/*.log 的真实格式

```bash
cd /home/icaruslee/Projects/javaweb

# 1) 逐字段读一次 8083 计数器的启动日志
grep "Tomcat initialized" logs/demo-counter.log          # 级别是 INFO，含端口

# 2) 找一条 WARN（Kafka 不在时 train 的反复重连线）
grep -m1 -A1 "could not be established" logs/train.log    # 看线程名：[ntainer#0-0-C-1]

# 3) 找一条 ERROR + 栈（下单余票尽时的真实碎片）
grep -m1 -A6 "已售罄" logs/train.log | head -8            # 栈从 "java.lang.IllegalStateException" 开始

# 4) 对比文件墙：logdemo.log 是 shell 重定向；logdemo2.log 是 --logging.file.name
head -2 /tmp/opencode/logdemo2.log                        # 同样格式、来自框架文件 appender
```

在看栈时优先看**最后一行 Caused by: 那根**（根因），栈中段先粗读不用逐行。

---

## 6. 思考题（先想 3 分钟）

1. 每行日志开头第一个非空字符是**时间戳**；第二个列右对齐是**级别**。为什么级别要右对齐且占 5 格？（提示：view 眼动。——同宽对齐让"不需要的行"在屏幕上形成同一条竖脉络。）
2. 为什么 `logging.level.root=DEBUG` 是危险的？（三条后果：磁盘/噪声/泄密。）
3. 我们的 `logs/train.log` 里 shell 重定向与 logback file appender 差在哪儿？如果运行的进程被 kill -9，两种方案的最后一行日志形态一样吗？
4. actuator 的 health 为什么默认只开 health 而不开 metrics？

## 7. 练习题

1. 给 train 的启动命令拼上 `--logging.file.name=logsmo/train-file.log` 并实测：重启后 shutdown 时确认两处都在写（shell 重定向 log + logback 文件）。
2. 写一个 `logging.level.com.javaweb.train.service=DEBUG`：让 BookingService 的报错同时**写进文件**（结合 logback 前技）。实测在 `grep "closeExpired"` 时能看到第二条记录。
3. 把 actuator 里的 `/actuator/metrics/http.server.requests` 当秒表用：连续调用十次 `/api/trips`，再查 metrics，看 `COUNT` 与 `0.99` 百分位差多少。**思考**：这和手测 curl 的耗时差异是怎么回事。

## 8. 参考答案

**练习 1**（关键步）：

```bash
nohup java -jar backend/train/target/train-1.0.0.jar \
  --logging.file.name=logs/train-file.log ... > logs/backup.log 2>&1 &
```

（注意 working dir 要在项目根，相对路径才对。实测：两份日志各有内容。生产建议：logback 版本配置按天滚动，shell 重定向仅做开发期。）

**练习 2**（同上，把第二家也开白名单）。注意 logback 滚动对 `logging.file.name` 已内置按大小的清理；若要精细控制要另写 `logback-spring.xml`。本册不深入，够用即可。

**练习 3**（核心点）：
- metrics 的 `http.server.requests` 记录**服务端自内部计时**（含序列化）。
- 一次 `curl` 的耗时 = 客户端发起 + 网络线程等待 + 服务端处理 + 返回等待全身。所以 curl 抢的最高耗时（毫秒脉冲）会常常高于 metrics 的 SUM 量刑——**别争论别冤枉服务端**，看 metrics 的 count/quantiles 才是"自家店内"数据。

---

## 9. 本节小结

- 一行日志七格：时间戳 / 级别 / PID / 应用名 / 线程 / logger / 消息；**级别右对齐 5 格**是为了肉眼扫异常更快。
- 级别是"嗓门"：`--logging.level.org.hibernate.SQL=DEBUG` 一条实锤 SQL 出现在 DEBUG 行（实测行已见）。
- `logs/*.log` 是 shell 重定向；生产要用 `logging.file.name` + logback 滚动。
- actuator 是可白名单的监控端点：health 报"零件好没好"，metrics 报"数字账本"（64 个，本机实测），默认不开放的 404 才是对的。

---

## 10. 下一站

数据回到了店里，往哪儿住？S10：数据层·JPA 与 H2——`@Entity`、`@Repository`、主键生成策略，以及 `EntityManager` 背地里的"落地痕迹"（Hibernate 的真实 INSERT 语句我们已经得到了）。