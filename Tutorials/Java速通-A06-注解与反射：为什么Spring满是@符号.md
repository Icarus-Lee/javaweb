# Java速通 A06 · 注解与反射：为什么 Spring 满是 @ 符号

> 三行件头：①要点——注解是"贴上去的标签、零行为"；反射是"运行期读标签的人"；②前置——A01-A05 语法已通、JDK 可跑 javap；③产出——**亲手写一个自定义注解 + 扫描类的 main（反射实测输出 + javap 验尸）**，把"标签+处理器"这套机制从概念变成你运行的程序。
>
> 🗺 主线进度：Java 速通第 6 站 | 🎞 上一站：A05 Stream | 📀 本站所得：一个能跑的"迷你 Spring"（反射读注解驱动行为）。

## 名词卡

| 名词 | 人话 |
|---|---|
| **注解（Annotation）** | 贴在类/方法/字段上的标签，本身零行为，只是"写在字节码里的备注" |
| **元数据** | "关于数据的数据"。注解就是关于代码的数据 |
| **反射（Reflection）** | 运行期按字符串名字查类、查方法、查注解，甚至调用它们 |
| **`Class<T>` 对象** | 每个类在 JVM 里的一面"镜子"，`Foo.class` 是拿镜子的把手 |
| **RetentionPolicy** | 注解活多久：SOURCE（编译后扔）/ CLASS（进字节码）/ RUNTIME（反射可读） |
| **处理器** | 读注解然后**干实事**的代码。Spring 是一个巨大的注解处理器 |
| **@Retention/@Target** | 定义注解时的两枚"(生命周期/贴哪儿"先决注解 |

## 一、大白话：注解只是标签，反射是读标签的人

C++ 同学先稳住：**Java 注解不是预处理宏**。`#pragma`/宏在编译前就改了代码；Java 注解一个字的处理逻辑都不写，程序行为**完全不变**——它只是被"贴上去"然后编进 .class。

真正让它"起作用"的是**另一段代码**在运行期把标签读出来按标签办事：

```
@RestController  ←——→ "这个类是路由器，帮我注册 URL 映射"
@Service         ←——→ "这个类请帮 new 一个单例，谁要发给谁"
```

读标签的机制是**反射**：有类名就能加载类（`Class.forName`）；有 `Class` 对象就能列出方法/字段/注解；甚至按字符串调方法（`method.invoke`）。
对比 C++：C++ 编译完名字大多被"擦掉"（类型信息几乎没了）；**Java 编译器把全套类型信息留在 .class 里——反射就是翻这份档案**。

Spring 启动干的事，本质：扫 `com.javaweb` 包下全部 .class → 反射读注解 → 见 `@RestController` 注册路由、见 `@Service` 造单例进容器。**框架比你先读完你的代码。**

## 二、真实代码走查：本项目的注解密度

随便挑一个类 `backend/takeaway/src/main/java/com/javaweb/takeaway/service/OrderService.java`：

```java
@Service                                    // ← 进容器造单例
public class OrderService {
    @Transactional                          // ← 方法跑在数据库事务里
    public Order create(Long userId, BookReq req) { ... }
    public record Item(Long shopId, Long dishId, Integer quantity) {}   // record 也有"元数据味"
}
```

拦截器 `backend/train/src/main/java/com/javaweb/train/security/AuthInterceptor.java`：

```java
@Component                                  // 管起来
public class AuthInterceptor implements HandlerInterceptor {
    @Override                               // 编译器报警：拼错就炸
    public boolean preHandle(...) { ... }
}
```

生命周期分层：`@Override` 的 RetentionPolicy 是 **SOURCE**（仅编译器看，编译完扔掉，反射不可见）；`@Service` 是 **RUNTIME**（反射可读）。同一套语法，生命周期不同用途就分了层。

### 注解三件套语法（自己写一个时要用）

```java
@Retention(RetentionPolicy.RUNTIME)   // 活到运行期（否则反射读不到）
@Target(ElementType.METHOD)           // 只准贴在方法上
public @interface ReRun {             // @interface = 定义注解
    int times() default 1;            // 注解可以带"参数"，带默认值
}
```

### 一个"知道的越多越安心"的对照：三种访问路径的门禁

| 门禁 | 编译期 | 反射不 setAccessible | setAccessible(true) |
|---|---|---|---|
| public 字段/方法 | ✅ | ✅ | ✅ |
| 同包字段/方法 | ✅ | ✅（同包默认放行） | ✅ |
| **private 的字段/方法** | ❌（同 outer 类除外） | ❌ IllegalAccessException（实测见 §四） | ✅ |

Spring/Jackson 的 autowire/字段填充、A01 的 protected 无参构造，都是"持通行证"的反射。Java 9+ 的模块系统可能收紧（`opens` 声明），本项目教学配置全放行。

```java
@Retention(RetentionPolicy.RUNTIME)   // 活到运行期（否则反射读不到）
@Target(ElementType.METHOD)           // 只准贴在方法上
public @interface ReRun {             // @interface = 定义注解
    int times() default 1;            // 注解可以带"参数"
}
```

## 三、工程实录：自定义注解 + 扫描类 main（反射实测，全真跑）

30 行小程序验证三件事：**注解贴上去 → 字节码里真在 → 反射读出来并驱动行为**。全程只需 JDK，无需 Maven。

### 步骤 1：写三个文件（/tmp/opencode/refl/）

**Mirror.java**（自定义注解）：

```java
import java.lang.annotation.*;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Mirror {
    String actor() default "路人甲";   // 像配置参数
}
```

**Stage.java**（被注解的类）：

```java
public class Stage {
    @Mirror(actor = "站长")
    public void tick() { System.out.println("叮 —— 检票口响了"); }

    @Mirror
    public void shout() { System.out.println("前方到站：苏州"); }

    public void silent() { System.out.println("[没贴标签，反射看不见我吧]"); }
}
```

**ReflDemo.java**（处理器：反射读标签，标签内容驱动循环次数）：

```java
import java.lang.reflect.Method;

public class ReflDemo {
    public static void main(String[] args) throws Exception {
        Class<?> c = Class.forName("Stage");                       // 1) 按名字拿"镜子"
        Object stage = c.getDeclaredConstructor().newInstance();   //    字符串也能 new 对象
        for (Method m : c.getDeclaredMethods()) {
            Mirror tag = m.getAnnotation(Mirror.class);            // 2) 读单个标签
            if (tag == null) continue;                             //    没贴标签跳过
            int times = 1;
            if (tag.actor().equals("站长")) times = 2;              //    标签内容驱动逻辑
            for (int i = 0; i < times; i++) m.invoke(stage);       // 3) 反射调用
        }
    }
}
```

### 步骤 2：编译并跑（真机输出）

```bash
$ cd /tmp/opencode/refl && javac *.java && java ReflDemo
```

本机实测输出——`tick` 因 `actor="站长"` 被打两次，`shout` 一次，`silent` 完全不出现：

```
叮 —— 检票口响了
叮 —— 检票口响了
前方到站：苏州
```

（注意方法论：`getDeclaredMethods` 的顺序不保证，本机实测输出顺序恰好是 tick→shout；所以处理器读多个方法时别依赖顺序，要靠"标签内容或注解字段"给语义——本例用 actor 字段正是为此。）

### 步骤 3：javap 验尸字节码（证明注解真进 class 文件）

```bash
$ javap -v Stage.class | grep -A4 RuntimeVisibleAnnotations
    RuntimeVisibleAnnotations:
      0: #31(#32=s#33)
        Mirror(
          actor="站长"
        )
```

这就是铁证：注解以 `RuntimeVisible` 形式躺在字节码常量池里，运行期反射 API 才读得到。把它改成 `RetentionPolicy.SOURCE` 重新编译，这段 grep 结果会消失、`java ReflDemo` 输出变空——三种生命周期，行为立刻不同，这是理解注解最直接的一刀。

### 排障小贴士（反射现场两句）

- `MyClass x = new MyClass(); MyAnno a = x.getClass().getAnnotation(MyAnno.class)` 通常是新手第一把"刺"，注意 class 常量用法 `MyClass.class`。
- 注解自己`@Retention(SOURCE)` 的话，程序运行反射读不到——看"注解有没有 Default"完全两码事。

## 四、反射 API 速查 + 本站注解对照表

| 反射 API | 干什么 | 本站对应物 |
|---|---|---|
| `Class.forName("全名")` | 按名字加载类 | Spring 启动扫描组件 |
| `getDeclaredConstructor().newInstance()` | 反射造对象 | IoC 容器 `@Service` 实例化内核 |
| `clazz.getAnnotation(A.class)` | 读单个注解 | 扫 `@RestController` |
| `method.invoke(obj,args)` | 反射调方法 | `@Scheduled` 每 15 秒叫醒 `closeExpired()` |
| `field.get(obj)` | 私有字段也读 | Jackson 序列化实体的后备手段 |

本项目最直白的活教材：**train 的 `@Scheduled` 定时关单**（BookingService.java 的 `@Scheduled(fixedDelay=15_000)` → `closeExpired()`），Spring 启动时反射扫到注解记住周期，此后每周期 `invoke` 一次——与 ReflDemo 的 `m.invoke(stage)` 是同一套动作。

### 再一刀：反射直插私有字段（"集成访问检查"本体）

JPA/Hibernate 给实体"先 new 空对象再填字段"，Jackson 从 public 字段取值，中间很多时候要**越过 private**。两行程序验证"访问检查"与"通行证"：

```java
// privtest/PrivGet.java（Vault.secret 是 private）
try {
    f.get(v);                          // 不 setAccessible
} catch (IllegalAccessException e) {
    System.out.println("不打卡: IllegalAccessException");
}
f.setAccessible(true);
System.out.println("打卡后读出: " + f.get(v));
```

真机输出：

```
不打卡: IllegalAccessException
打卡后读出: JWT密钥请勿外传
```

读输出：**private/protected 是"访问检查"的纪律**，反射在运行期保留了这道检查但给了开关——框架（Spring 的对象装配、Jackson 的字段填充、A01 的 protected 无参构造）都持这张通行证。
注意边界：同 outer class 内的嵌套类不触发（编译期本就可互访）；把 Vault 放独立类才出 IllegalAccessException——Java 的访问模型细节在反射照旧生效，这本身值得记住。

### 与 C++ 的一句对比

`#pragma`/宏是**编译前改代码**；注解是**不改本体、只留标签在字节码**，由运行期处理器决定行为——同一个 `@Transactional` 闭眼是空标签，睁眼（反射）看才有事务。

## 动手验证（含预期，全部对账）

1. §三 步骤 1~3 完整跑一遍，最终输出应是三行（两声"叮"+一声"前方到站"），且 silent 不出现。
2. `javap -v Stage.class | grep -A3 RuntimeVisibleAnnotations` → 两个 `Mirror(...)` transcribed；改成 SOURCE 版重编译后 grep 应为空。
3. §四 的 PrivGet（反射私有字段）：不 setAccessible 得 IllegalAccessException，之后读出 "JWT密钥请勿外传"。
4. 练 3 的 @Route 迷你路由：配三个方法验证字典打印。

## 思考题

1. 你写 `@Retention(SOURCE)` 的 `@FastLog` 贴方法上，运行时反射 `getAnnotation` 会拿到什么？为什么说"注解本身不改变行为，处理器才改变"？
2. 反射按名字 `Class.forName("Stage")`，和 C++ 的 dlopen+dlsym 按符号名取函数，与模板生成，最本质差异在？
3. `@Override` 为什么 SOURCE 就够？如果 `@Service` 也 SOURCE，Spring 还能活吗？
4. ReflDemo 里"没贴标签的 silent 没执行"——如果它贴了 `@Mirror` 但你想**按贴的顺序**执行，要额外依赖什么？这个限制对"手写路由扫描"意味着什么？

## 练习题 / 参考答案

**练 1**：给 Mirror 的 `@Target` 加 `ElementType.TYPE` 并在类上贴 `@Mirror(actor="戏台")`，反射读**类上**注解打印者：

```java
@Target({ElementType.METHOD, ElementType.TYPE})
Mirror cls = c.getAnnotation(Mirror.class);
System.out.println(cls.actor());     // → 戏台
```

**练 2**：RUNTIME 版 javap 出现 `RuntimeVisibleAnnotations: Mirror(...)`；SOURCE 版 `Mirror` 一词完全消失。结论：SOURCE 存于源码，产物里没档案。
**练 3**：写 `@Route(path, method)`，反射扫描出 `path -> 方法名` 字典（迷你 Spring 路由）：

```java
Map<String,String> routes = new LinkedHashMap<>();
for (Method m : c.getDeclaredMethods()) {
    Route r = m.getAnnotation(Route.class);
    if (r != null) routes.put(r.path(), m.getName());
}
```

**参考答案（思考题）**：
1. 拿到的是 `null`——SOURCE 标签没进 .class，运行期不存在的档案读不到。"处理器才改变行为"是这次全章的题眼：受检的是**标签生命期**而非语义。
2. dlsym 取的是**编译好的函数地址**（类型信息几乎没了）；模板是**编译期按类型逐份生成代码**；反射是**运行期翻类型档案**（连方法参数/注解/字段类型都在）。三者时间点：运行期地址查找 vs 编译期生成 vs 运行期反射档案。
3. `@Override` 只是给编译器看的"拼写检查"提示，编译完使命完成→SOURCE 够。但 Spring 的 `@Service` 注解在**运行期**被读，SOURCE 的话 Spring 根本看不见 → 无法工作。**生命周期由用途决定**。

## 本节小结
- 注解=元数据标签零行为；`@Retention` 决定活到 SOURCE/CLASS/RUNTIME 哪层——**用途决定生命周期**。
- 反射=运行期看名字拿类/方法/标签，靠 .class 里的完整类型档案。
- Spring/JPA/@Service/@Transactional/@Scheduled 全是"标签+处理器"；抄 ReflDemo 不需要 Maven，JDK 就够。
- 与 C++ 宏区别：宏改本体（编译前），注解留标签（编译后、运行期可读）。
- `getDeclaredMethods()` 顺序不保证——处理器逻辑别依赖顺序，靠注解字段驱动（这正是本次实录的方法论收获）。

## 下一站

A07 把镜头从"类"拉到"工程"：Maven 多模块 pom 树与 `mvn dependency:tree` 的真实输出——**reactor 一键构建 5 个 jar**。
