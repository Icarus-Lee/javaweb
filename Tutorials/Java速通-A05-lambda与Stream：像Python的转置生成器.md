# Java速通-A05 · lambda 与 Stream：像 Python 的转置生成器

> Python 里 `[f(x) for x in xs if p(x)]` 你写了一万遍。Java 的 Stream 就是它的静态类型版：`xs.stream().filter(p).map(f).toList()`。这一站讲函数式接口、方法引用、管道三段式，并用 demo01 的真实数据做过滤实战。

---

## 🎬 开篇三件套

### 1️⃣ 本站你走在大厅哪一格

还记得 Z01 里那张请求旅程图吗？TripController 收到一列 `TrainTrip` 后要把它们"加工"成 JSON 友好的 Map 列表——这个加工过程就是一条 **Stream 流水线**：原料进（List<TrainTrip>）→ 加工（map）→ 成品出（toList）。本站你站在流水线旁，学操作机器。

### 2️⃣ 本站任务单

1. 理解函数式接口与 lambda 的关系（lambda 是接口实例的语法糖）；
2. 掌握四大方法引用（`类::方法`）；
3. 熟练 map/filter/collect 三段式，会 sort/distinct/limit；
4. 实战：对 demo01 任务列表全读、按标题过滤（真源码 TaskRepo 的 API）；
5. 知道 Stream 是惰性的、一次性的。

### 3️⃣ 开工前自查

- [ ] Python 列表推导式/生成器表达式用过
- [ ] A03 的 List/Map 与方法引用前的 `::` 笑过
- [ ] demo01（8081）在跑，`curl http://127.0.0.1:8081/api/tasks` 有数据

---

## 🗂 本站名词卡

| 名词 | 人话 | Python/C++ 对照 |
|---|---|---|
| **lambda** | 匿名函数：`(参数) -> 表达式` | Python `lambda x: ...` |
| **函数式接口** | 只有**一个**抽象方法的接口，lambda 的目标类型 | C++ 可调用对象概念 |
| **方法引用** | `System.out::println` = `x -> System.out.println(x)` | 无直接对应 |
| **Stream** | 数据源的惰性流水线 | Python 生成器管道 / C++ ranges |
| **中间操作** | filter/map/sorted/limit——返回新 Stream，**惰性** | 管道组装 |
| **终端操作** | collect/forEach/count/toList——真正干活 | 触发生成 |
| **Optional** | "可能有值"的容器，逼你处理空 | `optional<T>`（C++17） |
| **Collectors** | 收集器工具箱：toList/groupingBy/joining | 无（手写循环） |

---

## 🧠 概念人话

### lambda 的真身：函数式接口的实例

Java 没有独立函数，lambda 必须落在**函数式接口**（只有一个抽象方法的接口）上：

```java
Runnable r = () -> System.out.println("hi");    // Runnable：无参无返回
Comparator<Task> c = (a, b) -> a.title.compareTo(b.title);
Predicate<String> p = s -> s.startsWith("G");   // 一个参数返回 boolean
Function<String, Integer> f = String::length;   // 一进一出
```

对照 Python：lambda 是一等公民函数；Java 里它是**接口实例**（编译器帮你生成了匿名类）。常用内置接口背五个就够：

| 接口 | 签名 | 用途 |
|---|---|---|
| `Predicate<T>` | T→boolean | 过滤 |
| `Function<T,R>` | T→R | 转换 |
| `Consumer<T>` | T→void | 消费（forEach） |
| `Supplier<T>` | ()→T | 生产 |
| `BiFunction<T,U,R>` | (T,U)→R | 两参合并 |

### 方法引用：lambda 的缩写

| 形式 | 等价 lambda | 例子 |
|---|---|---|
| `类::静态方法` | `x -> 类.方法(x)` | `Integer::parseInt` |
| `对象::方法` | `x -> 对象.方法(x)` | `System.out::println` |
| `类::实例方法` | `(x, y) -> x.方法(y)` | `String::compareTo` |
| `类::new` | `x -> new 类(x)` | `Task::new` |

读法：**"拿这个名字的方法当函数用"**。`TripController.java:31` 的 `t -> { ... return m; }` 太长写不成引用；简单场景才缩。

### 管道三段式

```java
var titles = tasks.stream()          // 1) 源：List 变 Stream
        .filter(t -> !t.done)        // 2) 中间操作：惰性，返回 Stream
        .map(t -> t.title)           //    还是惰性
        .toList();                   // 3) 终端操作：真正遍历执行
```

Python 等价：`[t.title for t in tasks if not t.done]`。
**惰性**：不调终端操作，中间操作一行都不执行——像 Python 生成器，攒着不跑。
**一次性**：同一个 Stream 只能消费一次，二次消费抛 IllegalStateException（重新 `.stream()` 即可）。

### 常用操作速查

| 操作 | 干什么 | Python 对应 |
|---|---|---|
| filter | 挑 | if 子句 |
| map | 变换 | 表达式部分 |
| sorted / sorted(Comparator) | 排序 | sorted() |
| distinct | 去重 | set 去重（保序版） |
| limit(n) / skip(n) | 截取 | [:n] / [n:] |
| collect(toList()) | 收集成 List | list(...) |
| Collectors.groupingBy | 分组 | defaultdict 分组 |
| Collectors.joining(", ") | 拼字符串 | ", ".join() |
| count/anyMatch/allMatch | 统计/判定 | len/any/all |

---

## 🔍 真实代码走查

### 活例 1：TripController 的 map 变换

`backend/train/src/main/java/com/javaweb/train/controller/TripController.java:31-44`：

```java
31:         return trips.stream().map(t -> {
32:             Map<String, Object> m = new LinkedHashMap<>();
33:             m.put("id", t.id);
34:             m.put("trainNo", t.trainNo);
...
41:             String s = redis.opsForValue().get("train:trip:" + t.id + ":stock");
42:             m.put("stock", s == null ? 0 : Integer.parseInt(s));
43:             return m;
44:         }).toList();
```

一条 `stream().map(...).toList()`：每个 `TrainTrip` 变成一个 `LinkedHashMap`（多对一转换 + 顺带查 Redis）。Python 写法：`[to_view(t) for t in trips]`。**lambda 体超过 3 行就该抽成私有方法**（教学版内联了）。

### 活例 2：TaskRepo——数据源即集合

`backend/demo-todo/src/main/java/com/javaweb/todo/repo/TaskRepo.java:7`：

```java
public interface TaskRepo extends JpaRepository<Task, Long> {}
```

它白送的 API（A02 讲过）正是 Stream 实战的数据源：

- `findAll()` → `List<Task>`（全读）
- `findById(id)` → `Optional<Task>`
- `existsById(id)` → boolean

TaskController.java:22-24 的 `list()` 直接 `return repo.findAll();`——返回 List，Spring 自动转 JSON。**我们马上用 curl 拿到这份数据，再在 Java 里对它做过滤。**

### 活例 3：分页取最近 N 条（collect 的兄弟）

BookingController.java:54-58 的审计台：

```java
54:     public List<AuditLog> audits(@RequestParam(defaultValue = "5") int n) {
55:         Page<AuditLog> page = auditRepo
56:                 .findAll(PageRequest.of(0, n, Sort.by("id").descending()));
58:         return page.getContent();
59:     }
```

"最近 5 条"没有用 stream().skip/limit，而是让**数据库**分页——大数据量时在 DB 层截断远优于内存层。**Stream 是内存工具，别拿它假装 SQL。**

---

## 动手验证

### 实验 1：给 demo01 灌数据

```bash
$ curl -s -X POST http://127.0.0.1:8081/api/tasks -H 'Content-Type: application/json' -d '{"title":"买票"}'
$ curl -s -X POST http://127.0.0.1:8081/api/tasks -H 'Content-Type: application/json' -d '{"title":"订酒店"}'
$ curl -s -X PUT http://127.0.0.1:8081/api/tasks/1        # 把"买票"标记完成
$ curl -s http://127.0.0.1:8081/api/tasks
[{"id":1,"title":"买票","done":true},{"id":2,"title":"订酒店","done":false}]
```

### 实验 2：全读 + 过滤 + 变换（jshell 实战）

jshell 没法连 Spring 的 repo，我们用"从 API 拉来的真实数据"当源：

```java
jshell> record Task(Long id, String title, boolean done) {}      ← A02 的 record 当本地模型
jshell> var tasks = new ArrayList<>(List.of(
        new Task(1L,"买票",true), new Task(2L,"订酒店",false), new Task(3L,"买票回程",false)))

jshell> tasks.stream().map(t -> t.title).toList()                 ← 全读：只取标题
$3 ==> [买票, 订酒店, 买票回程]

jshell> tasks.stream().filter(t -> !t.done).map(t -> t.title).toList()
$4 ==> [订酒店, 买票回程]                                          ← 未完成的

jshell> tasks.stream().filter(t -> t.title.contains("票")).count()
$5 ==> 2                                                          ← 标题含"票"的

jshell> tasks.stream().filter(t -> !t.done)
              .sorted(Comparator.comparing(t -> t.title))
              .map(Task::title)                                   ← 方法引用版
              .collect(java.util.stream.Collectors.joining("、"))
$6 ==> "买票回程、订酒店"                                           ← 拼成一句话
```

四条管道对照 Python：

```python
[t.title for t in tasks]
[t.title for t in tasks if not t.done]
sum(1 for t in tasks if "票" in t.title)
"、".join(sorted(t.title for t in tasks if not t.done))
```

一模一样的思路，只是 Java 把"顺序"显式写成了管道节点。

### 实验 3：验证惰性与一次性

```java
jshell> var s = tasks.stream().filter(t -> { System.out.println("过滤" + t.title); return !t.done; })
jshell> s.count()          ← 此刻才真正执行（打印 3 行"过滤"）
jshell> s.count()
|  异常异常 java.lang.IllegalStateException: stream has already been operated upon or closed
```

第一条证据：`filter` 行执行时**零输出**——惰性。第二条：流用完即弃。

---

## 思考题

1. `tasks.stream().filter(a).map(b).toList()` 中 filter 和 map 谁先执行？是"先过滤完所有元素再 map"吗？
2. 为什么 BookingController 的审计台用数据库分页而不是 `stream().skip(n).limit(5)`？
3. `map(Task::title)` 和 `map(t -> t.title)` 等价吗？哪种更适合教学阅读？
4. Optional（`findById` 的返回值）和空 List（`findAll` 的返回值）表达"没有"的方式不同，为什么 API 作者要这样设计？

## 练习题

**练习 1**：对实验 1 的真实数据，用 curl 拿回 JSON，用 Python `json` 模块解析后完成与实验 2 相同的四条管道——体会两种语言的对应关系。

**练习 2**：在 jshell 里对 tasks 做"分组统计"：按 done 值分组，统计各组数量（`Collectors.groupingBy` + `counting()`）。

**练习 3**：仿照 TripController，写 `stream().map(...).toList()` 把 tasks 变成 `List<Map<String,Object>>`（键：id/title/done），用 LinkedHashMap 保证键序。

### 完整参考答案

**思考题 1**：逐元素垂直执行——第一个元素走完 filter→map 再轮到第二个（除非有 sorted 这类"全量操作"才退化为分阶段）。不是"先过滤完整表再 map"。可以打日志验证：在 filter 和 map 里各打印一行，输出是交替的。

**思考题 2**：数据在数据库里，`stream().skip/limit` 要先把**全部**行查进内存再丢弃——数据量大时是灾难。数据库 LIMIT/OFFSET 只取需要的那几行。原则：**能下推给数据库的就下推**，Stream 只加工已进内存的数据。

**思考题 3**：等价。方法引用更短，但读者要知道 title 是 Task 的字段访问器；教学场景 lambda 显式写出参数更直白。团队惯例：简单取字段/调方法用引用，有逻辑的用 lambda。

**思考题 4**：`findById` 查"单个东西"——"没有"是常态之一，用 Optional 逼调用方显式处理（orElseThrow/map/ifPresent）；`findAll` 查"一批"——"没有"就是空集合，空集合本身可安全遍历，无需特殊处理。**单数用 Optional，复数用空集合**——这是集合 API 设计的经典约定。

**练习 1 参考**：

```python
import json, urllib.request
tasks = json.load(urllib.request.urlopen("http://127.0.0.1:8081/api/tasks"))
print([t["title"] for t in tasks])                                # map
print([t["title"] for t in tasks if not t["done"]])               # filter+map
print(sum(1 for t in tasks if "票" in t["title"]))                # filter+count
print("、".join(sorted(t["title"] for t in tasks if not t["done"])))  # joining
```

**练习 2 参考**：

```java
jshell> tasks.stream().collect(java.util.stream.Collectors.groupingBy(t -> t.done, java.util.stream.Collectors.counting()))
$7 ==> {false=2, true=1}
```

**练习 3 参考**：

```java
jshell> tasks.stream().map(t -> {
   ...>   var m = new java.util.LinkedHashMap<String,Object>();
   ...>   m.put("id", t.id); m.put("title", t.title); m.put("done", t.done);
   ...>   return m;
   ...> }).toList()
$8 ==> [{id=1, title=买票, done=true}, {id=2, title=订酒店, done=false}, {id=3, title=买票回程, done=false}]
```

这与 TripController.java:32-44 的结构逐行同构——车次 JSON 就是这么造出来的。

---

## 本节小结
- lambda = 函数式接口的实例；五接口五法：Predicate/Function/Consumer/Supplier/BiFunction。
- 方法引用四种形式，简单场景是 lambda 的缩写。
- 管道三段式：源 → 惰性中间操作 → 终端操作；Stream 一次性，用完重开。
- Python 列表推导式 ↔ Stream 逐条对应；会一种就会另一种。
- 实战套路：repo.findAll() 拿 List → filter/map/toList 加工 → 返回给前端；大数据先让数据库分页。

## 下一站

[Java速通-A06-注解与反射：为什么Spring满是@符号.md](Java速通-A06-注解与反射：为什么Spring满是@符号.md)——你一路见过的 @Entity/@Id/@RestController 到底凭什么改变程序行为？答案：注解只是标签，反射才是读标签的人。Java 速通系列在此收官，前方就是 Spring Boot 内核。
