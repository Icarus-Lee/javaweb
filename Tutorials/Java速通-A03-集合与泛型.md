# Java速通-A03 · 集合与泛型：从 STL 视角

> 三行件头：①要点——vector→ArrayList、unordered_map→HashMap、map→**TreeMap**（命名陷阱）；②前置——用过 STL，A01 引用语义已读；③产出——**真跑一段"绕过泛型防线"的代码，捕获一枚 ClassCastException**，把"擦除+堆污染"从概念变成事故标本。
>
> 🗺 主线进度：Java 速通第 3 站 | 🎞 上一站：A02 record | 📀 本站所得：STL 对照表 + 一次亲手捕获的类型事故。

## 🎬 开篇三件套

### 你在哪格 + 任务单
你站到配货间，认识三大货架。任务：①List/Set/Map 与三大实现；②"贴标签的仓库"理解擦除；③STL↔Java 对照表；④踩三个经典坑：equals、for-each 删元素、可变共享；⑤读懂 TripController 的 Map 拼装。

### 开工前自查
- [ ] 用过 vector/map/unordered_map
- [ ] 知道模板实例化
- [ ] A01 引用语义已消化

---

## 名词卡

| 名词 | 人话 | C++ 对照 |
|---|---|---|
| List/Set/Map | 有序可重复/去重/键值对 | vector/set/map 抽象 |
| ArrayList/HashMap | 数组实现/哈希实现（无序） | vector/unordered_map |
| TreeMap | 红黑树按键有序 | **std::map**（最容易对错号！C++map→TreeMap） |
| 泛型 `<T>` | 类型的形参 | template<typename T> |
| **擦除** | 编译后 T 被抹掉 | 模板按类型逐份实例化 |
| 自动装箱 | int↔Integer | 无 |
| 堆污染 | 绕过泛型塞错类型，取出才炸 | 没这一说 |
| Stream | 流式管道（A05 详讲） | ranges |

## 1. 概念最小人话 + 三大货架速查

```java
List<String> trips = new ArrayList<>();  trips.add("G1024"); trips.get(0);
Set<Long> paid = new HashSet<>();        paid.add(7L); paid.contains(7L);   // O(1)
Map<String,Object> resp = new HashMap<>(); resp.put("ok", true); resp.get("ok");
```

**泛型擦除（一句人话）**：仓库（JVM 的 List 类）只有一套货架；`List<String>` 只是**入库时贴的标签**，编译器看标签拦错（编期检查），仓库本身不识字（运行时无类型信息）。

推论三件套：
```java
a.getClass() == b.getClass();            // true！运行时同一个类
new T[10];                               // 编译错：T 被擦除
if (x instanceof List<String>)           // 编译错（instanceof List 可以，带 <> 不行）
```

## 2. 真实代码走查：TripController 的 Map 拼装

`backend/train/src/main/java/com/javaweb/train/controller/TripController.java`：

```java
26:     public List<Map<String, Object>> list(@RequestParam(required=false) String from,
27:                                           @RequestParam(required=false) String to) {
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
```

四点逐条"为什么这样写"：
1. **`List<Map<String,Object>>`**：JSON 对象数组的"穷人写法"，值类型 Object 什么都装，取出要强转；
2. **LinkedHashMap 而非 HashMap**（第 32 行）：JSON 字段顺序=插入顺序，前端拿到的字段顺序稳定（教学可读+测试可对拍）；
3. **`Integer.parseInt(s)`**：Redis 存字符串 "3"，出来转 int——包装类转换的日常；
4. **`s == null ? 0 : ...`**：Redis 缺 key 返 null，防御性兜底。

Controller 里集合 API 全家福：TaskController.java:22-24 `repo.findAll()` 返 `List<Task>`；BookingController.java:49 `findByUserIdOrderByCreatedAtDesc(...)` 返 `List<Booking>`——**方法名即查询，泛型参数就是元素类型**。

## 3. 工程实录：HashMap 泛型锁与装错类型的真实 ClassCastException

### 3.1 路人皆知的"名字照骗"（选表要盯行为，不强记名字）

先给一张安全选型表（本项目真实用过的都收拢在此）：

| 场景 | 选 | 理由 |
|---|---|---|
| JSON 拼装/字段序稳定 | LinkedHashMap | 记住插入顺序 |
| 快照存"当前库存" | Map<String,Integer>（LinkedHashMap） | stock 等键值 |
| 去重判定已支付用户 | HashSet<Long> | O(1) contains |
| 并发读多写少（聊天历史） | CopyOnWriteArrayList | A08 详证 |
| 返回 JSON 的小壳 | Map.of(...)/record | 不可变防误改（AuthController.java:43/53） |

对照一把"C++ 眼里的 map"：`std::map`→TreeMap（有序）、`std::unordered_map`→HashMap（哈希无序）——**Java 的 `TreeMap` 才是有序的**，别被"Map"这名字骗。

### 3.2 真·堆污染现场（本机实测，可复跑）

绕过泛型防线的邪路只有一条：**raw type**。`/tmp/opencode/Pollute.java`：

```java
import java.util.*;
public class Pollute {
    public static void main(String[] a) {
        List<String> safe = new ArrayList<>();
        safe.add("ticket-X");
        List raw = safe;                    // ← raw type：拿"货架"不拿"标签"
        raw.add(1024);                      // 编译器只有一行警告，不拦
        System.out.println("list now: " + safe);
        String s = safe.get(1);             // 取出时才炸
    }
}
```

编译（含真实警告）：

```
Pollute.java:8: warning: [unchecked] unchecked call to add(E) as a member of the raw type List
        raw.add(1024);
              ^
```

真跑输出（异常一字一句）：

```
list now: [ticket-X, 1024]
Exception in thread "main" java.lang.ClassCastException: class java.lang.Integer cannot be cast to class java.lang.String
(java.lang.Integer and java.lang.String are in module java.base of loader 'bootstrap')
	at Pollute.main(Pollute.java:10)
```

**读输出逐行拆**：
1. "list now: [ticket-X, 1024]"——Integer 竟然在 `List<String>` 里！这是**入库时**（raw.add）防线被绕过；
2. 炸**不在入库时**、炸在**取出时**（编译器给 get 的返回值贴了 String 标签，拆箱出 Integer 时强转炸）——这就是"擦除"的著名副作用：**标签贴在源码上，仓库不识字，堵门的动作发生在取件那一刻**。
3. 对照 C++：`vector<string>` 里塞 int 会**直接编译不过**；Java 放行是"运行期仍可运行取货"的代价换来了"同一套货架"（擦除模型）。

### 3.3 equals/共享/陷阱（jshell 快版，三坑一次补齐）

```java
jshell> var a = new ArrayList<Integer>(); a.add(1000); a.add(1000)
jshell> a.get(0) == a.get(1)
$3 ==> false        ← == 比引用：两个不同 Integer 对象（-128~127 缓存区间内会"偶尔 true"！）
jshell> a.get(0).equals(a.get(1))
$4 ==> true
jshell> var l = new ArrayList<>(List.of("a","b","c"))
jshell> for (var s : l) { if (s.equals("b")) l.remove(s); }
|  异常 java.util.ConcurrentModificationException   ← 迭代器账对不上
jshell> l.removeIf(s -> s.equals("b"))
$8 ==> [a, c]                                   ← 官方安全删除
jshell> var src = new ArrayList<>(List.of("票1","票2")); var alias = src
jshell> alias.add("票3"); src
$12 ==> [票1, 票2, 票3]   ← src 也"多"了！（别名）
jshell> var copy = new ArrayList<>(src); copy.add("票4"); src.size()
$15 ==> 3                ← src 无恙（真拷贝）
```

## 4. 模式对比 / 选型表（补充"Java 特有"两行）

| 要什么 | Java | C++ 等价 |
|---|---|---|
| 尾部追加 | list.add(x) | push_back |
| 取第 i 个 | list.get(i) | v[i] |
| 查键 | map.containsKey/get | count/find |
| 去重 | new HashSet<>(list) | sort+unique |
| 排序 | list.sort(Comparator) | std::sort |
| **不可变集合** | List.of/Map.of | 无（C++ 需 const 封装） |
| **线程安全快照列表** | CopyOnWriteArrayList | 无（RCU 思想，A08） |
| 安全删除 | removeIf | 手写循环+erase |

## 5. 动手验证

1. 复现 3.2 的 Pollute（javac 警告照贴出来），记录异常全行。
2. 把 3.2 的 `String s = safe.get(1)` 改成 `Object o = safe.get(1); System.out.println(o)`——**不炸了**，为什么？（答案在"强转发生在哪里"，这题做你就真懂擦除了）
3. 3.3 的 equals/CME/别名三组，各留一份输出。

## 思考题

1. 为什么 `List<int>` 非法而 `List<Integer>` 合法？装箱发生在哪一步？
2. TripController 为什么 LinkedHashMap？换 HashMap 功能坏不坏？
3. 运行时 JVM 不防塞错类型，那防线在哪？用 3.2 的 Pollute 输出一行行拆。
4. `Map.of("a",1,"a",2)` 会怎样？
5. Integer 在 -128~127 有缓存池，`==` 会"偶尔为 true"——它对"把 Integer 放进 Set 做去重"意味着什么野路子坑？

## 练习题

**练习 1**：ArrayList 装 5 个车次号去重打印。
**练习 2**：HashMap 统计 "abracadabra" 字符出现次数。
**练习 3**：仿 TripController.java:32-44 写 `tripView(t, stock)` 返 LinkedHashMap。

### 完整参考答案

**思考 1**：泛型擦除后集合装 Object（引用），int 不是。装箱在 `add(1)` 编译成 `add(Integer.valueOf(1))` 处。
**思考 2**：JSON 字段序=插入序。LinkedHashMap 保序（教学可读/diff 可对拍）；HashMap 功能不坏但字段序漂移。
**思考 3**：编译期标签拦错（声明 `List<String>` 拦 add(123)），运行时不拦；绕过防线（反射/raw type）**取出时**炸 CCE。就是 3.2 的现场意义。
**思考 4**：IllegalArgumentException——Map.of 不允许重复键（put 允许覆盖，工厂方法在构造期拒绝矛盾）。
**思考 5**：缓存池让 127==127 为 true、128==128 为 false——用 `==` 手写去重的结果是"**小数字判不出来重、大数字判重可以**"这种鬼分裂。HashSet 的 contains 本来就用 equals，你不用自作聪明换 `==`。这就是"引用而非内容"踩进集合的最常见姿势。

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

（`merge` = 存在则用函数合并、不存在放默认值——统计神器。）

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

（这与 TripController.java:32-44 的结构逐行同构——车次 JSON 就是这么造出来的。）

```java
jshell> var cnt = new java.util.HashMap<Character,Integer>()
jshell> for (char c : "abracadabra".toCharArray()) cnt.merge(c, 1, Integer::sum)
jshell> cnt
$3 ==> {a=5, r=2, b=2, c=1, d=1}
```

---

## 本节小结
- List 有序 / Set 去重 / Map 键值；**std::map→TreeMap，unordered_map→HashMap**（命名陷阱）。
- 泛型擦除：编译期贴标签拦错，运行时无类型——爆炸延后到"取件"（Pollute 实测为证：`List<String>` 里躺着 1024）。
- 三坑：对象比 equals（Integer -128~127 缓存伪真）；for-each 删元素用 removeIf；赋值是别名不是拷贝。
- Map.of/List.of 不可变——返壳好选择，要改就 new 可变版。
- 选型以"行为"起拍：LinkedHashMap 保序、HashSet 求成员、COW 给读多写少。

## 下一站

[Java速通-A04-异常与资源：try-with-resources.md](Java速通-A04-异常与资源：try-with-resources.md)——RAII 靠析构 vs Java 靠 try-with-resources；一段真跑的栈回溯 + BookingService 锁的 finally 写法。
