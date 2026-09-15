# Java速通-A01 · 类与对象：从 C++ 视角

> 三行件头：①要点——语法与 C++ 表亲级相似，内存模型根本不同（无栈上对象、无指针运算、无 delete）；②前置——会 C++ 的 class/构造器/指针，`jshell` 可跑；③产出——会论证"一切的引用"，并用一段真跑的 C++ 程序 + jshell 对照，钉死引用语义。
>
> 🗺 主线进度：Java 速通第 1 站 | 🎞 上一站：Z04 Maven | 📀 本站所得：一颗"引用语义"的钉子 + C++/Java 并列实测输出。

## 🎬 开篇三件套

### 你在哪格 + 任务单
你在排练厅。任务：①字段/构造器/this/static；②public/protected（以及 Java 没有 friend/多重继承）；③建立内存模型心智图（**对象全在堆，变量是引用**）；④与 C++ 的关键差异清单；⑤ jshell 跑通第一个类。

### 开工前自查
- [ ] 会 class/构造函数/指针
- [ ] JDK21 装好，`jshell` 能进
- [ ] 见过 `Task.java` 不必读懂，下面带读

---

## 名词卡

| 名词 | 人话 | C++ 对应 |
|---|---|---|
| 类/对象 | 蓝图→具体产物 | 同义 |
| **引用** | 指向对象的"受控指针"，不可算术 | 指针/引用的混血 |
| **堆** | 所有对象的家 | C++ 可栈可堆，Java 只在堆 |
| GC | 自动回收没人引用的对象 | 包了你的 delete 活 |
| 构造器 | new 时执行 | 同 |
| this | 当前对象引用 | this 指针（不可取地址） |
| static | 属于类不属于对象 | static 成员 |
| 装箱类型（Long/Integer） | 原始类型的对象版（可 null） | 无 |
| jshell | JDK 自带 REPL | Python REPL |

## 1. 概念最小人话 + 内存图

```text
 栈（线程私有）                    堆（所有对象的家，GC 管）
 ┌─────────────┐          ┌──────────────────────┐
 │ c ──────────┼────────> │ Cat@1a2b name:"咪咪" │
 │ d ──────────┼───┐      │ Cat@3c4d name:"旺财" │
 └─────────────┘   │        └──────────────────────┘
                   └────────────> 同一对象（c/d 是别名）
```

C++ 有三张图（栈对象、堆对象+delete、智能指针），Java 只剩一种。推论：无 delete、无指针运算；`b = a` 是别名不是拷贝；也因此多了"共享可变状态"问题（A03 用真案例做实证）。

## 2. 真实代码走查：demo01 的 Task 类

`backend/demo-todo/src/main/java/com/javaweb/todo/model/Task.java`（25 行）：

```java
 9: @Entity
10: public class Task {
11:     @Id
12:     @GeneratedValue(strategy = GenerationType.IDENTITY)
13:     public Long id;              ← Long（包装类）不是 long：可为 null
15:     @NotBlank(message = "标题不能为空")
16:     public String title;
18:     public boolean done = false;
20:     protected Task() {}          ← JPA 需无参构造（protected 即可）
22:     public Task(String title) { this.title = title; }
```

五条 C++ 视角逐条读：
1. **public 字段直接裸奔**：C++ 老兵皱眉，但 JPA 框架要反射访问，教学版省 getter/setter（A09 专题）；
2. **Long vs long**：主键"还不存在"必须是 null，原始类型没有 null；JPA 的 `@GeneratedValue` 靠"id 为 null"判定"新记录"；
3. **protected Task() {}**：JPA 要"先 new 空对象再填字段"；反射可绕过 protected（protected/private 是给编译期的人看的纪律）；
4. **@Entity/@Id**：注解（贴给框架看的标签），C++ 的 attribute 只在编译期；
5. **没有析构函数**：生命周期由 GC 管，`done` 字段释放不用你操心。

再用 TaskController.java:26-29 看"引用语义的真实业务代价"：

```java
26:     public Task create(@Valid @RequestBody Task task) {
28:         task.id = null;               ← 就地修改"反序列化出来的对象"
29:         return repo.save(task);       ← 传走的是同一对象，不是拷贝
30:     }
```

`task` 是 Jackson 造出来的引用，第 28 行直接改、第 29 行传给 save——**一条引用走到黑**。这份代码在意"谁还能摸到这个 task"么？在意：因为它是**同一个对象**，第 28 行以后任何持有它的人看到的 id 都是 null。C++ 里若这条链路按值传递，第 28 行的修改在 return 后即消失——语义完全不同。

### static：属于类，不属于对象（快版）

`Counter.total` 类名直接访问；static 方法里不能用 this、不能摸实例字段——它没有"当前对象"。工具方法常写成 static（如 `AuthController.sha256`）。

## 3. 工程实录：C++ 程序模拟 Java 行为 + jshell 验证引用语义（本机实测）

### 3.1 先跑 C++（模拟"栈对象 vs 堆对象 + 值语义"）

`/tmp/opencode/CppAlias.cpp`：

```cpp
#include <cstdio>
#include <string>
struct Cat { std::string name; Cat(std::string n) : name(std::move(n)) {} };
void rename_stack(Cat c) { c.name = "changed-inside"; }   // 按值传：得到拷贝
void rename_heap (Cat* c) { c->name = "changed-inside"; } // 用指针模拟"传引用"
int main() {
    Cat a("mimi");
    Cat b = a;                 // C++ 复制语义：b 是新对象！
    b.name = "double";
    printf("C++ stack copy: a=%s b=%s (two objects)\n", a.name.c_str(), b.name.c_str());
    Cat* h = new Cat("heapcat");
    rename_heap(h);
    printf("heap via ptr:   %s\n", h->name.c_str());
    rename_stack(h[0]);        // 函数拿到拷贝，改不影响原对象
    printf("pass-by-value:  %s\n", h->name.c_str());
    delete h;                  // Java 的 GC 替你做
    return 0;
}
```

真跑输出（本机 g++ -std=c++17）：

```
C++ stack copy: a=mimi b=double (two objects)
heap via ptr:   changed-inside
pass-by-value:  changed-inside
```

**读输出**：`b = a` 之后 b 改名 a 不动——C++ 复制**对象**；Java 里同样那两行，b 与 a 是**同一个对象**的两个名字。这个分歧就是全篇的核心。

### 3.2 再跑 Java（一句话验证引用语义）

```bash
$ jshell -q
jshell> class Cat { String name; Cat(String name) { this.name = name; } }
jshell> var c = new Cat("mimi")
jshell> var d = c
jshell> d.name = "changed-inside"
jshell> System.out.println("c.name = " + c.name);
c.name = changed-inside         ← 没碰 c，c 也被改了
jshell> System.out.println("same object? " + (c == d));
same object? true               ← == 确认：d 和 c 是同一个对象
```

真机完整输出：

```text
jshell> jshell> jshell> jshell> $4 ==> "changed-inside"
jshell> c.name = changed-inside
jshell> same object? true
```

对照 3.1 的 C++ 输出，三条时间线一横排就是一张"引用语义"的教学卡：

| 操作 | C++ 输出/行为 | Java 输出/行为 |
|---|---|---|
| `b = a` 后改 b | a 不变（两份） | a 跟着变（一份） |
| 函数形参改名 | 不影响实参（按值拷贝） | 影响实参（引用按值传） |
| 栈 vs 堆 | 三种活法 | 一种活法 |

### 3.3 null 的味道（jshell 一行）

```java
jshell> Cat x = null; x.meow()
|  Exception java.lang.NullPointerException      ← 确定异常，可捕（不是段错误）
```

C++ 解引用空指针是未定义行为（可能段错误）；Java 是确定的异常，可捕获（A04 讲栈回溯）——"从段错误到异常"的体验升级。jshell 里复现：

### 3.4 jshell 三步曲（语法沙盒的第一课）

```bash
$ jshell
jshell> class Cat { String name; Cat(String name) { this.name = name; } void meow() { System.out.println(name + ": 喵"); } }
jshell> var c = new Cat("咪咪")
jshell> c.meow()
咪咪: 喵
```

jshell 是 JDK 自带 REPL（`$JAVA_HOME/bin/jshell`），改一行立刻重跑。写 C++ 时你要 g++ 编译+运行，这里敲完回车就出结果——**语法实验的成本降到零**。
## 4. 模式对比 / 选型表

| Java | 可见范围 | C++ 对照 |
|---|---|---|
| public | 任何人 | public |
| protected | 同包+子类 | 类似（"包"参与可见性） |
| （不写） | 同包 | Java 默认 |
| private | 本类 | private |

Java 没有 friend、无多继承（类 extends 一个，implements 可多个——A02）。

## 5. 动手验证（自己跑一遍 + 预期输出对账）

1. 复跑 3.1 的 C++ 与 3.2 的 jshell，逐行对账（特别是"a=mimi b=double (two objects)"那行——它是全书的分水岭）。
2. `this` 与链式（jshell）：

```java
jshell> class Box { int v; Box(int v){this.v=v;} Box plus(Box o){return new Box(this.v+o.v);} Box twice(){return plus(this);} }
jshell> new Box(3).plus(new Box(4)).twice().v
$3 ==> 14
```

3. static 共享（jshell）：`class Counter { static int total = 0; int id; }`，`var a=new Counter(); var b=new Counter(); Counter.total=7;`，`Counter.total` 一份全体看。
4. null 的味道（3.3 那一行）亲自炸一次，把异常念出声。

## 思考题

1. Java 敢把对象全放堆上，C++ 为什么不肯？（提示：栈分配快在哪？Java 用什么补偿？）
2. `Task.id` 是 `Long` 不是 `long`，除可 null 外还有什么好处（和第 28 行置 null 有关）？
3. `protected Task(){}` 被反射怎么绕过？protected/private 到底是给谁看的？
4. "Java 全是传引用"这句哪里错了？
5. 实录 3.1 的 C++ 程序里 `rename_stack(h[0])` 说的是"函数不改原对象"——那条与 TaskController.java:28"就地修改 task"对照起来，你能不能用"形参是不是引用"一句话把两种语言分清？

## 练习题

**练习 1**：jshell 写 `Ticket`（trainNo/seatNo 字段+构造器+`label()`），new 两个打印。
**练习 2**：加 `static int sold=0`，构造器里 `sold++`，new 三个验证 static 是全体共享。
**练习 3**：把 Task.java 抄进 jshell 报错（缺 jakarta 依赖），手动写"去注解版"对照。

### 完整参考答案

**思考 1**：栈分配快在"移动栈指针即分配、方法返回即回收"。Java 全堆因为对象要跨方法共享、生命周期不明，栈语义做不到；补偿是 JVM 逃逸分析把没逃出方法的对象栈上分配/标量替换，加上分代 GC，分配本身只是指针碰撞。C++ 承诺确定性析构（RAII），栈对象退出作用域必须立刻调析构。
**思考 2**：`long` 无法赋 null，编译不过；`Long` 是对象才能置 null，而 JPA 的 `@GeneratedValue` 正是靠"id 为 null"判断"新记录，请数据库生成主键"。
**思考 3**：反射 `setAccessible(true)` 短路访问检查（受模块系统管控但同模块内默认放行）。protected/private 是给"编译期的人"看的纪律，运行时框架持通行证。
**思考 4**：按值传递，值是引用。传原始类型传值本身（改不动外面）；传对象传"引用的值"——方法内改对象内容影响外面，但把参数指向新对象不影响外面。"全是传引用"错在忽略原始类型与重新赋值两处。
**思考 5**：C++ 默认按值（想共享得 `Cat&`/`Cat*`/`C&`）；Java 对象参数永远是"引用的值"——所以 TaskController 的 `task.id = null` 对调用者可见。一句话：**C++ 默认复制对象，Java 默认共享对象**。

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

---

## 本节小结
- 语法像 C++，内存模型天差地别：**对象全堆，变量是引用，GC 收尾**。
- `Long` vs `long`：包装类可为 null，JPA 主键靠它表达"待生成"。
- protected 无参构造是 JPA 实体标配；注解是贴给框架看的标签。
- 一份 C++ 实测 + 一份 jshell 实测双证"C++ 复制语义 / Java 引用语义"的分叉（`b = a` 是两份还是一份）。
- 口诀带走：**C++ 默认复制对象，Java 默认共享对象**。
- jshell 是语法沙盒，任何疑问先去试。

## 下一站

[Java速通-A02-record、枚举与接口.md](Java速通-A02-record、枚举与接口.md)——"只想传个数据"却要写构造器/getter/equals？record 一行搞定；以及 JPA 实体为什么**不能**用 record（一次 keyword 真编译报错）。
