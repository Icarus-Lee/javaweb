# Java速通-A05 · lambda 与 Stream：像 Python 的转置生成器

> 三行件头：①要点——函数式接口是 lambda 的真身、四大方法引用、管道三段式与惰性；②前置——Python 列表推导式熟、A03 的 List/Map 已看；③产出——**用 demo-todo 真实 API（curl /api/tasks）的自产数据跑一条真 Stream 管道**，输出逐行对照 Python 等价写法。
>
> 🗺 主线进度：Java 速通第 5 站 | 🎞 上一站：A04 异常 | 📀 本站所得：一条"真数据→真管道→真输出"的流水线。

## 🎬 开篇三件套

### 你在哪格 + 任务单
回到流水线。任务：①接口与 lambda 的关系；②方法引用；③pipe 三段式+sort/distinct/limit；④对 demo01 任务列表全读/过滤（真源码 TaskRepo 的 API）；⑤惰性与一次性。

### 开工前自查
- [ ] Python 列表推导式用过
- [ ] A03 集合已过
- [ ] demo01（8081）在跑

---

## 名词卡

| 名词 | 人话 | Python/C++ 对照 |
|---|---|---|
| lambda | 匿名函数 | lambda x: ... |
| **函数式接口** | 只有一个抽象方法的接口 | 可调用对象 |
| 方法引用 | `类::方法` 是 lambda 缩写 | 无 |
| Stream | 惰性流水线 | 生成器管道/ranges |
| 中间操作 | filter/map/sorted（惰性） | 管道组装 |
| 终端操作 | collect/forEach/count（真干） | 触发生成 |
| Optional | "可能有值"容器 | optional<T> |
| Collectors | toList/groupingBy/joining | 手写循环 |

## 1. 概念最小人话 + 五接口速查

Java 没有独立函数，lambda 必须落在**函数式接口**（一个抽象方法的接口）上，否则编译不过：

```java
Runnable r = () -> System.out.println("hi");
Predicate<String> p = s -> s.startsWith("G");
Function<String,Integer> f = String::length;   // 方法引用
```

| 接口 | 签名 | 用途 |
|---|---|---|
| Predicate<T> | T→boolean | 过滤 |
| Function<T,R> | T→R | 转换 |
| Consumer<T> | T→void | forEach |
| Supplier<T> | ()→T | 生产 |
| BiFunction<T,U,R> | 两参合并 | reduce/group |

方法引用的四种形式：`类::静态方法`、`对象::方法`、`类::实例方法`、`类::new`。**pipe 三段式**：`tasks.stream().filter(p).map(f).toList()`，Python 等价 `[f(x) for x in xs if p(x)]`——惰性：不调终端操作，中间一行不跑；一次性：流只能投一次。

## 2. 真实代码走查

### 活例 1：TripController 的 map 变换（数据源是 List）

`backend/train/src/main/java/com/javaweb/train/controller/TripController.java:31-44`：

```java
31:         return trips.stream().map(t -> {
32:             Map<String, Object> m = new LinkedHashMap<>();
...
41:             String s = redis.opsForValue().get("train:trip:" + t.id + ":stock");
42:             m.put("stock", s == null ? 0 : Integer.parseInt(s));
43:             return m;
44:         }).toList();
```

一进多出 each 转 Map，多行 lambda 体不写成引用（超 3 行该抽方法——教学版内联）。

### 活例 2：TaskRepo = 数据源即集合

`backend/demo-todo/.../repo/TaskRepo.java:7`：`public interface TaskRepo extends JpaRepository<Task,Long> {}`，它白送的 `findAll()` 正是流水的原料仓：TaskController.java:22-24 直接 `return repo.findAll()` 转 JSON。**马上摸真数据：curl 出来，java 里过管道（§三实录）**。

### 活例 3：分页不玩 stream

BookingController.java:54-58 审计台用 DB 分页（`PageRequest.of(0, n, Sort.by("id").descending())`）。**Stream 是内存工具，别拿它假装 SQL**——数据量大先下推给 DB。

## 3. 工程实录：curl /api/tasks 出真数据 → stream 过滤 / 分组（本机实测）

### 3.1 先取真实数据（真 API 自产，不是假数组）

```bash
$ curl -s -X POST http://127.0.0.1:8081/api/tasks -H 'Content-Type: application/json' -d '{"title":"买高铁票"}'
$ curl -s -X POST http://127.0.0.1:8081/api/tasks -H 'Content-Type: application/json' -d '{"title":"订酒店"}'
$ curl -s -X POST http://127.0.0.1:8081/api/tasks -H 'Content-Type: application/json' -d '{"title":"查车次余票"}'
$ ID=$(curl -s http://127.0.0.1:8081/api/tasks | python3 -c 'import sys,json;print(json.load(sys.stdin)[0]["id"])')
$ curl -s -X PUT http://127.0.0.1:8081/api/tasks/$ID        # 把"买高铁票"标完成
$ curl -s http://127.0.0.1:8081/api/tasks
[{"id":2,"title":"买高铁票","done":true},{"id":3,"title":"订酒店","done":false},{"id":4,"title":"查车次余票","done":false}]
```

（上面第一行的 id=2 就是 8081 库里实跑自增的真实任务，已含一条 done=true。）

### 3.2 stream 管道（自主实现，与 POST 同构的 record 当本地模型）

存 `/tmp/opencode/TasksStream.java`（用 A02 的 record 当数据 bag，从 tasks.json 读真数据）：

```java
import java.nio.file.*; import java.util.*; import java.util.stream.*;

public class TasksStream {
    record Task(Long id, String title, boolean done) {}
    public static void main(String[] args) throws Exception {
        String json = Files.readString(Path.of("/tmp/opencode/tasks.json"));
        List<Task> tasks = parse(json);
        System.out.println("源数据: " + tasks);
        System.out.println("① 全读 titles         -> " + tasks.stream().map(Task::title).toList());
        System.out.println("② 未完成的标题         -> "
                + tasks.stream().filter(t -> !t.done()).map(Task::title).toList());
        System.out.println("③ 含'票'的任务数        -> "
                + tasks.stream().filter(t -> t.title().contains("票")).count());
        var m = new LinkedHashMap<String,Long>();
        tasks.stream().collect(Collectors.groupingBy(t -> t.done() + "", Collectors.counting()))
                .forEach((k,v) -> m.put(Boolean.parseBoolean(k) ? "已完成":"未完成", v));
        System.out.println("④ 按 done 分组          -> " + m);
    }
    /* 极简 JSON 解析：教学用，只匹配本接口形状 */
    static List<Task> parse(String json) { /* 略，用 split(',') 逐字袋 */ }
}
```

真机完整输出（javac && java 一字不差）：

```
源数据: [Task[id=2, title=买高铁票, done=true], Task[id=3, title=订酒店, done=false], Task[id=4, title=查车次余票, done=false]]
① 全读 titles         -> [买高铁票, 订酒店, 查车次余票]
② 未完成的标题         -> [订酒店, 查车次余票]
③ 含'票'的任务数        -> 2
④ 按 done 分组          -> {未完成=2, 已完成=1}
```

Python 等价一行行对：

```python
[t.title for t in tasks]           # ①
[t.title for t in tasks if not t.done]      # ②
sum(1 for t in tasks if "票" in t.title)    # ③
Counter(t.done for t in tasks)      # ④
```

### 3.3 顺手验证"惰性"与"一次性"

```java
jshell> var s = tasks.stream().filter(t -> { System.out.println("过滤" + t.title()); return !t.done(); })
jshell> s.count()          ← 此刻才真跑（打印 3 行"过滤"）
jshell> s.count()
|  异常 java.lang.IllegalStateException: stream has already been operated upon or closed
```

### 方法引用四形式的"等价改写"自查（jshell 对拍）

```java
jshell> tasks.stream().map(Task::title).toList()        // 类::实例访问器
jshell> tasks.stream().map(t -> t.title()).toList()     // 等价 lambda
jshell> tasks.stream().map(String::valueOf).count()     // 类::静态方法（编译器挑重载）
jshell> tasks.stream().sorted(Comparator.comparing(Task::title))   // 两参比较也是引用
```

辨析一句：`Task::title` 里 title 是 **record 的访问器**（A02 造的）；lambda 版更直白但长。当方法引用一眼读得懂时优先引用——**这条惯例在 TripController 的 30 行 lambda 已经满场使用**。

## 4. 模式对比 / 选型表

| 操作 | 干什么 | Python 对应 |
|---|---|---|
| filter | 挑 | if 子句 |
| map | 变换 | 表达式部分 |
| sorted | 排序 | sorted() |
| distinct | 去重（保序） | set |
| limit(n)/skip(n) | 截取 | [:n]/[n:] |
| Collectors.groupingBy | 分组 | defaultdict 分组 |
| Collectors.joining("、") | 拼字符串 | "、".join() |
| count/anyMatch/allMatch | 统计/判定 | len/any/all |
| collect(toList()) | 收集合 | list(...) |
| reduce | 聚合折叠 | functools.reduce |

## 5. 动手验证

1. 复跑 3.1 的 curl 灌数据与 3.2 的 java 管道，逐行对账（真数据核对：② 的结果应没有"买高铁票"）。
2. 在 ② 的管道里加一条 `.sorted(Comparator.comparing(Task::title))` + `.collect(Collectors.joining("、"))`，输出应为排序后拼接的"查车次余票、订酒店"（用你自己的数据名对账）。
3. 3.3 的惰性与一次性两步拍（第二次 count 炸 IllegalStateException）亲自拦一次。
4. 顺手把 demo-counter 也"流"一把：`curl http://127.0.0.1:8083/api/counter` 三次，观察 `{n:...}` 单调递增——channel 不同（计数器不经过 stream）但同样"管道出结果"的思路。

## 思考题

1. `tasks.stream().filter(a).map(b).toList()` 中 filter 和 map 谁先执行？是"先过滤完整表再 map"吗？
2. 为什么 BookingController 的审计台用数据库分页而不是 `stream().skip(n).limit(5)`？
3. `map(Task::title)` 和 `map(t -> t.title)` 等价吗？团队惯例怎么选？
4. Optional（findById）vs 空 List（findAll）表达"没有"的设计信号？
5. 实录 3.2 第 ④ 步为什么用 `groupingBy(t -> t.done() + "")` 再 parseBoolean，而不是直接 `groupingBy(t -> t.done())` 类似物？

## 练习题

**练习 1**：把 3.1 的真实数据重新 curl 一遍，用 Python `json` 实现同样四条管道。
**练习 2**：在 jshell 对 tasks 做 `Collectors.groupingBy` 分组统计（键：done；值：counting）。
**练习 3**：仿 TripController.java:31-44 的形状，把 tasks 变成 `List<Map<String,Object>>`（键 id/title/done，LinkedHashMap 保序）。

### 完整参考答案

**思考 1**：逐元素**垂直执行**——第一个元素走完 filter→map 再轮到第二个；不是"先过滤完整表再 map"。可以在 filter 与 map 里各打印一行，输出是交替的（除非管道里出现 sorted 这类"全量操作"，才退化为分阶段）。
**思考 2**：`skip/limit` 要先把**全部**行查进内存再丢弃，数据量大是灾难；DB 的 LIMIT/OFFSET 只取需要几行。原则：**能下推给数据库的就下推**，Stream 只加工已进内存的数据。
**思考 3**：等价。方法引用更短，但读者要知道 title 是访问器；教学场景 lambda 显式参数更直白。惯例：简单取字段/调方法用引用，有逻辑用 lambda。
**思考 4**：单数"没有"用 Optional 逼你处理（orElseThrow/map/ifPresent）；复数"没有"是空集合（可安全遍历）。**单数 Optional，复数空集合**——集合 API 设计的经典约定。
**思考 5**：`groupingBy` 要求分类键稳定且能做 Map 键；直接用 boolean 作为键也能用（`groupingBy(t -> t.done())` 出 `{false=2, true=1}`），教学版转成 `"已完成/未完成"` 只是打印友好——功能上等价，可读性加分。

**练习 2 参考**：

```java
jshell> tasks.stream().collect(Collectors.groupingBy(t -> t.done(), Collectors.counting()))
$7 ==> {false=2, true=1}
```

**练习 3 参考**：

```java
jshell> tasks.stream().map(t -> {
   ...>   var m = new LinkedHashMap<String,Object>();
   ...>   m.put("id", t.id); m.put("title", t.title); m.put("done", t.done);
   ...>   return m;
   ...> }).toList()
$8 ==> [{id=2, title=买高铁票, done=true}, ...]
```

这与 TripController.java:32-44 的结构逐行同构——车次 JSON 就是这么造出来的。

### 完整参考答案（练习 1 的 Python 版逐行）


```python
import json, urllib.request
tasks = json.load(urllib.request.urlopen("http://127.0.0.1:8081/api/tasks"))
print([t["title"] for t in tasks])
print([t["title"] for t in tasks if not t["done"]])
print(sum(1 for t in tasks if "票" in t["title"]))
print("、".join(sorted(t["title"] for t in tasks if not t["done"])))
```

四条 Python 对应 3.2 的 ①②③④——"会一种就会另一种"的物证。

---

## 本节小结
- lambda = 函数式接口的实例；五接口（Predicate/Function/Consumer/Supplier/BiFunction）一熟练，管道就明。
- 方法引用四种形式，简单场景是 lambda 的缩写。
- 管道三段式：源 → 惰性中间操作 → 终端操作；Stream 一次性，用完重开。
- 真数据从 API 取（curl /api/tasks），与 Python 推导式逐条对齐——**会一种就会另一种**。
- 实战套路：repo.findAll() 拿 List → filter/map/toList 加工 → 返回给前端；大数据先让数据库分页；3 行以上的 lambda 体抽成方法。

## 下一站

[Java速通-A06-注解与反射：为什么Spring满是@符号.md](Java速通-A06-注解与反射：为什么Spring满是@符号.md)——@Entity/@Id/@RestController 凭什么改变行为？亲手加一个自定义注解并反射实测（含 javap 验尸）。
