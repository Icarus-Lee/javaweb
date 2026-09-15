# Java速通 A10 · 踩坑清单：NPE、==与 equals、可变共享与浮点钱

> 三行件头：①要点——四个所有人真实掉过的井：NPE、==陷阱、共享可变、浮点钱；②前置——A01-A09 全部跑过一遍；③产出——**每坑都给"本项目真实场景引用 + 最小可跑复现代码 + 真实输出"**，从"我听说了"升级为"我复现过"。
>
> 🗺 主线进度：Java 速通第 10 站（收官） | 🎞 上一站：A09 Lombok | 📀 本站所得：四份"自己脸上过"的复现标本。

## 名词卡（压缩，按掉坑频次排列）

| 名词 | 人话 |
|---|---|
| NPE | 拿 null 点方法→崩；第一名 |
| `==`(对象) | 比内存同一块，非"内容相同" |
| `.equals()` | 比内容（前提正确覆写） |
| 自动装箱 | int↔Integer；`==` 高危作案地 |
| 可变共享 | 一处改全体变脸 |
| BigDecimal | 精确十进制，**算钱正解** |
| 整数存钱 | 本站 `totalYuan` 做法绕小数陷阱 |

## 坑 1：NPE —— Java 崩溃率第一名

### 复现（jshell 两行）

```java
jshell> String name = null;
jshell> name.length()
|  Exception java.lang.NullPointerException     ← 确定异常，不段错误
```

**本项目真实语境**：`JwtUtil.verify`（JwtUtil.java:35-42）设计成"校验失败返 null"；若调用方写 `long uid = jwt.verify(token)`（自动拆 null）**直接 NPE**。所以 AuthInterceptor 里是标准"判 null"写法：

```java
Long uid = jwt.verify(auth.substring(7));
if (uid == null) { resp.setStatus(401); return false; }    // 先判空再用
```

### 修复梯度

```java
if (uid == null) { ... }                             // 1) 显式判空（本站拦截器）
Objects.requireNonNullElse(name, "匿名");             // 2) 给默认值
Objects.equals(a, b);                               //    任一为 null 也不炸
Optional.ofNullable(jwt.verify(tok)).orElse(...)     // 3) Optional 显式"可能没有"
orElseThrow(() -> new IllegalStateException(...))    // 4) 干脆不返 null（BookingService 思路）
```

习惯一句话：**方法别对 外改造型 return null；吃了别人的 null 就在自己层收紧**。

## 坑 2：== 与 equals；Integer 缓存的"远程杀伤"

### 复现 A：字符串

```java
String a = "train";                          // 字面量 → 常量池
String b = new String("train");              // → 堆新对象
System.out.println(a == b);          // false！比内存
System.out.println(a.equals(b));     // true  比内容
```

### 复现 B（更阴）：Integer 装箱缓存（本机真跑）

`/tmp/opencode/EqualsTrap.java`：

```java
import java.util.Objects;
public class EqualsTrap {
    public static void main(String[] a) {
        Integer big = Integer.valueOf(200), big2 = Integer.valueOf(200);
        Integer small = Integer.valueOf(100), small2 = Integer.valueOf(100);
        System.out.println("200 ==200 : " + (big == big2));
        System.out.println("100 ==100 : " + (small == small2));
        System.out.println("Objects.equals(200,200): " + Objects.equals(big, big2));
        System.out.println("Objects.equals(null,200): " + Objects.equals(null, big));
    }
}
```

**真跑输出（本机 jdk26）**：

```
200 ==200 : false     ← 缓存区外，两对象
100 ==100 : true      ← 缓存区内（-128..127），"伪真"！
Objects.equals(200,200): true
Objects.equals(null,200): false   ← null 在场也安全
```

**高危逻辑**：`==` 恰在缓存区间"看起来对"，换值就炸——定时炸弹型 bug。**例外**：枚举用 `==` 是推荐写法（JVM 全局单例）。本项目订单状态虽是 String，业已提示过：`"PAID".equals(o.status) ==`、`o.status.equals("PAID")` 见坑 1。

修复：内容比一律 equals/Objects.equals；常量在前防 NPE（`"PAID".equals(o.status)` status 为 null 得 false 不炸）。

## 坑 3：可变共享 —— 同一个对象被两个名字改脸

### 复现："拷错深度"（本机可复跑）

```java
Map<String,List<String>> shelf = new HashMap<>();
shelf.put("A排", new ArrayList<>(List.of("book1","book2")));
Map<String,List<String>> copy = new HashMap<>(shelf);   // 浅拷贝！
copy.get("A排").remove("book1");
System.out.println(shelf.get("A排"));     // [book2] ← 原版也被拆了！
```

本项目真实场景引用（demo-chat）：`history` 用 **CopyOnWriteArrayList**（A08，写时复制防踩踏）；Spring 单例 Controller 里**绝不要写"实例级可变实体字段"**（只读的线程安全容器除外；demo-counter 用无状态 Controller + Redis 外存正是这个道理）。A08 的 synchronizedList 陷阱在这题里也用得上（遍历时挨删）。

### 修复姿势

- 默认**不可变**：List.of/Map.of/record（再也不怕"谁改谁"）；
- 必须可变又要共享 → 并发类库（A08 的 COW/ConcurrentHashMap）；
- 拷贝要**深**：`new ArrayList<>(list)` 对每层内部集合也要再拷；
- 返回集合时给**副本**：`return List.copyOf(inner)`，别把内部列表当公共广场。

## 坑 4：浮点算钱 —— 0.1+0.2 的路人皆知

### 复现（本机真跑 MoneyBug2.java）

`/tmp/opencode/MoneyBug2.java`：

```java
public class MoneyBug2 {
    public static void main(String[] a) {
        double s = 0; for (int i = 0; i < 10; i++) s += 0.10;
        System.out.println("0.1累加10次 = " + s + "  ==1.0? " + (s == 1.0));
        System.out.println("0.10+0.20 = " + (0.10 + 0.20) + " ==0.30? " + ((0.10+0.20)==0.30));
        var t = new java.math.BigDecimal("0.10").add(new java.math.BigDecimal("0.20"));
        System.out.println("BigDecimal(\"0.10\")+\"0.20\" = " + t);
        System.out.println("BigDecimal(0.10)[double构造] = " + new java.math.BigDecimal(0.10));
    }
}
```

**真跑输出（本机）**：

```
0.1累加10次 = 0.9999999999999999  ==1.0? false
0.10+0.20 = 0.30000000000000004 ==0.30? false
BigDecimal("0.10")+"0.20" = 0.30
BigDecimal(0.10)[double构造] = 0.1000000000000000055511151231257827021181583404541015625
```

两个细节值得钉死：
- **0.1 累加十次竟然 != 1.0**——尾渣不以"意外"而是"必然"出现；
- **BigDecimal(0.10)（double 直传）也带尾渣**：`0.10000000000000000555…`——请务必字符串构造 `new BigDecimal("0.10")`，`BigDecimal(0.1)` **比 double 还危险**，这就是"BigDecimal 用错比不用还坑"的真相。

### 本项目的真实解（整数口径）+ 对照实验

takeaway 的价格与订单总额全部 `int`（源文件可查）：

```java
// Dish.java:14     public int priceYuan;
// Order.java:19    public int totalYuan;
// OrderService.java:59  o.totalYuan = dish.priceYuan * qty;   // 整数封袋，永不丢渣
```

|"路" | 写法 | 结果 | 备注 |
|---|---|---|---|
| double | `2990 乘法/100.0` | 偶发放大尾渣 | 商业口径不敢用 |
| BigDecimal(字符串) | `new BigDecimal("29.90").multiply(...).setScale(2,HALF_UP)` | 32.89 | 精确，注意 compareTo |
| 整数分 | `(int) Math.round(2990 * 1.10)` | 3289 → 32.89 | 本站选择，机器精确 |

**选整数（priceYuan/totalYuan）的根本理由**：教学可读+机器精确；**口径改"分"即精确**——这就是 `totalYuan` 诞生的故事。

## 动手验证（自己做一遍 + 输出对账）

```bash
# 坑 1（jshell 两行）
jshell> String name = null
jshell> name.length()
|  Exception java.lang.NullPointerException

# 坑 2（javac + java EqualsTrap.java 对账，本机实测）
$ java EqualsTrap
200 ==200 : false
100 ==100 : true
Objects.equals(200,200): true
Objects.equals(null,200): false

# 坑 3（DeepShelf：浅拷拆书 → 深拷复原，输出贴回）
# 坑 4（javac MoneyBug2.java && java MoneyBug2）
0.1累加10次 = 0.9999999999999999  ==1.0? false
0.10+0.20 = 0.30000000000000004 ==0.30? false
BigDecimal("0.10")+"0.20" = 0.30
BigDecimal(0.10)[double构造] = 0.10000000000000000555...
```

## 思考题

1. 为什么 train/takeaway 金额全用 `int priceYuan/totalYuan`？要"8.5 折"怎么办，你建议整数"分"+四舍五入还是 BigDecimal？边界？
2. `"PAID".equals(o.status)` 与 `o.status.equals("PAID")`，前者防御好在哪？什么时候后者也不必改？
3. BookingService 有 `if (!b.userId.equals(userId))`——b.userId 为 null 会发生什么？这算"坑 1 + 坑 2 哪个在起作用"？
4. 坑 4 输出里 `BigDecimal(0.10)[double构造]` 那一长串说明什么？为什么说"BigDecimal 用错比 double 还危险"？

## 练习题 / 参考答案

**练 1**：EqualsTrap（坑 2 复现 B 现成代码）；要点：Integer `==` 超出 -128~127 便"实锤两块"，区间内是"伪真"。
**练 2**：DeepShelf 深拷版本：

```java
Map<String, List<String>> deepCopy = new HashMap<>();
shelf.forEach((k, v) -> deepCopy.put(k, new ArrayList<>(v)));   // 每层都拷

deepCopy.get("A排").remove("book1");
System.out.println(shelf.get("A排"));      // [book1, book2] ← 原版无损

// 更硬的封条：对外只给不可变视图
Map<String, List<String>> frozen = shelf.entrySet().stream()
    .collect(Collectors.toMap(Map.Entry::getKey, e -> List.copyOf(e.getValue())));
```

**练 3**：满 30 减 3：

```java
BigDecimal price = new BigDecimal("29.90").multiply(new BigDecimal("1.10"))
    .setScale(2, RoundingMode.HALF_UP);               // 32.89
if (price.compareTo(new BigDecimal("30")) >= 0)
    price = price.subtract(new BigDecimal("3.00"));
System.out.println(price);                            // 29.89
```

要点：BigDecimal 比较**永远用 compareTo**（== 比引用；equals 连精度都祭出，`2.0` vs `2.00` 会 false）。

**参考答案（思考题）**：
1. `int` 机器精确、教学可读、永不丢渣。要小数折扣：金额小且规则明确→整数"分"+约定舍入（sql/业务统一）；含任意精度需求（计费引擎/利息）→BigDecimal。边界看"是否需要保留小数语义与复杂舍入规则"，两条路都行的前提是**比较永远不用 ==**。
2. 前者常量在前，status 为 null 只得 false 不炸（坑 1 防御)；当 status 由本方刚构造、绝无 null 时（如自己刚 set 的枚举 name），后者写法可读性更好、不必改。
3. b.userId 为 null 时 NPE（坑 1），而"拆箱 null"那种最阴的 variation 才涉坑 2；本项目里 userId 在下单链路刚生成不为 null——所以这里是"给库模型留隐患"的提醒，修复是 `Objects.equals(b.userId, userId)`。
4. `new BigDecimal(0.10)` 会把 double 的尾渣（0.10000000000000000555…）**全量带进来**，输出里一长串肉眼可见；字符串构造才干净。BigDecimal 的精确性前提是"从精确的字面量出发"，用错构造器比 double 还隐蔽。

### 本站全景一图（四坑+修法速查，打印贴墙用）

| 坑 | 最小复现 | 本站场景引用 | 一行修复 |
|---|---|---|---|
| NPE | `String s=null; s.length()` | JwtUtil.verify 返 null → AuthInterceptor 判空 | 先判空再用（`if (uid == null)`）；对外不裸 return null |
| ==与 equals | Integer 200/200 两对象（false） | Booking.status 是 String → `"PAID".equals(...)` | 内容比一律 equals/Objects.equals |
| 可变共享 | `new HashMap<>(shelf)` 浅拷拆书 | demo-chat history 用 COW；单例 Controller 不藏可变字段 | 不可变优先；深拷；给副本 |
| 浮点钱 | `0.10+0.20 != 0.30`（实证） | OrderService.totalYuan 用 int | 整数分/元，或 BigDecimal 字符串构造 |

四份标本 (EqualsTrap / MoneyBug2 / 浅拷拆书 / jshell NPE) 都在 /tmp/opencode 可复跑——**把"听过"变"跑过"是本站唯一的作业**。

## 本节小结
- **NPE**：判空意识 + 常量前 equals + Optional/orElseThrow；对外别裸 return null（AuthInterceptor 是标准判 null 现场）。
- **=与 equals**：对象内容一律 equals；Integer 装箱比较最高危（-128~127 缓存伪真，128 现形——EqualsTrap 实测）。
- **可变共享**：共享可变=一处改全体变（浅拷贝拆书实测）；拷贝逐层深；对外给不可变副本。
- **浮点钱**：IEEE754 永有尾渣（0.1×10 != 1.0、0.10+0.20 != 0.30 实证）；BigDecimal 字符串构造+compareTo+setScale；或**整数分/元**——本站 `totalYuan`（Dish.java:14/Order.java:19/OrderService.java:59）的整数口径就是取舍结论。

## 下一站

Java 基础速通线（A01-A10）到此收官。回到浏览器端：**JS速通 B01 DOM 与事件**——打开 DevTools 亲手改 DOM/写事件，初识 event loop（和 GUI 事件循环一回事）。

## 工程实录：踩坑现场（每坑一实录）

本章本身就是工程实录合集：MoneyBug2（BigDecimal 尾渣）、EqualsTrap（== 与 equals）、RecordTry（final 赋值与无参构造）等演示程序的每个报错都是本机真跑输出；工作方式：用 /tmp/opencode 的演示程序逐个编译运行，即可逐条复现——这也是"踩坑清单"最好的复习路径。
