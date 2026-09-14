# Java速通 A06 · 注解与反射：为什么 Spring 满是 @ 符号

> **本站你走在大厅哪一格**：你已经会写 Java 类、方法、继承（A01-A05）。本篇走到"大厅的镜子墙"——
> 从这里你终于能回答一个问题：`@RestController`、`@Service` 这些 @ 符号，到底凭什么能改程序的行为？
> 答案的两个关键词：**注解（元数据）** 和 **反射（运行期照镜子）**。后面所有 Spring 教程（S01 起）都站在这面镜子前。

## 名词卡

| 名词 | 人话 |
|---|---|
| 注解（Annotation） | 贴在类/方法/字段上的**标签**，本身不执行任何代码，只是"写在字节码里的备注" |
| 元数据 | "关于数据的数据"。注解就是关于代码的数据 |
| 反射（Reflection） | 程序**运行期**按字符串名字查类、查方法、查注解，甚至调用它们的能力 |
| `Class<T>` 对象 | 每个 Java 类在 JVM 里都有一面"镜子"，`Foo.class` 就是拿到它的把手 |
| RetentionPolicy | 注解"活多久"：SOURCE（编译后扔掉）/ CLASS（进字节码）/ RUNTIME（反射可读） |
| 处理器 | 读注解、然后**干实事**的代码。Spring 就是一个巨大的注解处理器 |

## 一、大白话：注解只是标签，反射是读标签的人

C++ 同学先稳住：Java 注解**不是**预处理宏。`#pragma` 或宏在编译前就把代码改了；
Java 注解哪怕一个字都不写处理逻辑，程序行为也**完全不变**——它只是被'贴上去'，然后编译进 .class 文件。

真正让它"起作用"的是**另一段代码**在运行期把标签读出来、按标签办事：

```
@RestController  ←——→  "这个类是路由器，帮我注册 URL 映射"
@Service         ←——→  "这个类请帮我 new 一个，单例，谁要就发给谁"
```

读标签的机制就是**反射**：

- 你有类名就能加载类：`Class.forName("com.javaweb.train.TrainApp")`
- 有 `Class` 对象就能列出所有方法/字段/注解：`clazz.getMethods()`、`clazz.getAnnotations()`
- 甚至能按字符串调方法：`method.invoke(obj, args)`

一句话对比 C++：C++ 编译完，代码的名字大多就"擦掉了"（符号还剩点，类型信息几乎没了）；
Java 编译器**保留全套类型信息**在 .class 里——反射就是翻这份档案。

Spring 启动时干的事，本质上就是：扫描你 `com.javaweb` 包下所有 .class → 反射读注解 →
看到 `@RestController` 就按路由处理，看到 `@Service` 就造一个实例放进 IoC 容器。**框架比你先读完了你的代码。**

## 二、真实代码走查：本项目的注解密度

随便挑一个类 `backend/takeaway/src/main/java/com/javaweb/takeaway/service/OrderService.java`：

```java
@Service                                    // ← 注解①：进 Spring 容器，造一个实例
public class OrderService {
    @Transactional                          // ← 注解②：方法跑在数据库事务里
    public Order create(Long userId, BookReq req) { ... }
}
```

其中 `OrderService` 里还藏着一条名字很有"record 味"的定义：

```java
public record Item(Long shopId, Long dishId, Integer quantity) {}
```

record 是 A09 的主角，这里只看它也是"把结构信息写进类似元数据的东西"。

再看拦截器 `backend/train/src/main/java/com/javaweb/train/security/AuthInterceptor.java`：

```java
@Component                                  // 告诉 Spring：把我造出来管起来
public class AuthInterceptor implements HandlerInterceptor {
    @Override                               // 告诉编译器：我声明覆盖了接口方法，拼错就报错
    public boolean preHandle(...) { ... }
}
```

注意 `@Override` 的 RetentionPolicy 是 **SOURCE**——只给编译器看，编译完就扔，反射都看不见它。
而 `@Service`、`@Component` 是 **RUNTIME**——所以反射读得到。同一套语法，生命周期不同，用途就分了层。

### 注解三件套语法（自己写一个时要用）

```java
@Retention(RetentionPolicy.RUNTIME)   // 活到运行期（否则反射读不到）
@Target(ElementType.METHOD)           // 只准贴在方法上
public @interface ReRun {             // @interface = 定义注解
    int times() default 1;            // 注解可以带"参数"，带默认值
}
```

## 三、动手验证：写自定义注解 + 反射跑一个"复读机"

这个 30 行的小程序验证三件事：**注解贴上去 → 字节码里真有 → 反射读出来并驱动行为**。
全程不需要 Maven，JDK 就够。

### 步骤 1：建目录写三个文件

```bash
mkdir -p /tmp/refl && cd /tmp/refl
```

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

**ReflDemo.java**（处理器：反射读标签，用标签驱动循环次数）：

```java
import java.lang.reflect.Method;

public class ReflDemo {
    public static void main(String[] args) throws Exception {
        // 1) 按名字拿"镜子"：这才是"运行期看名字拿对象/方法"
        Class<?> c = Class.forName("Stage");
        Object stage = c.getDeclaredConstructor().newInstance();  // 字符串也能 new 对象

        for (Method m : c.getDeclaredMethods()) {
            Mirror tag = m.getAnnotation(Mirror.class);
            if (tag == null) continue;                 // 没贴标签的方法跳过
            int times = 1;
            if (tag.actor().equals("站长")) times = 2; // 标签内容驱动逻辑
            for (int i = 0; i < times; i++) m.invoke(stage);  // 反射调用
        }
    }
}
```

### 步骤 2：编译并运行

```bash
cd /tmp/refl && javac *.java && java ReflDemo
```

预期输出——`tick` 因贴了 `actor = "站长"` 被**打两次**，`shout` 一次，`silent` 完全不出现：

```
叮 —— 检票口响了
叮 —— 检票口响了
前方到站：苏州
```

### 步骤 3：用 javap 亲自验尸字节码（证明注解真的进了 class 文件）

```bash
javap -v Stage.class | grep -A3 RuntimeVisibleAnnotations
```

你会看到类似：

```
RuntimeVisibleAnnotations:
  Mirror(actor="站长")
```

这就是证据：注解**不是编译期魔法**，它以 `RuntimeVisible` 形式真实躺在字节码常量池里，
所以运行期的反射 API 把它读了出来。如果把它改成 `RetentionPolicy.SOURCE` 重新编译，
上面 grep 的结果会消失，`java ReflDemo` 输出也随之变成空——三种生命周期，行为立刻不同，
这是理解注解最直接的一刀。

> **这个 demo 与本站的关系**：`BookingService` 上贴的 `@Service`、`@Scheduled`
> （backend/train/src/main/java/com/javaweb/train/service/BookingService.java:22 与 :115），
> 就是 Spring 启动时用和 `ReflDemo` 一模一样的方式（`getAnnotation` → 按结果行事）
> 处理出来的：`@Scheduled` 让 Spring 每过 fixedDelay 毫秒反射调用一次 `closeExpired()`。

### 与 C++ 的一句对比

`#pragma once` / 宏是**编译前改代码**，改完原文就没了；Java 注解是**不改代码、留标签，
由运行期的处理器决定怎么对待这段代码**——所以同一个 `@Transactional` 闭着眼是空标签，
睁眼（反射）看才有事务。思考题 T1 会考你这一点。

## 三点五、反射 API 速查 + 本站注解对照表

反射常用 API 就这几个，A01-A06 里出现过的所有注解都能对上号：

| 反射 API | 干什么 | 本站对应物 |
|---|---|---|
| `Class.forName("全限定名")` | 按名字加载类 | `Class.forName("com.javaweb.train.TrainApp")` |
| `getDeclaredConstructor().newInstance()` | 反射造对象 | Spring IoC 容器 `@Service` 类实例化的内核 |
| `clazz.getAnnotation(A.class)` | 读单个注解 | Spring 启动扫描 `@RestController` |
| `clazz.isAnnotationPresent(A.class)` | 有没有贴标签 | `@RequestMapping` 分发的判断素材 |
| `method.invoke(obj, args)` | 反射调方法 | `@Scheduled` 每 15 秒反射调用 `closeExpired()` |
| `field.get(obj)` | 直插私有/公有字段 | Jackson 把 H2 实体序列化成 JSON 的后备手段 |

放回本站：**train 服务的 `@Scheduled` 定时关单**（backend/train/src/main/java/com/javaweb/train/service/BookingService.java:104）
是反射最直白的活教材——Spring 启动时反射扫到这个注解，记住"每 15 秒调用一次"；
之后每个周期 `method.invoke(serviceInstance)` 把它叫醒。Controller 的 URL 映射、
JwtUtil 里的 `@Value("${app.jwt.ttl-minutes:60}")` 注入也一样：反射读注解、按 key 去 application.yml 拿值。

> 换句话说：本项目的每一个 `@xxx` 都在等你写完 ReflDemo 才完全看懂——你现在已经是懂"处理器内幕"的人了。

## 思考题

1. 你写了个 `@Retention(SOURCE)` 的 `@FastLog` 注解并贴在方法上，程序运行时反射 `getAnnotation` 会拿到什么？为什么说"注解本身不改变行为，处理器才改变行为"？
2. 反射按字符串 `Class.forName("Stage")` 拿类，这不就是泄露式的"运行期名字表"吗？和 C++ 模板、以及 dlopen+`dlsym` 按符号名取函数，异同在哪一点最本质？
3. `@Override` 为什么声明为 SOURCE 生命周期就够？如果 @Service 也保 SOURCE，Spring 还能工作吗？

## 练习题

1. 给 `Mirror.java` 的 `@Target` 加上 `ElementType.TYPE`，并把 `@Mirror(actor="戏台")` 贴到 `Stage` 类上；改 `ReflDemo` 使它反射读出**类上**的注解并打印 actor。完成后预期输出第一行是 `戏台`。
2. 用 javap 分别在 `RetentionPolicy.RUNTIME` 与 `SOURCE` 两个版本下 grep `Mirror`，记录两个输出差异。
3. 写一个注解 `@Route(path, method)`，反射扫描 Stage 的所有方法、把有 @Route 的打成 `path -> 方法名` 字典打印（模仿 Spring 路由注册的最小模型）。

## 参考答案

**练 1**：

```java
// Mirror.java 头部改为：
@Target({ElementType.METHOD, ElementType.TYPE})
// Stage.java 类声明上方加：@Mirror(actor = "戏台")
// ReflDemo.java main 开头加：
Mirror cls = c.getAnnotation(Mirror.class);
System.out.println(cls.actor());     // → 戏台
```

**练 2**：RUNTIME 版：`RuntimeVisibleAnnotations` 段出现 `Mirror(...)`；SOURCE 版：javap 输出里 `Mirror` 一词**完全消失**。结论：SOURCE 的标签存于源代码，编译产物里没有档案，运行期自然查无此注解。

**练 3**：

```java
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;

public class RouteDemo {
    public static void main(String[] a) throws Exception {
        Class<?> c = Class.forName("Stage");
        Map<String, String> routes = new LinkedHashMap<>();
        for (Method m : c.getDeclaredMethods()) {
            Route r = m.getAnnotation(Route.class);
            if (r != null) routes.put(r.path(), m.getName());
        }
        System.out.println(routes);
    }
}
```

配套 `@Route(path = "/tick", method = "GET")` 贴到一个方法上即可得到 `/tick -> tick`。

## 本节小结
- 注解 = 贴在代码上的**元数据标签**，本身零行为；`@Retention` 决定它活到 SOURCE/CLASS/RUNTIME 哪一层。
- 反射 = 运行期"看名字拿对象、看名字调方法、看名字读标签"，靠的是编译器留在 .class 里的完整类型档案。
- Spring / JPA / JWT、`@Service`、`@Transactional`、`@Scheduled` 全部是"标签 + 处理器"这套机制（一旦意识到这点，后面 S01-S14 的注解都不再神秘）。
- 与 C++ 的 #pragma/宏区别：宏改代码本体在编译前，注解不改本体、只留标签在字节码。

## 下一站

A07 我们把镜头从"类"拉到"工程"：Maven 多模块的 pom 树——正是 Maven 的注解处理一样的东西（插件 + 配置），
把 5 个 Spring Boot jar 和 2 个前端 dist 一次性构建出来。
