# Java速通-A03 · 集合与泛型：从 STL 视角

> `std::vector`→`ArrayList`、`std::unordered_map`→`HashMap`、`std::map`→`TreeMap`。对照学最快。但有两个 Java 特产必须单独讲：**泛型擦除**（和 C++ 模板根本不是一回事）与**引用共享陷阱**（C++ 值语义练出的直觉在这里会翻车）。

---

## 🎬 开篇三件套

### 1️⃣ 本站你走在大厅哪一格

票务大厅每时每刻都在跟"一车人""一列票""一张菜单"打交道：车次列表是 `List`、接口返回的 JSON 骨架是 `Map`、用户名到用户是 `Map`。本站你在窗口后面的**配货间**，认识三大货架。

### 2️⃣ 本站任务单

1. 掌握 List/Set/Map 三大接口及 ArrayList/HashSet/HashMap 三大实现；
2. 用"贴标签的仓库"类比理解泛型擦除；
3. 完成 STL↔Java 集合对照表；
4. 踩三个经典坑：equals、for-each 中删元素、可变共享；
5. 读懂本项目真实集合代码（TripController 的 Map 拼装）。

### 3️⃣ 开工前自查

- [ ] 用过 std::vector/map/unordered_map
- [ ] 知道模板实例化是什么
- [ ] A01 的引用语义（别名）已理解

---

## 🗂 本站名词卡

| 名词 | 人话 | C++ 对照 |
|---|---|---|
| **List** | 有序、可重复、按下标访问 | std::vector/list 抽象 |
| **Set** | 不重复的集合 | std::set/unordered_set |
| **Map** | 键值对 | std::map/unordered_map |
| **ArrayList** | List 的数组实现，随机访问快 | vector |
| **HashMap** | Map 的哈希实现，无序 | unordered_map |
| **LinkedHashMap** | HashMap + 记住插入顺序 | 无直接对应（insert 序遍历） |
| **TreeMap** | 红黑树实现，按键有序 | std::map |
| **泛型 `<T>`** | 类型的形参 | template<typename T> |
| **擦除** | 编译后 T 被抹掉，运行时不知道 T 是谁 | C++ 模板按类型逐份实例化 |
| **自动装箱** | int↔Integer 自动转换 | 无（Java 原始类型不是对象） |
| **Stream** | 流式管道操作（A05 详讲） | ranges/STL 算法 |

---

## 🧠 概念人话

### 三大货架速查

```java
List<String> trips = new ArrayList<>();     // 有序可重复：车次列表
trips.add("G1024"); trips.get(0);

Set<Long> paidUserIds = new HashSet<>();    // 不重复：已支付用户去重
paidUserIds.add(7L); paidUserIds.contains(7L);   // O(1)

Map<String, Object> resp = new HashMap<>(); // 键值对：JSON 的 Java 长相
resp.put("ok", true); resp.get("ok");
```

### STL 对照表（背下来）

| 你要干什么 | C++ | Java |
|---|---|---|
| 尾部追加 | v.push_back(x) | list.add(x) |
| 取第 i 个 | v[i] | list.get(i) |
| 长度 | v.size() | list.size() |
| 查键 | m.count(k) / find | map.containsKey(k) / get(k) |
| 遍历 | for (auto& x : v) | for (var x : list) |
| 排序 | std::sort | list.sort(Comparator) / Collections.sort |
| 去重 | sort+unique 或 set | new HashSet<>(list) |

**命名反直觉预警**：Java 的 `TreeMap` 有序、`HashMap` 无序——而 C++ 的 `map`（有序）对应 Java 的 `TreeMap`，`unordered_map` 对应 `HashMap`。别被"Map"这个名字骗了。

### 泛型擦除：贴标签的仓库

C++ 模板：`vector<int>` 和 `vector<string>` 是**两种编译产物**，类型信息刻进二进制。
Java 泛型：编译后 `List<Integer>` 和 `List<String>` 都是同一个 `List`，`T` 被**擦除**成上界（通常是 Object）。

类比：仓库（JVM 里的 List 类）只有一套货架；`List<String>` 这个泛型只是**入库时贴的标签**——编译器看标签拦截你放错货（编译期检查），但仓库本身不识字，运行时货架上没有任何类型标记。

推论（常考）：

```java
List<String> a = new ArrayList<>();
List<Integer> b = new ArrayList<>();
a.getClass() == b.getClass();      // true！运行时是同一个类
new T[10];                         // 编译错：T 被擦除，运行时不知道 T 是谁
if (x instanceof List<String>)     // 编译错（instanceof List 可以，带 <> 不行）
```

### 自动装箱：原始类型与包装类

集合只能装对象，不能装原始类型：`List<int>` 非法，要写 `List<Integer>`。`add(1)` 时 int 自动包成 Integer（装箱），取出时自动拆箱。**代价**：装箱产生对象；拆箱 null 会 NPE（`Integer x = null; int y = x;` 炸）。

---

## 🔍 真实代码走查

### TripController：Map 拼装 JSON（真实源码）

`backend/train/src/main/java/com/javaweb/train/controller/TripController.java`：

```java
26:     public List<Map<String, Object>> list(@RequestParam(required = false) String from,
27:                                           @RequestParam(required = false) String to) {
28:         List<TrainTrip> trips = (from != null && to != null && ...)
29:                 ? repo.findByFromCityAndToCityOrderById(from, to)
30:                 : repo.findByOrderById();
31:         return trips.stream().map(t -> {
32:             Map<String, Object> m = new LinkedHashMap<>();   ← 记住插入顺序的 HashMap
33:             m.put("id", t.id);
34:             m.put("trainNo", t.trainNo);
...
41:             String s = redis.opsForValue().get("train:trip:" + t.id + ":stock");
42:             m.put("stock", s == null ? 0 : Integer.parseInt(s));
43:             return m;
44:         }).toList();          ← Stream 收集成 List（A05 详讲）
45:     }
```

看点：

1. **`List<Map<String, Object>>`**：JSON 对象数组在 Java 里的"穷人写法"。`Map<String,Object>` 值类型是 Object——什么都能装，取出来要强转。
2. **LinkedHashMap 而非 HashMap**（第 32 行）：JSON 字段顺序 = 插入顺序，返回给前端的字段顺序稳定可读。用 HashMap 顺序会乱（哈希序）。
3. **`Integer.parseInt(s)`**：Redis 里存的是字符串 "3"，出来要转 int——包装类转换的日常。
4. **`s == null ? 0 : ...`**：Redis 没这个 key 返回 null，防御性兜底。

### Controller 里的集合 API 全家福

TaskController.java 里 TaskRepo 返回的就是 `List<Task>`：

```java
22:     public List<Task> list() {
23:         return repo.findAll();        ← JpaRepository 返回 List<Task>
24:     }
```

BookingController.java:49 的 `repo.findByUserIdOrderByCreatedAtDesc(...)` 同样返回 `List<Booking>`——**方法名即查询**，返回类型里的泛型参数就是集合元素类型。

---

## 动手验证

### 实验 1：equals 的坑（真实翻车）

```java
jshell> var a = new ArrayList<Integer>(); a.add(1000); a.add(1000)
jshell> a.get(0) == a.get(1)
$3 ==> false          ← == 比较引用：两个不同的 Integer 对象
jshell> a.get(0).equals(a.get(1))
$4 ==> true           ← equals 比较内容
```

> 📌 Integer 在 -128~127 有缓存池，`==` 会"偶尔为 true"——**最阴险的 bug**。规则：对象比较永远用 equals。

### 实验 2：for-each 中删元素 → ConcurrentModificationException

```java
jshell> var l = new ArrayList<>(List.of("a","b","c"))
jshell> for (var s : l) { if (s.equals("b")) l.remove(s); }
|  异常异常 java.util.ConcurrentModificationException
```

for-each 内部用迭代器计数，你直接改集合，迭代器发现账对不上就炸。正确姿势：

```java
jshell> l.removeIf(s -> s.equals("b"))      ← 官方提供的安全删除
jshell> l
$8 ==> [a, c]
```

### 实验 3：可变共享（C++ 值语义直觉翻车现场）

```java
jshell> var src = new ArrayList<>(List.of("票1","票2"))
jshell> var alias = src;                    ← 引用赋值：同一个对象！
jshell> alias.add("票3")
jshell> src
$12 ==> [票1, 票2, 票3]                      ← src 也"多"了
jshell> var copy = new ArrayList<>(src)     ← 真拷贝（浅拷贝）
jshell> copy.add("票4")
jshell> src.size()
$15 ==> 3                                   ← src 无恙
```

C++ `vector b = a;` 默认深拷贝；Java `b = a;` 只是别名。要拷贝必须显式 `new ArrayList<>(src)`。

### 实验 4：Map.of 的不可变陷阱

```java
jshell> var m = Map.of("ok", true)          ← Map.of 产出不可变 Map！
jshell> m.put("x", 1)
|  异常异常 java.lang.UnsupportedOperationException
```

本项目大量用 `Map.of(...)`（AuthController.java:43/53、CounterController.java:29）返回 JSON——因为返回体不需要再改。**想可变就 new HashMap<>()。**

---

## 思考题

1. 为什么 `List<int>` 非法而 `List<Integer>` 合法？装箱发生在哪一步？
2. TripController 为什么用 LinkedHashMap 而不是 HashMap？如果换成 HashMap，功能会坏吗？
3. `a.getClass() == b.getClass()` 为 true 意味着：运行时无法区分 `List<String>` 和 `List<Integer>`。那 JVM 怎么防止你把 Integer 塞进 `List<String>`？
4. `Map.of("a",1,"a",2)` 会怎样？（键重复）

## 练习题

**练习 1**：用 `ArrayList<String>` 装入 5 个车次号，去重后打印（用 HashSet 或 stream distinct 皆可）。

**练习 2**：用 `HashMap<String,Integer>` 统计字符串 `"abracadabra"` 里每个字符出现次数，打印。

**练习 3**：写一个方法 `Map<String,Object> tripView(TrainTrip t, String stock)`，仿照 TripController.java:32-44 拼一个含 id/trainNo/stock 的 LinkedHashMap。在 jshell 里用假数据验证。

### 完整参考答案

**思考题 1**：泛型擦除后集合只能装 Object（引用），int 不是引用。装箱发生在 `add(1)` 编译成 `add(Integer.valueOf(1))` 的字节码处——编译器自动插的语法糖。

**思考题 2**：JSON 字段输出顺序 = Map 遍历顺序。LinkedHashMap 保插入序，前端拿到的字段顺序稳定（教学可读、测试可对拍）。换 HashMap 功能不坏（键值都在），只是字段顺序漂移——对机器无所谓，对教学演示和 diff 对拍很烦。

**思考题 3**：JVM 不防——运行时根本不知道。防线在**编译期**：编译器根据声明 `List<String>` 拦截 `add(123)`。绕过防线的邪路（反射、raw type）会在运行时取出时炸 ClassCastException——这叫"堆污染"，擦除模型的著名副作用。

**思考题 4**：抛 `IllegalArgumentException`——Map.of 不允许重复键。这与 HashMap.put 的"覆盖旧值"不同，工厂方法在构造期就拒绝矛盾。

**练习 1 参考**：

```java
jshell> var trips = new ArrayList<>(List.of("G1024","G1025","G1024","D3102","G1025"))
jshell> System.out.println(new java.util.LinkedHashSet<>(trips))
[G1024, G1025, D3102]
```

**练习 2 参考**：

```java
jshell> var cnt = new java.util.HashMap<Character,Integer>()
jshell> for (char c : "abracadabra".toCharArray()) cnt.merge(c, 1, Integer::sum)
jshell> cnt
$3 ==> {a=5, r=2, b=2, c=1, d=1}
```

（`merge` = 存在则用函数合并、不存在则放默认值——统计神器。）

**练习 3 参考**：

```java
jshell> Map<String,Object> tripView(int id, String no, String stock) {
   ...>   var m = new java.util.LinkedHashMap<String,Object>();
   ...>   m.put("id", id); m.put("trainNo", no); m.put("stock", Integer.parseInt(stock));
   ...>   return m;
   ...> }
jshell> tripView(1, "G1024", "3")
$2 ==> {id=1, trainNo=G1024, stock=3}
```

---

## 本节小结
- List 有序可重复 / Set 去重 / Map 键值；ArrayList/HashSet/HashMap 是默认选择，LinkedHashMap 保序。
- STL 对照：vector→ArrayList，unordered_map→HashMap，**map→TreeMap**（命名陷阱）。
- 泛型擦除：编译期贴标签拦错，运行时无类型信息——与 C++ 模板按类型实例化根本不同。
- 三坑：对象比较用 equals（== 只比引用）、for-each 中删元素用 removeIf、赋值是别名不是拷贝。
- Map.of/List.of 产出不可变集合——返回 JSON 壳的好选择，要改就 new 可变版。

## 下一站

[Java速通-A04-异常与资源：try-with-resources.md](Java速通-A04-异常与资源：try-with-resources.md)——C++ 的 RAII 靠析构函数，Java 靠 try-with-resources。受检异常这个 Java 特产是什么？BookingService 的锁为什么放在 finally 里还？
