# Java速通 A09 · Lombok 简写：@Data 的便利与代价（以及为什么本站不用它）

> **本站你走在大厅哪一格**：你已经写了好几天 Java，每次 model 类都要手敲 `getPrice()` `setStock()`……
> 本篇先带你认识"自动生成这些样板"的 Lombok，再解释本项目教学代码为什么**几乎一篇 Lombok 都没用**，
> 反而大量用 **public 字段 + record**——这是一次值得看清楚的教学取舍，不是偷懒。

## 名词卡

| 名词 | 人话 |
|---|---|
| POJO / JavaBean | 只有字段 + getter/setter/构造器的"数据袋子" |
| 样板代码（boilerplate） | 元素固定但必须写、还容易写错的那坨代码 |
| Lombok | 注解处理编译期插件：`@Data` 之类在**编译期**把 getter/setter/toString/equals 等**生成进 class 文件** |
| @Data | 一次打包：`@Getter + @Setter + @ToString + @EqualsAndHashCode + @RequiredArgsConstructor` |
| record（Java 16+） | 语言级"不可变数据袋子"：一行声明自动有构造器/访问器/equals/hashCode |
| 注解处理器 | javac 编译期挂钩读取注解并"加工"代码的机制（注意：与 A06 说过的运行期反射是两码事） |

## 一、一个 model 类不写 Lombok 有多啰嗦

是个"外卖 Dish（菜品）"的传统写法：

```java
public class Dish {
    private Long id;
    private Long shopId;
    private String name;
    private int priceYuan;

    public Dish() {}
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getShopId() { return shopId; }
    public void setShopId(Long v) { this.shopId = v; }
    public String getName() { return name; }
    public void setName(String n) { this.name = n; }
    public int getPriceYuan() { return priceYuan; }
    public void setPriceYuan(int p) { this.priceYuan = p; }
    @Override public String toString() { return "Dish{id=" + id + ", name=" + name + "}"; }
}
```

实际项目里每个 entity 都这样写是**35+ 行样板换 4 个字段**，还容易漏一个而踩坑。

## 二、Lombok：一行注解换掉 30 行样板

```java
import lombok.Data;

@Data                       // ← getter/setter/toString/equals/hashCode/RequiredArgsConstructor 全生成
public class Dish {
    private Long id;
    private Long shopId;
    private String name;
    private int priceYuan;
}
```

其他常用粒度：

| 注解 | 生成什么 |
|---|---|
| `@Getter` / `@Setter` | 只读访问器 / 读写都给 |
| `@RequiredArgsConstructor` | 为 `final` 或 `@NonNull` 字段生成构造器（**依赖注入构造器**最常用它） |
| `@AllArgsConstructor` / `@NoArgsConstructor` | 全参 / 无参构造器 |
| `@Value` | 全字段 final 的不可变版 @Data |
| `@Builder` | 链式构建器 `Dish.builder().name("可乐").build()` |

**Lombok 走的是编译期**：它不是反射扩展的库，而是给 javac 挂了一个**注解处理器**，
在字节码真生成出来之前把 `getPrice()` 这些方法**直接织进 .class 文件**。
所以不管 IDE、Spring、Jackson，它们看到的 class 已是"完整版"——运行期零额外开销。

### 动手验证：亲手验尸一个 @Data class

```bash
mkdir -p /tmp/lombok && cd /tmp/lombok

# 本项目 pom 刻意没引入 lombok，实验就从中央仓库取一份（已配好 Maven 时一条命令）
mvn -q dependency:get -Dartifact=org.projectlombok:lombok:1.18.34
cp ~/.m2/repository/org/projectlombok/lombok/1.18.34/lombok-1.18.34.jar lombok.jar

cat > Dish.java <<'JAVA'
import lombok.Data;

@Data
public class Dish {
    private Long id;
    private String name;
    private int priceYuan;
}
JAVA

# 编译时把 lombok 放在 classpath（它注册为 javac 注解处理器）
javac -cp lombok.jar Dish.java

# 验尸：javap 看 Dish.class 里到底有没有 getPrice()
javap Dish.class | head -20
```

**预期输出**（javap 里看到 Lombok 生成的完整一排方法）：

```
public class Dish {
  public Dish();
  public java.lang.Long getId();
  public void setId(java.lang.Long);
  public java.lang.String getName();
  public void setName(java.lang.String);
  public int getPriceYuan();
  public void setPriceYuan(int);
  public boolean equals(java.lang.Object);
  public int hashCode();
  public java.lang.String toString();
}
```

**这就是"编译期展开"的铁证**：javap 看到的 Dish.class 里那些方法，Dish.java 源码里一行都没有。

### Lombok 的利与弊（白话老实说）

| 利 | 弊 |
|---|---|
| 30 行样板换成 1 行，可读性上是净赚 | **是一个编译期插件**：没装 IDE 插件的人打开源码"看不到" getter，ai/新人/反编译工具不友好 |
| 消灭"id 字段加了、toString 忘了"那种错 | 生成面广（@Data 连 equals/hashCode 都生成），一不留神有坑（如可变字段参与 equals） |
| RequiredArgsConstructor 让 DI 构造注入顺手 | 隐式魔法，出错时读的是生成码不是你的源码 |
| 与运行期无关，性能零损耗 | 构建链多一个依赖；团队风格分裂 |

## 三、本站教学代码的取舍：public 字段 + record

打开火车模型 backend/train/src/main/java/com/javaweb/train/model/TrainTrip.java：

```java
@Entity(name = "train_trips")
public class TrainTrip {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(nullable = false, length = 8)
    public String trainNo;        // 车次号，如 G1024

    public String fromCity;
    public String toCity;
    ...
    public int totalSeats;
    public int priceYuan;
}
```

**字段全部 public**——这不是 Java 的正统写法（Java 规范提倡 private + getter），而是本项目的**教学刻意**：

| 取舍理由 | 说明 |
|---|---|
| 少 1 层跳转 | `b.orderNo` 一眼看穿；`b.getOrderNo()` 对初学者多一道翻译 |
| 突出主体 | 学习目标是 JPA/JWT/Redis/Kafka，不是 getter/setter 写法 |
| 免 Lombok 依赖 | 任何编辑器打开就能读懂，无插件烦恼 |
| 代价也明说 | 外部可以乱写字段（无封装）；上生产应换成 record 或 private+Lombok |

**record 是本站真正用的"现代利器"**，落地在两处真实代码：

1. **BookingService 里的返回值袋子**（backend/train/src/main/java/com/javaweb/train/service/BookingService.java:25）：

```java
public record BookResult(String orderNo, int seatNo) {}
```

2. **OrderService 里的请求参袋子**（backend/takeaway/src/main/java/com/javaweb/takeaway/service/OrderService.java:27-28）：

```java
public record Item(Long shopId, Long dishId, Integer quantity) {}
public record BookReq(Long shopId, Long dishId, Integer quantity) {}
```

record 一行顶 @Data 一行（甚至更小）：不可变 + 构造器 + 访问器 + equals/hashCode/toString
全部由**语言本身**（Java 16+）保证，无第三方依赖，IDE/反编译/反射全天然认识。
record 与 Lombok 的色差一句话：**record 改语言规则，Lombok 改编译流程**——record 是"正路"。

### 对照演示：三胞胎各自的样子

同一个"菜品"三种写法并排放：

```java
// A) 传统 JavaBean（正统 private+getter，样板 30 行）— 从略，见第一节

// B) Lombok 一行（编译期生成方法）
@Data public class DishL { private Long id; private String name; private int priceYuan; }

// C) record 一行（语言级不可变）
public record DishR(Long id, String name, int priceYuan) {}
// 用法：new DishR(1L, "宫保鸡丁", 28).priceYuan()   ← 访问器不带 get
```

企业级老项目常见 A/B 混杂；**新项目一律 C（record）或 private+final**。
本站 train/takeaway 的字段全部 public 是教学口语化写法，读它时不要学去生产——
但也因此你能一次看清"JavaBean 样板"这整套问题的真正成本。

补充一个判断顺序帮你记住：**先问"这个数据可不可变"**——不可变 → record；
可变但只是普通 DTO → private 字段 + 构造/@Setter（Lombok 可选）；
JPA `@Entity` → 必须 private 字段 + setter（Hibernate 的脏检查/代理机理决定的，record 不可用）。

## 思考题

1. `@Data` 生成 equals 时可变字段参与比较——若 Dish 的 name 可变、而它已被放进 HashSet， setName 之后 contains 还找得到吗？为什么这被叫"哈希桶游魂"？
2. record 能代替 JPA `@Entity` 吗？想想 Hibernate 要**无参构造 + 可变实例**来做脏检查，而 record 生来不可变。（提示：JPA 不行，但 Jackson/DTO 完美支持）
3. Lombok 的 `@RequiredArgsConstructor` 为什么恰好是 Spring 构造注入的黄金搭档（本项目 BookingService 的构造器手写版与之对应）？

## 练习题

1. 把本站 `TrainTrip` 用 record 重写一版 `TrainTripR`，并写 Main 打印一个实例验证 toString 访问器的工作（注意 record 字段访问不带 get）。
2. `javap` 查一下刚才 record 版编译产物：列出 record 自动生成了哪些成员（应该有 `trainNo()` 之类、`equals`、`hashCode`、`toString`、一个合成构造器）。
3. 在 /tmp/lombok 下加 `@RequiredArgsConstructor` 版的 `TripSpecL`：两个 `final String` 字段 + 一个 `int`，javap 验证生成的构造器是 `(java.lang.String, java.lang.String, int)`。

## 参考答案

**练 1**：

```java
public record TrainTripR(Long id, String trainNo, String fromCity, String toCity,
                         String departTime, String arriveTime,
                         int totalSeats, int priceYuan) {}

public class Main {
    public static void main(String[] a) {
        var t = new TrainTripR(1L, "G1024", "上海虹桥", "苏州", "08:00", "08:35", 3, 42);
        System.out.println(t);                    // TrainTripR[id=1, trainNo=G1024, ...]
        System.out.println(t.priceYuan());        // 42（注意不是 getPriceYuan）
    }
}
```

**练 2**：javap 输出包含：`public ... TrainTripR(...)`（合成全参构造器）、
`Long id()`、`String trainNo()`、`...`（每个字段一个同名访问器）、
`equals(java.lang.Object)`、`hashCode()`、`toString()`。
record 的访问器**与字段同名**（不带 get 前缀）是它和 Lombok 生成码最大的表面差异。

**练 3**：

```java
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class TripSpecL {
    public final String no;      // final 字段才"必填"
    public final String from;
    public int seats;            // 非 final 不进 @RequiredArgsConstructor
}
```

javap 里出现 `public TripSpecL(java.lang.String, java.lang.String);`——第三个字段因不 final 被"必填构造器"排除。这就是"必填"二字的含义：**final/`@NonNull` 的字段必须由构造器喂饱**。

## 本节小结
- JavaBean 的样板问题真实存在；Lombok 用 @Data/@Getter/@Setter/@RequiredArgsConstructor 在**编译期**把方法织进字节码，运行零开销。
- 动手验证过：javap 能直接看到 Lombok 生成的方法，证明它是编译期处理不是运行期反射。
- Lombok 的代价：IDE 依赖、隐式生成、"看不到的源码"；团队协作与非 Lombok 工具链要付适配费。
- 本站刻意用 **public 字段 + record** 做教学取舍：让 JPA/Redis/Kafka 这些主角不被样板干扰；record 是 Java 16+ 的语言级不可变数据袋，比 Lombok 更"正"。
- 生产上的合理路线：不可变数据用 record；必须可变的实体用 private 字段 + 构造注入（或规范的 Lombok）。

## 下一站

A10 踩坑清单：NPE、==与 equals、可变共享、浮点算钱——四个"Java 玩家真实掉过井"的坑，
每个都配最小复现与修复；本站 takeaway 的订单金额计算（OrderService `o.totalYuan = dish.priceYuan * qty`）
为什么用整数存钱，这一站给你从 BigDecimal 角度做对照实验。
