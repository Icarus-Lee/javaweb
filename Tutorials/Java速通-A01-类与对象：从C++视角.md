# Java速通-A01 · 类与对象：从 C++ 视角

> 你写过十年 C++（或至少写过三年）。好消息：Java 的 class 语法和 C++ 是表亲；坏消息：内存模型根本不同——没有栈上对象、没有指针运算、没有 delete。这一站把"相同"快速带过，把"不同"掰开揉碎。

---

## 🎬 开篇三件套

### 1️⃣ 本站你走在大厅哪一格

票务大厅里的每个实体——车次（TrainTrip）、订单（Booking）、用户（User）——在代码里都是一个**类**。本站你在排练厅里学基本功：怎么定义一个类、new 一个对象、理解它活在内存哪里。

### 2️⃣ 本站任务单

1. 会写：字段、构造器、方法、this、static；
2. 理解 public/protected（以及 Java 没有" friend/多重继承"）；
3. 建立 Java 内存模型的心智图：**一切对象都在堆上，变量只是引用**；
4. 列出与 C++ 的关键差异清单；
5. 用 jshell 三行跑通第一个类。

### 3️⃣ 开工前自查

- [ ] 会 C++ 的 class/构造函数/指针
- [ ] 本机有 JDK21（`jshell` 能启动）
- [ ] 见过 `Task.java` 长什么样（不必读懂，本站带读）

---

## 🗂 本站名词卡

| 名词 | 人话 | C++ 对应 |
|---|---|---|
| **类 class** | 对象的蓝图 | class，几乎同义 |
| **对象/实例** | 蓝图造出来的具体东西 | 对象 |
| **引用** | 指向对象的"受控指针"，不能做算术 | 指针/引用的混血 |
| **堆** | 所有对象的家 | C++ 里对象可在栈/堆，Java 只在堆 |
| **GC** | 垃圾回收器，自动回收没人引用的对象 | 你手动 delete 的活它包了 |
| **字段/方法** | 成员变量/成员函数 | 同义 |
| **构造器** | 与类同名、无返回类型、new 时执行 | 构造函数 |
| **this** | 当前对象的引用 | this 指针（但不可取地址、不可算术） |
| **static** | 属于类不属于对象 | static 成员 |
| **包 package** | 命名空间 + 目录结构 | namespace + 目录约定 |
| **jshell** | JDK 自带的 Java 交互式 REPL | 没有，类似 Python REPL |

---

## 🧠 概念人话

### 最小类：和 C++ 十分像

```java
class Cat {
    String name;                 // 字段（成员变量）
    int age;

    Cat(String name) {           // 构造器：与类同名、无返回类型
        this.name = name;        // this 消歧：字段 vs 参数
    }

    void meow() {                // 方法（成员函数）
        System.out.println(name + ": 喵");
    }
}
```

用法也像：`Cat c = new Cat("咪咪"); c.meow();`。差异藏在下面。

### 内存图：Java 只有一张图

```text
 栈（线程私有，自动伸缩）              堆（所有对象的家，GC 管）
 ┌─────────────────┐            ┌──────────────────────┐
 │ c  ──────────────┼───────────>│ Cat@1a2b  name:"咪咪" │
 │ d  ──────────────┼───┐        │ Cat@3c4d  name:"旺财" │
 └─────────────────┘   │        └──────────────────────┘
                       └──────────> 同一个对象（c 和 d 是别名）
```

对照 C++ 的三张图（栈对象、堆对象+delete、智能指针），Java 只剩一种：**变量是引用，对象全在堆**。推论：

- 没有 `delete`：没人引用的对象由 GC 自动回收；
- 没有指针运算、没有 `&` 取地址、没有 `->`（用 `.`）；
- `Cat c2 = c;` 复制的是**引用**（C++ 里默认会拷贝对象！Java 永远只拷贝引用）；
- 所以 Java 没有"拷贝构造"问题，但多了"共享可变状态"问题（A03 细讲）。

### 访问修饰符：比 C++ 少两个

| Java | 可见范围 | C++ 对照 |
|---|---|---|
| public | 任何人 | public |
| protected | 同包 + 子类 | 类似 protected（注意：Java 的"包"参与可见性！） |
| （不写，包私有） | 同包可见 | 没有对应物，是 Java 默认 |
| private | 本类 | private |

Java 没有 `friend`，没有多继承（类只能 extends 一个，接口可 implements 多个——A02 讲）。

### static：属于类，不属于对象

```java
class Counter {
    static int total = 0;        // 全体对象共享一份
    int id;                      // 每个对象一份
}
```

`Counter.total` 用类名直接访问。`static` 方法里**不能**用 this、不能摸实例字段——因为它没有"当前对象"。工具方法常写成 static（如 `AuthController.sha256`，AuthController.java:56）。

---

## 🔍 真实代码走查：demo01 的 Task 类

`backend/demo-todo/src/main/java/com/javaweb/todo/model/Task.java`（全 25 行）：

```java
 9: @Entity
10: public class Task {
11:     @Id
12:     @GeneratedValue(strategy = GenerationType.IDENTITY)
13:     public Long id;              ← 注意：Long（包装类）不是 long
14:
15:     @NotBlank(message = "标题不能为空")
16:     public String title;
17:
18:     public boolean done = false;
19:
20:     protected Task() {}          ← JPA 需要无参构造（protected 即可）
21:
22:     public Task(String title) {
23:         this.title = title;
24:     }
25: }
```

逐条从 C++ 视角解读：

1. **`public Long id` 直接裸奔**：字段是 public 的！C++ 老兵会皱眉，但这是 JPA 实体的教学惯例——框架要反射访问字段， getter/setter 省略让代码更短。业务类里仍建议 private + getter。
2. **`Long` vs `long`**：`Long` 是包装类（对象，可为 null），`long` 是原始类型。数据库主键可能"还不存在"（null），所以用 `Long`。C++ 没有这层区分。
3. **`protected Task() {}`**：第 20 行。JPA 规定实体必须有**无参构造器**（框架要"先 new 空对象再填字段"）。protected 防止外人乱用，同时放行框架（经反射）。C++ 里你会写 `Task() = default;`。
4. **`@Entity`/`@Id` 这些 @ 开头的东西**：注解（annotation），像"贴在类上的标签"，框架运行时读取它们决定行为。C++ 没有运行时可读的元数据（attribute 只在编译期）。
5. **没有析构函数**：对象生命周期由 GC 管，`done` 字段释放不需要你操心。

### 引用语义的现场证据：TaskController

`backend/demo-todo/src/main/java/com/javaweb/todo/controller/TaskController.java`：

```java
26:     @PostMapping
27:     public Task create(@Valid @RequestBody Task task) {
28:         task.id = null;               ← 新建：让数据库自增
29:         return repo.save(task);
30:     }
```

`task` 是框架反序列化出来的对象引用，第 28 行直接改它，第 29 行传给 `repo.save`——**传的是同一个对象**，不是拷贝。C++ 里 `f(Task t)` 会拷贝，Java 里永远是"传引用的值"（引用本身按值拷贝，指向同一对象）。

---

## 动手验证

### 实验 1：jshell 三行跑通（真实输出）

```bash
$ jshell
jshell> class Cat { String name; Cat(String name) { this.name = name; } void meow() { System.out.println(name + ": 喵"); } }
jshell> var c = new Cat("咪咪")
jshell> c.meow()
咪咪: 喵
```

jshell 是 JDK 自带 REPL（`$JAVA_HOME/bin/jshell`），改一行立刻重跑——学语法的沙盒。

### 实验 2：验证"引用别名"

```java
jshell> var d = c
jshell> d.name = "改了"
jshell> c.name
$5 ==> "改了"        ← d 和 c 是同一个对象的两张门牌
```

C++ 里 `auto d = c;` 默认**拷贝**出新对象；Java 里只复制引用。想"拷贝"得显式 clone 或重新构造。

### 实验 3：null 的味道

```java
jshell> Cat x = null
jshell> x.meow()
|  Exception java.lang.NullPointerException
```

C++ 解引用空指针是未定义行为（可能段错误）；Java 是确定的 `NullPointerException` 异常，可捕获（A04 讲）。这是"从段错误到异常"的体验升级。

### 实验 4：this 与方法重载（对比 C++ 成员函数）

```java
jshell> class Box {
   ...>   int v;
   ...>   Box(int v) { this.v = v; }          // this.v = 字段；v = 参数
   ...>   Box plus(Box o) { return new Box(this.v + o.v); }   // 必须写 this 消歧的场景
   ...>   Box twice()      { return plus(this); }              // this 当实参传
   ...> }
jshell> new Box(3).plus(new Box(4)).twice().v
$3 ==> 14
```

C++ 里 `this` 是指针（`this->v`），Java 里是引用（`this.v`），且**不能**对 this 取地址或做算术。链式调用 `new Box(3).plus(...).twice().v` 之所以可行，是因为每个方法都 `return` 新对象——不可变风格的雏形（A02 的 record 会正式登场）。

---

## 思考题

1. 为什么 Java 敢把对象全放堆上？C++ 为什么不肯？（提示：栈分配快在哪？Java 用什么补偿？）
2. `Task.id` 声明为 `Long` 而不是 `long`，除了可 null 还有一个好处，与第 28 行 `task.id = null` 有关，是什么？
3. `protected Task() {}` 里 protected 的用意是"框架能用、外人别用"。反射为什么能绕过 protected？
4. Java 方法参数传递到底是什么语义？"Java 全是传引用"这句话哪里错了？

## 练习题

**练习 1**：在 jshell 里写一个 `Ticket` 类：字段 `String trainNo; int seatNo;`，构造器两参，方法 `String label()` 返回 `"G1024-05座"` 这样的字符串。new 两个实例并打印 label。

**练习 2**：给 Ticket 加 `static int sold = 0;`，在构造器里 `sold++`。new 三个实例后打印 `Ticket.sold`，验证 static 是全体共享。

**练习 3**：把 Task.java 抄进 jshell 会报错（缺 jakarta 依赖）。请手动敲一个"去注解版" Task 类（三个字段 + 两个构造器），体会 JPA 版与普通类的差别只在注解。

### 完整参考答案

**思考题 1**：栈分配快在"移动栈指针即分配、方法返回即回收"。Java 全放堆是因为对象会被方法间共享、生命周期不明确，栈语义做不到。补偿手段：JVM 的**逃逸分析**会把"没逃出方法的对象"自动栈上分配/标量替换；加上分代 GC，分配本身只是指针碰撞，极快。C++ 不肯是因为它承诺确定性析构（RAII），栈上对象退出作用域必须立刻调析构。

**思考题 2**：`long`（原始类型）没有 null，赋 null 编译不过。`Long` 是对象才能置 null，而 JPA 的 `@GeneratedValue` 正是靠"id 为 null"判断"这是新记录，请数据库生成主键"。

**思考题 3**：反射的 `Constructor.newInstance` 会调用 `setAccessible(true)` 短路访问检查（受 Java 模块系统管控，但同模块内默认放行）。protected/private 是给"编译期的人"看的纪律，运行时框架持通行证。

**思考题 4**：语义是**按值传递，值是引用**。传原始类型（int 等）传的是值本身，方法内改不影响外面；传对象传的是引用的值，方法内改对象内容影响外面，但把参数指向新对象不影响外面。"全是传引用"错在忽略了原始类型，以及"重新赋值参数"不动外面。

**练习 1 参考**：

```java
jshell> class Ticket { String trainNo; int seatNo; Ticket(String t, int s) { trainNo = t; seatNo = s; } String label() { return trainNo + "-" + seatNo + "座"; } }
jshell> new Ticket("G1024", 5).label()
$2 ==> "G1024-5座"
```

**练习 2 参考**：

```java
jshell> class Ticket { static int sold = 0; Ticket() { sold++; } }
jshell> new Ticket(); new Ticket(); new Ticket();
jshell> Ticket.sold
$6 ==> 3
```

**练习 3 参考**：

```java
jshell> class Task { Long id; String title; boolean done = false; protected Task() {} public Task(String title) { this.title = title; } }
```

去注解后它就是个普通类——注解（@Entity/@Id/@NotBlank）才是让框架认领它的"工牌"。

---

## 本节小结
- 语法像 C++，内存模型天差地别：**对象全在堆，变量是引用，GC 收尾**。
- `Long` vs `long`：包装类可为 null，JPA 主键靠它表达"待生成"。
- protected 无参构造是 JPA 实体的标配；注解是贴给框架看的标签。
- 引用赋值=别名（不拷贝）；null 解引用=可控异常（不段错误）。
- jshell 是你的语法沙盒，任何疑问先去那里试。

## 下一站

[Java速通-A02-record、枚举与接口.md](Java速通-A02-record、枚举与接口.md)——"我只是想传个数据"却要写构造器/getter/equals？Java 16 的 record 一行搞定。看看本项目在哪些地方用了它，以及 JPA 实体为什么**不能**用 record。
