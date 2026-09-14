# Java速通 A10 · 踩坑清单：NPE、==与 equals、可变共享与浮点钱

> **本站你走在大厅哪一格**：这是"大厅里钉着的一块'警示牌'"。
> 前面 9 篇都在教你"怎么写对"，本篇专门讲**四个所有人真实掉过的井**：
> 空指针、`==` 陷阱、共享可变状态、浮点算钱。每个坑配**最小复现 + 修复**，
> 最后落到本站 takeaway 的真实尚可代码：订单金额为什么用**整数元**算（`totalYuan`）——
> 并给你一个 BigDecimal 对照实验亲手踩一次浮点钱的坑。

## 名词卡

| 名词 | 人话 |
|---|---|
| NPE（NullPointerException） | 拿着 `null` 去点方法 → 崩；Java 世界崩溃率第一名 |
| `==` 对对象 | 比"是不是同一块内存"，不是"内容相同" |
| `.equals()` | 比"内容是否相等"（前提：类正确覆写 equals） |
| 可变共享 | 同一个可变对象被多处引用，一处改全体"变脸" |
| 自动装箱（Integer） | `int` 与 `Integer` 的自动互转——`==` 事故的主要作案地 |
| BigDecimal | 精确十进制数：**算钱的正解** |
| 整数存钱 | 本站做法：价格用"元"整数（`priceYuan`），绕开小数陷阱（教学口径） |

## 一、坑 1：NPE —— Java 的"崩"源头

### 复现（最小两行）

```java
String name = null;
int len = name.length();        // 💥 NullPointerException
```

**为什么崩**：`null` 的意思是"这个引用不指向任何对象"，对它调任何方法/字段都是对"空气"做事。
从 C++ 角度：等价于**拿空指针调用成员函数**（Java 没有 undefined，等价行为是抛异常而非段错误）。

### 本站真实语境里的坑

`JwtUtil.verify`（backend/train/src/main/java/com/javaweb/train/security/JwtUtil.java:35-42）设计成
"校验失败返回 null"——若调用方写成 `long uid = jwt.verify(token)`（自动拆箱 null）**直接 NPE**。
所以 `AuthInterceptor` 里是标准的"判 null"写法（backend/train/src/main/java/com/javaweb/train/security/AuthInterceptor.java:23-27）：

```java
Long uid = jwt.verify(auth.substring(7));
if (uid == null) { resp.setStatus(401); return false; }    // 先判空再用
```

### 修复姿势（梯度）

```java
// 1) 显式判空（最朴素，本站拦截器就是这个）
if (uid == null) { ... }

// 2) Objects.equals / 工具方法（把"哪个是 null"都兜住）
Objects.requireNonNullElse(name, "匿名");        // 给默认值
Objects.equals(a, b);                            // 任一为 null 也不炸

// 3) Optional（API 层的"可能没有"显式声明）
Long uidOpt = Optional.ofNullable(jwt.verify(tok)).orElse(null);
// 4) 从一开始就不返回 null：返回 Optional 或抛 IllegalStateException
//    （本站 BookingService 里大量 orElseThrow 就是这个思路）
```

**习惯一句话**：方法别返回 null 对外；吃了别人的 null 就在自己这一层收紧（判一次，别让它往下漏）。

## 二、坑 2：== 与 equals；Integer 缓存的"远程杀伤"

### 复现 A：字符串

```java
String a = "train";
String b = new String("train");
System.out.println(a == b);          // false！比的是"两块内存"
System.out.println(a.equals(b));   // true —— 比"内容"
```

### 复现 B（更阴）：Integer 装箱缓存

```java
Integer x = 127, y = 127;
Integer m = 128, n = 128;
System.out.println(x == y);          // true？!（JVM 缓存 -128..127）
System.out.println(m == n);          // false（超出缓存，new 出两个对象）
```

**这就是高危**：代码里 `==` 恰好在缓存区间"看起来对"，换个值就炸——定时炸弹型 bug。

> 注意一个例外：**枚举**用 `==` 是推荐写法（枚举实例 JVM 全局唯一），
> 本站 `o.status === 'PAID'` 对应的 Java 写法是 `"PAID".equals(o.status)` 字符串比较。

### 修复姿势

- 对象比内容：一律 `equals`（或 JDK7+ 的 `Objects.equals`，双 null 也安全）。
- 常量在前防 NPE：`"PAID".equals(o.status)`（status 为 null 只得 false 不炸）。
- 想按"身份"比：先确认那真是意图（一般都有更直白名字），否则别用 ==。

## 三、坑 3：可变共享 —— 同一个对象被两个名字改脸

### 复现：List 陷入"同一份数据"

```java
List<String> a = List.of("A", "B");            // 这是不可变列表（List.of）
List<String> c = a;                             // 现在 c 与 a 是同一对象
```

更经典的是"拷错深度"：

```java
Map<String, List<String>> shelf = new HashMap<>();
shelf.put("A排", new ArrayList<>(List.of("book1", "book2")));

Map<String, List<String>> copy = new HashMap<>(shelf);   // 浅拷贝！
copy.get("A排").remove("book1");
System.out.println(shelf.get("A排"));                    // [book2] ← 原版也被拆了！
```

浅拷贝只拷"外壳"，**List 还是同一份**——改 copy 等于改原版。这正是 A08 讲过的"可变共享"陷阱的地图版：
**共享一个可变对象 = 任何一处修改全体可见**。

本站暗线：demo-chat 的 `history` 用 CopyOnWriteArrayList 写时复制，
以及 Spring 每请求独立"bean 调用"（Controller 单例但**请求参数/局部变量在栈上**不共享）——
**Spring 单例 Controller 里绝不要写可共享的实体字段**（demo-counter 用的是无状态 Controller + Redis 外存，就是这个道理）。

### 修复姿势

- 默认**不可变**：`List.of`、`Map.of`、record。
- 必须可变又需共享 → 用并发类库（A08）。
- 拷贝要**深**：`new ArrayList<>(list)` 对每层内部集合也要再拷。
- 返回集合时给**副本**：`return List.copyOf(inner)`，别把内部列表当公共广场。

## 四、动手验证：浮点算钱——0.1 + 0.2 的路人皆知

### 实验 A：double 算钱立刻翻车

```java
public class MoneyBug {
    public static void main(String[] args) {
        double a = 0.10, b = 0.20;
        System.out.println(a + b);          // 0.30000000000000004 ？!
        System.out.println((a + b) == 0.30); // false ！！
    }
}
```

浮点（IEEE 754 二进制）无法精确表示 0.1——每一分钱都可能带"尾渣"。
修复两种姿势：

```java
// 姿势 1：BigDecimal（字符串入参，避免 0.1 已被污染）
BigDecimal a = new BigDecimal("0.10");
BigDecimal b = new BigDecimal("0.20");
System.out.println(a.add(b).compareTo(new BigDecimal("0.30")) == 0);  // true

// 姿势 2：整数语义（本站的"priceYuan"），0.30 = 30 分
int cents = 10 + 20;   // 30，永不丢渣
```

### 实验 B：BigDecimal 对照加价校验（本站口吻的"加价校验"）

外卖场景：原价 29.90 元，"高峰加价 10%"（乘 1.1）。写一个对拍程序 `PriceCheck.java`，
同时用 `double`、`BigDecimal`、整数分三路算：

```java
import java.math.BigDecimal;
import java.math.RoundingMode;

public class PriceCheck {
    public static void main(String[] args) {
        double base = 29.90;
        double bump = base * 1.10;
        System.out.println("double  路径: " + bump);          // 32.89000000000001 之类

        BigDecimal d = new BigDecimal("29.90").multiply(new BigDecimal("1.10"))
                        .setScale(2, RoundingMode.HALF_UP);
        System.out.println("BigDecimal  路径: " + d);          // 32.89

        int cents = 2990;                       // 整数分路线
        int bumped = (int) Math.round(cents * 1.10);
        System.out.println("整数分  路径: " + bumped / 100 + "." + bumped % 100);  // 32.89

        // 校验是否等于"应为 32.89 元"
        System.out.println(bump == 32.89);                        // false（浮点败）
        System.out.println(d.compareTo(new BigDecimal("32.89")) == 0);  // true
        System.out.println(bumped == 3289);                       // true
    }
}
```

**预期输出**：

```
double 路径: 32.89000000000001
BigDecimal 路径: 32.89
整数分 路径: 32.89
false
true
true
```

注意 `new BigDecimal(0.1)`（double 直传）**保留尾渣**；想精确必须 `new BigDecimal("0.10")`（字符串）。
这就是"BigDecimal 写错比 double 还危险"的原因，也是**本站直接选整数（totalYuan、priceYuan）**的根本理由：
**钱用"分/元整数"，机器就是精确的**。

## 思考题

1. 为什么本站 train/takeaway 的实体金额字段都是 `int priceYuan` / `int totalYuan`？如果产品要求"打折 8.5 折带小数"，你会建议整数"分"+ 四舍五入规则，还是 BigDecimal？两者边界在哪？
2. `"PAID".equals(o.status)` 与 `o.status.equals("PAID")`，为什么前者被认为防御性更好？什么时候后者也不必改写？
3. 本站 BookingService 有 `if (!b.userId.equals(userId))`——如果 b.userId 是 null，会发生什么？这算"坑 1 + 坑 2 哪个在起作用"？

## 练习题

1. 写 `EqualsTrap.java`：两个 `Integer` 从 200 和 100 分别取值，用 == 对比后改用 `Objects.equals`，打印两种结果的差异。
2. 写 `DeepShelf.java`：完整复现第三节"浅拷贝拆书"的坑，并用 `Collections.unmodifiableMap` + `List.copyOf` 改写出"改 copy 不影响原版"的版本（深拷贝手动循环里逐条 `List.copyOf(v)`）。
3. 在 PriceCheck 基础上加一个"满 30 减 3"的运算（BigDecimal `subtract` + 比较），验证 29.90 加价按 BigDecimal 得 32.89 满足减免条件。

## 参考答案

**练 1**：

```java
public class EqualsTrap {
    public static void main(String[] a) {
        Integer big = Integer.valueOf(200), big2 = Integer.valueOf(200);
        Integer small = Integer.valueOf(100), small2 = Integer.valueOf(100);
        System.out.println(big == big2);                 // false（缓存区外）
        System.out.println(small == small2);             // true （缓存区内 -128..127）
        System.out.println(Objects.equals(big, big2));   // true（内容比较，永远语义正确）
        System.out.println(Objects.equals(null, big));   // false（null 在场也安全）
    }
}
```

**练 2**：

```java
public class DeepShelf {
    public static void main(String[] a) {
        Map<String, List<String>> shelf = new HashMap<>();
        shelf.put("A排", new ArrayList<>(List.of("book1", "book2")));

        Map<String, List<String>> deepCopy = new HashMap<>();
        shelf.forEach((k, v) -> deepCopy.put(k, new ArrayList<>(v)));   // 每层都拷

        deepCopy.get("A排").remove("book1");
        System.out.println(shelf.get("A排"));      // [book1, book2] ← 原版无损

        // 更硬的封条：对外只给不可变视图
        Map<String, List<String>> frozen = shelf.entrySet().stream()
            .collect(Collectors.toMap(Map.Entry::getKey, e -> List.copyOf(e.getValue())));
        System.out.println(frozen);
    }
}
```

**练 3**：

```java
BigDecimal price = new BigDecimal("29.90").multiply(new BigDecimal("1.10"))
                   .setScale(2, RoundingMode.HALF_UP);      // 32.89
if (price.compareTo(new BigDecimal("30")) >= 0)
    price = price.subtract(new BigDecimal("3.00"));
System.out.println(price);                                 // 29.89
```

要点：BigDecimal 比较**永远用 compareTo**（== 比"同一个对象"，equals 还祭出精度：`2.0` vs `2.00` 会 false）。

## 本节小结
- **坑 1 NPE**：判空意识 + 常量前 equals + Optional/orElseThrow；返回值别对外裸 return null。
- **坑 2 = 与 equals**：对象比内容一律 equals；Integer 装箱比较是 `==` 最高危地（-128..127 缓存伪真）；字符串用 `"PAID".equals(...)` 防空。
- **坑 3 可变共享**：共享可变 = 一处改全体变；拷贝必须逐层深;对外给不可变副本/视图。
- **坑 4 浮点钱**：双精度永远有尾渣；精确 = `BigDecimal`（字符串构造、compareTo、setScale）或**整数分/元**——本站 `totalYuan` 的整数口径就是这个取舍。

## 下一站

Java 基础速通线（A06-A10）到此收官。下一站回到浏览器那端：**JS速通 B01 DOM 与事件**——
打开浏览器 DevTools 亲手改 DOM、写点击事件、初次相认 event loop（比照 GUI 事件循环就是一回事）。
