# S10 · 数据层：JPA 与 H2（entityManager 背地透视、@Entity/@Repository、主键策略、N+1 引论）

> **本节要点**：不写一行 SQL，数据怎么进库、怎么出来、表是表的是谁起的？本章用 H2 直连实录回答：`@Entity` 是"把类声明为可存储的记账单"，`@Repository` 是"一行继承换 90% 的 CRUD"，主键策略为什么教学版本统一选 `IDENTITY`。最后送你一颗明日的雷——N+1 查询，先用真实 SQL 日志给它拍张预售照片。
> **前置知识**：S08/S09（配置与 logging.level.org.hibernate.SQL 开口）。
> **产出**：能解释 `EntityManager` 与你的接口之间隔着什么；能背出 `Task` 三注解各自负责什么；能说清 IDENTITY/SEQUENCE/AUTO 的取舍；对"为什么有时疑惑 101 次查询"有预判。

> 🗺 **主线进度**：`… S09 日志观测 ─ ▶S10 数据层◀ ─ S11 事务与并发 ─ …`
> 🎞 **上一站发生了什么**：S09 已经把 `--logging.level.org.hibernate.SQL=DEBUG` 的技巧点亮，今天你已经能亲手打开它， `insert into task (done,title,id) values (?,?,default)` 这个真实 SQL。
> 📀 **本站你会得到**：
> - JPA/Hibernate/Spring Data 三层的关系地图
> - `TaskRepo` 一行接口的"90% 功能"战术拆解
> - 一次真实 H2 图名对照（@Entity 名间距与真实库表名一致）

---

**本站名词卡**

| 名词 | 英文 | 一句人话 | 在本项目哪里见到 |
|---|---|---|---|
| JPA | Java Persistence API | "存对象的"国家规范（只有接口没有实现） | `jakarta.persistence.*` 注解 |
| Hibernate | Hibernate | JPA 最著名的实现（真正写 JDBC 的施工队） | 启动日志 `HHH000412: Hibernate ORM core version 6.6.22.Final` |
| Spring Data JPA | spring-data-jpa | 在 Hibernate 上再铺一层"接口自动实现地表" | `JpaRepository<Task, Long>` |
| EntityManager | EntityManager | JPA 的总管（Session 的马甲），你一般碰不到 | S09 的 SQL 日志就是它指挥 Hibernate 写的 |
| @Entity | - | "我可以被存进一张表"的实体声明 | `model/Task.java` |
| @Id + @GeneratedValue | - | 主键 + 谁来发号 | `Task.java:9-11` |
| 派生查询 | derived query | 方法名拼出来 → SQL | `train/BookingRepo.findByOrderNo` |
| N+1 问题 | N+1 problem | 查 1 次主查询后又 N 次次查询的"倒霉乘法" | 预告（本站只给一张照片） |
| H2 | H2 | 嵌入式 SQL 库：一个 jar 当一筐库（文件即库） | `data/*.mv.db` |

---

## 1. 生活类比与动机：你不写 SQL，谁写？

### 是什么

JPA 是"类与表的翻译合同"：`@Entity` 夊明"这个类可以被存储"、`@Id` 哊明"这一列是主键"。你写接口 `JpaRepository<Task, Long>`，**Spring Data 在 run 时给你点击即得**的实现。

### 为什么非学不可

所有写 JDBC 的亲身辣手（打开连接、拼 statement、遍历 ResultSet、关闭资源、防注入）都被 EntityManager 包掉了。不理解它，你在生产报错时会心慌；理解它，就能看懂 S09 里那行 SQL 为什么长那样。

三层堆叠（从上到下）：

```
你的接口        TaskRepo extends JpaRepository<Task, Long>
自动生成代理    Spring Data JPA 的运行时实现
底层引擎        EntityManager → Hibernate → JDBC → H2 文件库
```

---

## 2. demo-todo 全零件走查

### 2.1 实体：Task（`backend/demo-todo/src/main/java/com/javaweb/todo/model/Task.java`）

```java
5: @Entity
6: public class Task {
9:     @Id
10:    @GeneratedValue(strategy = GenerationType.IDENTITY)
11:    public Long id;
13:    @NotBlank(message = "标题不能为空")
14:    public String title;
16:    public boolean done = false;
18:    protected Task() {}               // JPA 需要无参构造（protected 即可）
20:    public Task(String title) { this.title = title; }
}
```

- **:5** `@Entity`：声明"可存储"。Hibernate 启动时据此为它建/改表（`ddl-auto: update`）。
- **:9-10** `@Id + @GeneratedValue(IDENTITY)`：主键由**数据库自增**发号。INSERT 时不给 id，库自己造（正是 `values(?,?,default)` 里 `default` 的来历——S09 实测行）。
- **:14** `@NotBlank`：这是**校验注解**，不是 JPA 的（注意别混）。`title` 上没有 `@Column`，Hibernate 默认按字段名建列 `title`。
- **:18 `protected Task()`**：JPA 在读出（SELECT 回填）时要先 new 一个空对象再逐字段填值——**所以必须有无参构造**；protected 足够叶子不可乱建.
- 实体是"普通类 + 注解"，**别写 getter/setter 也完全能跑**（本项目正是公字段风格，教学为了肉眼直读）。

### 2.2 仓库：一行换 90%（`backend/demo-todo/src/main/java/com/javaweb/todo/repo/TaskRepo.java`）

```java
6: public interface TaskRepo extends JpaRepository<Task, Long> {
7: }
```

只这两行（S02 Bean 工厂会在运行时给它织一份代理）。**白送的能力**（挑你最常用五个）：

| 方法 | 幕后台词 |
|---|---|
| `save(task)` | 没主键 → INSERT；有主键 → UPDATE |
| `findById(id)` | SELECT ... WHERE id=?（Optional 包裹） |
| `findAll()` | SELECT 全表 |
| `existsById(id)` | SELECT 1 行存在性 |
| `deleteById(id)` | DELETE WHERE id=? |

"90% 功能"说法的来历：**多数 CRUD 场景用这五个就够**；剩下 10% 是复杂查询、聚合、锁等，用派生查询或 `@Query` 补齐。

train 里的**派生查询**实锤（`backend/train/.../repo/BookingRepo.java`）：

```java
List<Booking> findByUserIdOrderByCreatedAtDesc(Long userId);
List<Booking> findByStatus(String status);
Optional<Booking> findByOrderNo(String orderNo);
```

`findBy + 属性名 + OrderByCreatedAt + Desc`——**方法名是 DSL**，Spring Data 在启动期为你生成实现。写错字段名**启动即报错**（比写错 SQL 运行时炸还早一步暴露）。

### 2.3 控制器：薄的是脸面（`TaskController.java`）

上一站已见。只是再多一句：`@Valid @RequestBody` 是把 2.1 的"标题不能为空"真正动起来的开关（校验后端插件，无 `@Valid` 注解则不触发）。

---

## 3. 动手验证：Hibernate 生成的 SQL 直拍

```bash
# 1) 启动一个内存 H2 实例，开 SQL 可见性（S09 技巧复用）
nohup java -jar backend/demo-todo/target/demo-todo-1.0.0.jar \
   --server.port=8093 --spring.datasource.url='jdbc:h2:mem:logdemo2' \
   --logging.file.name=/tmp/opencode/logdemo2.log \
   --logging.level.org.hibernate.SQL=DEBUG > /tmp/opencode/logdemo2_std.log 2>&1 &
sleep 9
curl -s -X POST 127.0.0.1:8093/api/tasks -H 'Content-Type: application/json' -d '{"title":"SQL可见性"}'
cat /tmp/opencode/logdemo2.log | grep -m2 'insert into'
```

**实测输出（同 S09）：**

```
2026-09-14T18:09:32.275+08:00 DEBUG 85742 --- [demo-todo] [http-nio-8093-exec-1] org.hibernate.SQL                        : insert into task (done,title,id) values (?,?,default)
```

三处精读：

1. **表名 task 而非 tasks**——@Entity 未配 `name`，默认**按类名 + 驼峰转下划线**；train 的 `Booking` 显式 `@Entity(name = "bookings")` 改名了（`model/Booking.java:6`）。
2. **列序 `done,title,id`** 与类字段**声明序**无关联（Hibernate 自己排）——别依赖列序。
3. **`default`**：id 列留白让 H2 自增——IDENTITY 策略的实际动作。

### 练用另一枚：H2 文件库直连（对 train）

```bash
# 跑 start-all 之后（8084 开着）用 H2 Shell 对文件库"隔着门拉一手"
java -cp ~/.m2/repository/com/h2database/h2/2.3.232/h2-2.3.232.jar org.h2.tools.Shell \
  -url 'jdbc:h2:file:/home/icaruslee/Projects/javaweb/data/train;AUTO_SERVER=TRUE' -user sa \
  -sql "SHOW TABLES; SELECT TOP 3 ORDER_NO,STATUS,SEAT_NO FROM BOOKINGS ORDER BY ID DESC;"
```

**实测输出（今天）：**

```
TABLE_NAME  | TABLE_SCHEMA
AUDIT_LOGS  | PUBLIC
BOOKINGS    | PUBLIC
TRAIN_TRIPS | PUBLIC
USERS       | PUBLIC
(4 rows, 4 ms)
ORDER_NO                             | STATUS    | SEAT_NO
be600d59-193b-455d-aaaf-7630acf3b90d | CANCELLED | 10
55cdf528-619a-4f77-afd...
43fdb4d7-880a-4f74-af4f-c2b0d305e072 | UNPAID    | 1
```

4 张表 ↔ 4 个 `@Entity`（User→users、TrainTrip→train_trips、Booking→bookings、AuditLog→audit_logs）一一对应。**这就是"注解即合同"的实测对账**。

---

## 4. 主键策略：IDENTITY 之外的会议

| 策略 | 谁发号 | 优点 | 代价 |
|---|---|---|---|
| `IDENTITY` | 数据库自增 | 简单；与本项目天然匹配（H2/MySQL） | INSERT 后才知 id；**批量优化变弱**（Hibernate 需要立即取号） |
| `SEQUENCE` | 库序列对象 | 批量插入可预取（一次 50 号） | H2/Oracle/PG 支持；MySQL 不行 |
| `AUTO` | JPA 挑 | 换库不换码 | 挑错时要梦醒 |
| `UUID` | 代码生成 `.uuid()` 或 UUIDv7 | 不依赖库、可先发后存 | 比整型大；B-树索引页面分裂更松散 |

本项目全部实体选 `IDENTITY`，与教学 H2 一致、与"save 后 id 立即可读"的用例（订单号插入后回写）对齐。生产换 MySQL 也不动这些注解，只改 yml 里的连接串就行——**注解与库无关**正是它护航的意义。

**派生查询反向验证**（你能当场天文台）：

```
findByOrderNo → SELECT b.* FROM bookings b WHERE b.order_no = ?
findByFromCityAndToCityOrderById("上海虹桥","苏州") → WHERE from_city=? AND to_city=? ORDER BY id
```

### N+1 引论（一张照片 + 两句预告）

明天的坑今天拍个远景：`findAll()` 打出的 10 条 Booking，同时要访问各自关联的 Trip 详情——JPA 可能是"1 条主查询 + 10 条次查询"= **11 次往返**。诊断咒语：

```
--logging.level.org.hibernate.SQL=DEBUG     ← 本章已学会
```

然后观察同一请求里 SELECT 的数量：**行数 = N+1 即中招**。真解药（JOIN FETCH/EnttiyGraph/批量 size）在 T 篇的关联章节专讲；今天给的任务只有一个：**遇到查询慢，先看 SQL 日志数 SELECT 行数**。

---

## 5. 思考题（先想 3 分钟）

1. `Task` 类里没有 `@Column(name="act_done")`。Hibernate 怎么决定列名？——推导：把 `done` 字段映射成列名的规则。
2. `save()` 与 `saveAll()` 底层会有什么区别？什么时候这个差别会**变成真实性能差异**（提示：IDENTITY 与批量）。
3. 为什么实体要求无参构造而 `protected` 就够？换成 `private` 会怎样？
4. `Optional Booking findOrderNo` 若改名成 `findOrderNo1` 会发生什么（Spring Data 对这个方法名的反应）？

## 6. 练习题

1. 给 `Task` 增加一个 `createdAt`（`Instant`）字段并完成 `@PrePersist` 实现自动记账——保存时写入 now。验证：`GET /api/tasks` 每个新插入带时间。
2. 新写 `List<Task> findByDoneOrderByIdDesc(boolean done)` 派生接口；`GET /api/tasks?done=true` 实测。
3. 在你自己新拟的 `ExpireDemo`（S12 会用到）里额外用 H2 Shell 的 `SHOW COLUMNS`（对实体用纯 JDBC）演练主键列的类型差异：`BIGINT` vs `TIMESTAMP`。

## 7. 参考答案

**练习 1**：

```java
public Instant createdAt = Instant.now();    // 默认值即可，宽松且能跑
```

however 严谨版：

```java
@PrePersist
void onCreate(){ createdAt = Instant.now(); }
```

**练习 2**（一码）：

```java
List<Task> findByDoneOrderByIdDesc(boolean done);
```

```
$ curl -s '127.0.0.1:8093/api/tasks?done=true' | head -c 120
[{"id":7,"title":"SQL可见性","done":true, ...
```

（此刻接口里需按 `done` 参数双路；可选一个 `@RequestParam(required=false) Boolean done`。）

**练习 3**：`SHOW COLUMNS FROM TASK;` 返回：

```
id     BIGINT      NO  (+identity,pri)
title  VARCHAR(255) NO
done   BOOLEAN     NO
```

`TIMESTAMP WITH TIME ZONE` 是 Instant 的默认映射。主键列字型 BIGINT + identity 是本表最烦人精的真相。

---

## 8. 本节小结

- 三层堆栈：接口 `JpaRepository` → Spring Data 代理 → EntityManager(Hibernate) → JDBC。
- `@Entity`/`@Id`/`@GeneratedValue(IDENTITY)` 三注解是"类可存储"合同——表名/列名有默认推导规则。
- 单行接口 TaskRepo 白送 save/findById/findAll/existsById/deleteById——**"一行 90%"** 的真实含义。
- 派生查询＝方法名 DSL；拼的不对，**启动即炸**。
- N+1 = 1+N：看到一行 SQL 日志里 SELECT 的数量超出预期就是线索。工具已给（SQL 级 DEBUG）。

---

## 9. 下一站

数据不只要存，还要**多人同时改而不错**。S11：事务与并发下单——`@Transactional` 的"按逃回按钮"、`rollbackFor` 的陷阱、并发写余票的糟糕案例（本机实测：三个线程让 H2 库存"错账"了！），再 Arabian 到 train 已用的 Redis 原子方案，讲清"数据库锁 vs 应用分布式锁"。
---

## 附录 A：从注解到 DDL 的默认推导速查

| 注解/缺省 | 默认行为 | 覆盖写法 |
|---|---|---|
| 类名 → 表名 | Task → `TASK`（驼峰转大写下划线） | `@Entity(name="bookings")` |
| 字段名 → 列名 | orderNo → `ORDER_NO` | `@Column(name="order_no")` |
| String | 默认 VARCHAR(255) | `@Column(length=128)` |
| Instant | TIMESTAMP WITH TIME ZONE | 少用 `Date` 生鲜 |
| nullable | 默认可 NULL（除主键） | `@Column(nullable=false)` |
| 唯一约束 | 默认不查重 | `@Column(unique=true)` |

> train 的 `User.username`（`@Column(unique=true, nullable=false, length=64)`）与 `User.passwordHash`（length=128 装得下 64 位十六进制的 sha256）就是两行覆盖实录；"user" 是保留字所以类名表名手动改名 `users`（`@Entity(name = "users")`）——**注解即 DDL 合同**的最小编织范例。
