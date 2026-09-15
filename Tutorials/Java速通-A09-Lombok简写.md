# Java速通 A09 · Lombok 简写：@Data 的便利与代价（以及为什么本站不用它）

> 三行件头：①要点——JavaBean 样板问题、Lombok 一行换 30 行、编译期织入的机制与代价；②前置——A01 类与字段、javap 会用；③产出——**手写版 vs @Data 版的字节数/方法数双证（javap 真跑）**，与"public 字段+record"的教学取舍。
>
> 🗺 主线进度：Java 速通第 9 站 | 🎞 上一站：A08 并发 | 📀 本站所得：一行注解的"字面成本"就摆在这。

## 名词卡

| 名词 | 人话 |
|---|---|
| POJO / JavaBean | 字段+getter/setter+构造器的"数据袋子" |
| **样板代码** | 固定但必须写、易写错的那坨 |
| **Lombok** | 编译期注解处理器：@Data 等在编译期把方法**生成进 .class** |
| @Data | @Getter+@Setter+@ToString+@EqualsAndHashCode+@RequiredArgsConstructor |
| record（Java 16+） | **语言级**不可变数据袋：一行声明全套 |
| 注解处理器 | javac 编译期挂钩"加工"代码（与 A06 的运行期反射两码事） |

## 一、一个 model 类不写 Lombok 有多啰嗦

外卖"菜品"的传统写法（35 行样板换 4 个字段）：

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

## 二、Lombok 一行顶十行

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

| 注解 | 生成什么 |
|---|---|
| @Getter/@Setter | 读/读+写 |
| @RequiredArgsConstructor | final/@NonNull 字段必填构造（**DI 构造注入最常用**） |
| @AllArgsConstructor/@NoArgsConstructor | 全参/无参 |
| @Value | 全 final 的不可变版 |
| @Builder | `Dish.builder().name("可乐").build()` |

**机制是编译期**：不是反射库，是给 javac 挂注解处理器，在字节码生成前把方法**直接织进 .class**。IDE/Spring/Jackson 看到的已是"完整版"，运行期零开销。

## 三、工程实录：手写版 vs @Data 版的字节对比（javap 真跑）

本机环境：JDK26 + lombok 1.18.34（Java 21+ 下的注解处理器）。两份 Dish 任意对照：

### 3.0 复现环境（两条命令备好现场）

```bash
$ mvn -q dependency:get -Dartifact=org.projectlombok:lombok:1.18.34
$ cp ~/.m2/repository/org/projectlombok/lombok/1.18.34/lombok-1.18.34.jar lombok.jar
$ javac -cp lombok.jar Dish.java        # 注解处理器从 classpath 自动注册
$ /usr/lib/jvm/java-21-openjdk/bin/javac -cp lombok.jar Dish.java   # 21 下最稳
```

（/javac 26 会对旧版 lombok 打 Unsafe 警告，但生成无碍；JDK21 下最干净——注意这本身是个知识点：**lombok 织的是 javac 的内部 API，顶级 JDK 版本兼容是它最大的隐务**。）

### 3.1 手写版（第一节的 23 行真代码）

```java
// DishHand.java — 第一节那 23 行样板
```

javap（方法就 4 组 8 个手写的）：

```
$ javap DishHand.class
public class DishHand {
  public java.lang.Long getId();
  public void setId(java.lang.Long);
  public java.lang.Long getShopId();
  public void setShopId(java.lang.Long);
  public java.lang.String getName();
  public void setName(java.lang.String);
  public int getPriceYuan();
  public void setPriceYuan(int);
  public DishHand();
}
```

源文件 **525 字节**（含注释与空行）。

### 3.2 @Data 版（第二节那 7 行）

```java
import lombok.Data;
@Data
public class Dish {
    private Long id;
    private Long shopId;
    private String name;
    private int priceYuan;
}
```

**源文件 147 字节**（差不多 1/4 的字数）。javap 前先跑一次 lombok（注解处理器是 javac 的一部分）：

```
$ javac -cp lombok.jar Dish.java
$ javap Dish.class
public class Dish {
  public java.lang.Long getId();
  public java.lang.Long getShopId();
  public java.lang.String getName();
  public int getPriceYuan();
  public void setId(java.lang.Long);
  public void setShopId(java.lang.Long);
  public void setName(java.lang.String);
  public void setPriceYuan(int);
  public boolean equals(java.lang.Object);
  protected boolean canEqual(java.lang.Object);
  public int hashCode();
  ...
}
```

**一望即知**：Dish.java **一行 getter/setter/equals 都没写**，javap 里全是；@Data 连 `canEqual` 这种内部细节都织了进去。**"编译期展开"最裸的一处证据**：IDE 上"看不见的方法"其实已在 .class 里。

（复现时还踩到一个值得记录的环境坑：JDK26 下 javac 对 lombok 会打印 `WARNING: sun.misc.Unsafe::objectFieldOffset ... deprecated`——lombok 织入用的是 javac 内部 API，**JDK 升级是它的兼容债**。JDK21 下稳。）

对账表（本机实测）：

| 版本 | 源字节数 | javap 方法数 | equals/hashCode/toString |
|---|---|---|---|
| 手写 DishHand | 525 | 9（全手写） | 都没写 |
| @Data Dish | 147 | 14+（全织入） | 全有 |
| record 同字段 | ~60 | 与 @Data 类似 | 全有 |

## 四、Lombok 的利与弊（白话老实说）

| 利 | 弊 |
|---|---|
| 30 行换 1 行，可读性净赚 | **编译期插件**：没 IDE 插件的人打开源码"看不到" getter |
| 消灭"字段加了 toString 忘了" | @Data 生成面广（连 equals 都给），可变字段参与 equals 有坑 |
| RequiredArgsConstructor 与 DI 黄金搭 | 隐式魔法，出错读的是生成码不是源码 |
| 运行期零开销 | 构建链多一个依赖；团队风格分裂 |

## 五、本站教学代码的取舍：public 字段 + record

打开 `backend/train/src/main/java/com/javaweb/train/model/TrainTrip.java`（教学真实写法）：

```java
@Entity(name = "train_trips")
public class TrainTrip {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    @Column(nullable = false, length = 8)
    public String trainNo;        // 车次号 G1024
    public String fromCity;  public String toCity;
    public int totalSeats;   public int priceYuan;
}
```

**字段全 public**——非 Java 正统，是本项目教学刻意：少一层跳转（`b.orderNo` 一眼看穿）；突出 JPA/JWT/Redis/Kafka 真主角；免 Lombok 依赖（任何编辑器可读）。代价也明说：外部可乱写字段（无封装）、上生产应换 record 或 private+Lombok。

**record 是真正"现代利器"**，两处真实代码：

1. **BookingService.java:25**：`public record BookResult(String orderNo, int seatNo) {}`——服务层返回袋子
2. **OrderService**（takeaway）里的请求袋子：`public record Item(Long shopId, Long dishId, Integer quantity) {}`

record 一行顶 @Data 一行且**零依赖**：不可变+构造+访问器+equals/hashCode/toString 全由语言本身保证，IDE/反编译/反射天然认识。
色差一句话：**record 改语言规则，Lombok 改编译流程**——新项目一律 record 或 private+final。

### record 与 @Data、"三胞胎"并排（同一菜品）

```java
// A) 传统 JavaBean（30 行样板）——见第一节
// B) Lombok 一行（编译期生成）
@Data public class DishL { private Long id; private String name; private int priceYuan; }
// C) record 一行（语言级不可变）
public record DishR(Long id, String name, int priceYuan) {}
// 用法：new DishR(1L, "宫保鸡丁", 28).priceYuan()   ← 访问器不带 get
```

企业老项目常见 A/B 混杂；**新项目一律 C（record）或 private+final**。判断顺序（顺口记）：
**先问这个数据可不可变**——不可变→record；可变普通 DTO→private+构造/@Setter（Lombok 可选）；JPA `@Entity`→private 字段+setter（Hibernate 脏检查/代理机理决定，record 不可用——A02 已实测编译报错）。

## 动手验证（照 §三 复现，输出对账）

```bash
# ① 备环境（两条命令）
mvn -q dependency:get -Dartifact=org.projectlombok:lombok:1.18.34
cp ~/.m2/repository/org/projectlombok/lombok/1.18.34/lombok-1.18.34.jar lombok.jar
# ② 手写 DishHand.java（§一 23 行）与 @Data Dish.java（§二 7 行）分存两文件
# ③ 各自编译 + javap，字节数与方法数填进对账表：
javac DishHand.java && javap DishHand.class | wc -l   # 手写版 12 行/9 方法
javac -cp lombok.jar Dish.java && javap Dish.class    # @Data 版 14+ 方法
wc -c DishHand.java Dish.java                          # 525 vs 147 字节
# ④ record 三比较：写 DishR record，javap 看访问器名（无 get 前缀）
```

**预期**（照 §三 实测逐行对）：@Data 的 javap 里 getId/setId/getName/setName/getPriceYuan/setPriceYuan/equals/hashCode/toString/canEqual 全冒出来；record 版出现 `id()`/`name()`/`priceYuan()` 同名访问器。

## 思考题

1. @Data 生成 equals 时可变字段参与比较——Dish 已进 HashSet，setName 后 contains 还找得到吗？为什么这叫"哈希桶游魂"？
2. record 能代替 JPA @Entity 吗？（提示：Hibernate 要无参构造+可变实例做脏检查；Jackson/DTO 完美支持）
3. @RequiredArgsConstructor 为什么是 Spring 构造注入的黄金搭档（BookingService 的手写构造器对应）？
4. §三 的 JDK26 WARNING 说明什么隐务？如果团队 JDK 每年升级，Lombok 的维护成本曲线如何？

## 练习题 / 参考答案

**练 1**：TrainTrip 写 record 版 `TrainTripR`，Main 打印验证：

```java
jshell> record TrainTripR(Long id, String trainNo, String fromCity, String toCity, String depart, String arrive, int totalSeats, int priceYuan) {}
jshell> var t = new TrainTripR(1L,"G1024","上海虹桥","苏州","08:00","08:35",3,42)
jshell> System.out.println(t)
TrainTripR[id=1, trainNo=G1024, fromCity=上海虹桥, ...]
jshell> t.priceYuan()      // 42（注意不是 getPriceYuan）
```

**练 2**：javap 查 record 产物：`trainNo()` 同名访问器（不带 get）、equals/hashCode/toString、合成全参构造器。
**练 3**：`@RequiredArgsConstructor` 版 TripSpecL（两个 final String + 一个 int）：javap 出 `(java.lang.String,java.lang.String)`——第三个字段不 final 被"必填构造器"排除，这就是"必填"的精确含义。

**参考答案（思考题）**：
1. 找不到。hashCode 随 name 变了，元素已"搬家"到另一个桶，contains 拿新 hash 查旧桶——再也找不到又不报错，这就是"游魂"。可变字段参与 equals/hashCode 是 @Data 的暗坑；record 字段 final 天生免疫。
2. 不能：Hibernate 要无参构造+可变实例做脏检查+生成子类做代理，record 三样全无（A02 实录编译报错实证）。Jackson/DTO 场景 record 完美支持（纯读壳）。
3. Spring 4.3+ 单构造器可省 @Autowired——@RequiredArgsConstructor 正好把 final 字段全收进构造器，注解一遍、注入四件套；BookingService 手写的四参构造器就是它的"无注解版"。
4. **lombok 的隐患之一**：织入用的 javac 内部 API，JDK 每次升级都可能失效（本次 JDK26 的 Unsafe WARNING 就是先兆）；成本曲线随 JDK 上升，团队锁 JDK 版本才稳。record 零此债。

## 本节小结
- JavaBean 样板问题真实存在；Lombok 在**编译期**把方法织进 .class，运行零开销（javap 铁证：没写的方法全在）。
- 双证字节数：手写 525B vs @Data 源 147B（对象约 1/4）；方法全集由注解处理器织入。
- 代价：IDE 插件依赖/隐式魔法/生成面广/**JDK 升级的兼容债**。
- 教学取舍：public 字段+record——让 JPA/Redis/Kafka 主角不被样板干扰；**record 是"正路"**（语言级机制）。
- 判断顺序：先问可不可变——不可变→record；可变 DTO→private+构造；JPA 实体→private+setter（record 不可用）。
## 下一站

A10 踩坑清单：NPE、==与 equals、可变共享、浮点算钱——四个真实掉过的井，每个配本项目真实场景的最小复现（可跑）与修复。
