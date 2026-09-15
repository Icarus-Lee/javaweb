# Java速通-A04 · 异常与资源：try-with-resources

> 三行件头：①要点——异常类型树、throw/finally/throws、try-with-resources 对位 RAII；②前置——会 C++ try/catch 与 RAII、A01 的 NPE；③产出——**一段真跑的 try-with-resources 实验含栈回溯（playback 逐行打印）**，与 BookingService 分布式锁活教材。
>
> 🗺 主线进度：Java 速通第 4 站 | 🎞 上一站：A03 集合 | 📀 本站所得：异常分类学 + finally 的写法直觉。

## 🎬 开篇三件套

### 你在哪格 + 任务单
你在大厅值班经理室。任务：①记住类型树；②throw/try/catch/finally/throws + 受检异常；③try-with-resources 对上 RAII；④读懂 BookingService 锁的 try/finally；⑤Stream 里异常的考点。

### 开工前自查
- [ ] 会 try/catch、RAII、析构时机
- [ ] A01 的 NPE 体验
- [ ] Z02 见过 500 响应

---

## 名词卡

| 名词 | 人话 | C++ 对照 |
|---|---|---|
| throw / try/catch/finally | 抛/捕/无论成败都执行 | throw / try/catch（无 finally） |
| **throws** | 方法签名声明"我可能抛 X" | 异常规范（已废弃的反面） |
| **受检（checked）** | 编译器强迫处理 | 无，Java 特产 |
| 非受检（RuntimeException 系） | 编译器不管 | 大多 C++ 异常 |
| Error | JVM 灾难（OOM），别接 | 无 |
| **try-with-resources** | 括号里资源自动 close | RAII/unique_ptr |
| AutoCloseable | 有个 close() 就算资源 | 析构函数接口化 |
| 异常链 | catch 后包新异常继续抛 | 手工 cause |

## 1. 概念最小人话 + 类型树

```text
Throwable
├── Error                 ← JVM 灾难：OOM/StackOverflow（别接）
└── Exception
    ├── RuntimeException  ← 非受检：NPE/IllegalArgument/IllegalState…
    └── 其他 Exception     ← 受检：IOException/SQLException…（编译器监工）
```

受检 vs 非受检一句话：非受检多是程序 bug（本项目业务全用这类，靠 Spring 统一翻译成 HTTP 500）；受检是环境问题（文件没了/网络断了），**编译器当监工**，必须 catch 或 throws。

## 2. 真实代码走查：BookingService 的锁（活教材）

`backend/train/src/main/java/com/javaweb/train/service/BookingService.java:36-77`：

```java
41:         Boolean locked = redis.opsForValue()
43:                 .setIfAbsent(lockKey, "1", Duration.ofSeconds(10));
44:         if (locked == null || !locked) {
45:             throw new IllegalStateException("手速太快，请重试（车次处理中）");
46:         }
47:         try {
48:             Long stock = redis.opsForValue().decrement(stockKey);
53:             if (stock < 0) {
54:                 redis.opsForValue().increment(stockKey);   ← 先把票还回去
55:                 throw new IllegalStateException("已售罄");
56:             }
60:             TrainTrip trip = trips.findById(tripId)
61:                     .orElseThrow(() -> new IllegalArgumentException("车次不存在"));
74:         } finally {
75:             redis.delete(lockKey);         ← 无论成败都还锁
76:         }
```

四点：**选异常类型=给事故分类**（锁被占/票没了=IllegalState；参数不为存在=IllegalArgument）；**先还票再抛**（补偿思想）；**finally 必还锁**——不写 finally，第 55 行抛时锁就留守 10 秒才自释；`orElseThrow` 把"查不到"转异常。后面还有两处 catch 范式可背：

```java
// JwtUtil.java:36-45："不区分失败种类→null" 的 catch 转换语义
43:         } catch (Exception e) { return null; }

// AuthController.java:56-63：受检异常的"打包再抛"
61:         } catch (Exception e) { throw new RuntimeException(e); }   ← 异常链保留 cause
```

## 3. 工程实录：try-with-resources 真跑 + 栈回溯标本

一道最省事的三合一体：**资源自动关/异常拦/回溯打印**三件事一把跑。

```java
// /tmp/opencode/Twr.java（组装版）
public class Twr {
    static class Lock implements AutoCloseable {
        Lock() { System.out.println("开资源：拿到 lock"); }
        public void close() { System.out.println("关资源：释放 lock"); }
    }
    public static void main(String[] args) {
        try (var l = new Lock()) {
            System.out.println("干活：扣票");
            throw new IllegalStateException("已售罄");
        } catch (IllegalStateException e) {
            System.out.println("caught: " + e.getMessage());
        }
    }
}
```

真跑输出（javac && java，本机 JDK21/26 输出）：

```
开资源：拿到 lock
干活：扣票
关资源：释放 lock
caught: 已售罄
```

层次：**异常炸了，close() 照做**——try 块退出（无论正常/异常）先关资源再处理 catch。这正是 Java 版 RAII 和你熟悉的 C++ `{ R r; throw ...; }` 析构执行完全同语义。

### 栈回溯（playback 版）

实际排障时你最想要的是"回溯从底往上"。`/tmp/opencode/Twr2.java`：

```java
public class Twr2 {
    static class Res implements AutoCloseable {
        Res() { System.out.println("开资源"); }
        public void close() { System.out.println("关资源"); }
    }
    static void sell() { throw new IllegalStateException("已售罄"); }
    static void book() {
        try (var r = new Res()) { sell(); }
    }
    public static void main(String[] args) throws Exception {
        try { book(); } catch (Exception e) { e.printStackTrace(System.out); }
    }
}
```

真跑输出（逐行）：

```
开资源
关资源
java.lang.IllegalStateException: 已售罄
	at Twr2.sell(Twr2.java:6)
	at Twr2.book(Twr2.java:9)
	at Twr2.main(Twr2.java:13)
```

读得懂栈回溯就懂了整章：`已售罄` 是异常的 message，**第一行 at 是"案发地"**（sell 方法，Twr2.java:6），往上一行行就是"谁的外家"（book → main）。对接真实服务：logs/train.log 里 `IllegalArgumentException: 车次不存在` 的栈帧，**找第一行属于自己包名的代码**就是事发地。
顺手一次把 finally 的狠劲也验证（jshell 一行）：

```java
jshell> int f() { try { return 1; } finally { System.out.println("finally 跑了"); } }
jshell> f()
finally 跑了
$3 ==> 1                  ← return 拦不住 finally
```

总结一句：**try-with-resources 是语法糖（自动 close）；栈回溯是人能读的"事故牌"；finally 是机械保证"清理必走"**。

### 异常到 HTTP 响应的"翻译链"（实测补刀）

业务异常没被 catch 不是事故，而是"向上汇报"的正规姿势——最终由框架统一翻译。真机实测（train 站点）：

```bash
$ curl -s -X POST http://127.0.0.1:8084/api/auth/login -H 'Content-Type: application/json' \
    -d '{"username":"zt01","password":"wrongpw"}'
{"timestamp":"...","status":500,"error":"Internal Server Error","path":"/api/auth/login"}
```

链路：AuthController.java:53 `throw new IllegalStateException("密码错误")` → 一路上抛到 Spring MVC → 兜底翻译 500。三个 catch 姿势各归各位：

| 姿势 | 代表 | 语义 |
|---|---|---|
| catch 后 return null | JwtUtil.verify | 边界场景的"预期内失败"转简单值 |
| catch 后包 RuntimeException 再抛 | AuthController.sha256 | 受检异常甩锅+保留原因链 |
| 全不 catch，等框架翻译 | BookingService 抛属业务 | Spring 兜底变 500/400 |

## 4. 模式对比 / 选型表

| 场景 | Java 动作 | C++ 惯例 |
|---|---|---|
| 资源退出自动收 | try-with-resources | RAII 析构 |
| "查不到" | Optional.orElseThrow | if (!found) throw |
| 签名"可能炸" | throws X | 异常规范（已废弃） |
| 兜底包再抛 | throw new RuntimeException(e) | 手工保存 cause |
| 预期内失败 | catch 转null/Optional（JwtUtil.verify 风格） | 返回错误码 |
| 多资源逆序关 | try(a; b)（后开先关） | 构造逆序析构 |

## 5. 动手验证

1. 复跑 3 的两段（Twr/Twr2），把异常行号对账（Twr2.java:6 是案发地）。
2. 触发真实业务异常试一下：

```bash
$ TOKEN=$(curl -s -X POST http://127.0.0.1:8084/api/auth/login -H 'Content-Type: application/json' \
    -d '{"username":"zt01","password":"pw123456"}' | python3 -c 'import sys,json;print(json.load(sys.stdin)["token"])')
$ curl -s -X POST http://127.0.0.1:8084/api/bookings -H "Authorization: Bearer $TOKEN" \
    -H 'Content-Type: application/json' -d '{"tripId":999}'
```

后端日志（logs/train.log）出现 `IllegalArgumentException: 车次不存在` 的完整栈帧——`orElseThrow` 抛的。**从下往上找第一行属于自己包名的代码，就是事发地**。
3. jshell 玩 3.3 的 finally 狠劲；再试 `System.exit(0)` 前置 finally，理解"唯一拦得住的两种情况"。

## 思考题

1. BookingService 第 45 行抛锁时（try 还没进），锁要还么？为什么 try 从 47 开始？
2. IllegalState 与 IllegalArgument 的分界线？为什么"车次不存在"选后者？
3. Kotlin/C# 都抛弃了受检异常，说出至少一个痛点。
4. try-with-resources 里 close() 也抛异常，与 try 块的异常谁"赢"？

## 练习题

**练习 1**：jshell 写 `divide(int a,int b)`：b==0 时 `throw IllegalArgumentException("除数不能为0")`；调用 catch 打消息。
**练习 2**：写 `class Lock implements AutoCloseable`（构造打印"上锁"/close"解锁"）；写"带异常自动解锁"实验验证 finally 语义。
**练习 3**：仿 JwtUtil.verify 写 `parseUid(String s)`（NumberFormatException 返 null）；再写抛异常版对比调用方两种体验。

思考 5 特别答：想制造"close 也抛"，把第三节 Twr 的 close 改成 `public void close() { throw new RuntimeException("关别人"); }`，真跑后主异常仍是"已售罄"——关资源异常进了 suppressed 数组，`e.getSuppressed()` 能看到。**主逃逸不被清理事故掩盖**，这一点比 C++ 的 terminate 更稳。

```bash
# jshell 里两行验证（可选）
jshell> class Bad implements AutoCloseable { public void close() { throw new RuntimeException("close 坏"); } }
jshell> try { try (var b = new Bad()) { throw new IllegalStateException("主犯"); } } catch (Exception e) { System.out.println(e + " / suppressed=" + e.getSuppressed().length); }
java.lang.IllegalStateException: 主犯 / suppressed=1
```

**练习 1 参考**：

```java
jshell> int divide(int a, int b) { if (b == 0) throw new IllegalArgumentException("除数不能为0"); return a / b; }
jshell> try { divide(1,0); } catch (IllegalArgumentException e) { System.out.println(e.getMessage()); }
除数不能为0
```

**练习 2 参考**：

```java
jshell> class Lock implements AutoCloseable {
   ...>   Lock() { System.out.println("上锁"); }
   ...>   public void close() { System.out.println("解锁"); }
   ...> }
jshell> try (var l = new Lock()) { System.out.println("干活"); throw new RuntimeException("x"); }
上锁
干活
解锁
```

### 完整参考答案（原答汇总）

**思考 1**：不用。45 行锁没拿到就抛——没拥有何谈归还；try 范围＝"需收拾现场"的范围。
**思考 2**：IllegalState=当前状态不允许操作；IllegalArgument=你传进来的东西不对。"车次不存在"是 tripId 指向不存在=参数问题。
**思考 3**：①大量受检不可恢复，强迫 catch 产生海量空 catch/包装样板；②lambda 里受检没法直接抛；③升级加新受检破坏所有签名。
**思考 4**：try 块赢。close 异常被**抑制**（suppressed），getSuppressed 可取——保证主案卷不被清理现场的次生事故掩盖；C++ 里析构叠加异常直接 terminate。
**练习 3 参考**：null 版调用方必须判空（忘就是 NPE）；异常版路径显式。**预期内的常见失败→null/Optional；真正的异常情况→抛**。

---

## 本节小结
- 类型树：Error 别接；RuntimeException=非受检（bug 侧）；其他=受检（环境，编译器当监工）。
- 本项目业务全非受检，Spring 统一翻 HTTP 500/400。
- finally=无论成败收拾现场；BookingService 锁的 try/finally 是活教材。
- try-with-resources=Java 版 RAII；close 异常被抑制；"catch 转换语义/包装再抛/兜底翻译"三姿势各有主。
- 栈回溯读法：第一行 at=案发地。

## 下一站

[Java速通-A05-lambda与Stream：像Python的转置生成器.md](Java速通-A05-lambda与Stream：像Python的转置生成器.md)——map/filter/collect 管道，并用 demo-todo 的真 API 数据（curl 出来的任务列表）做一条真流水线。
