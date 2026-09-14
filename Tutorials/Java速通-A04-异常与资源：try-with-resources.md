# Java速通-A04 · 异常与资源：try-with-resources

> C++ 的 RAII 靠析构函数在栈展开时收尾；Java 没有析构函数，但有**异常体系 + finally + try-with-resources** 三件套。这一站把受检/非受检、异常类型树、throws 声明讲透，并用本项目 BookingService 的分布式锁代码做活教材。

---

## 🎬 开篇三件套

### 1️⃣ 本站你走在大厅哪一格

票务大厅每天都有"办砸的事"：手速太快撞上处理中的车次（锁被占）、票卖完了、车次号不存在、JWT 过期。后端怎么把"砸了"规范地报给前端？答案就是异常体系。本站你站在**大厅值班经理室**，学"出事时怎么喊、怎么收拾现场"。

### 2️⃣ 本站任务单

1. 记住异常类型树：Throwable → Error/Exception → RuntimeException/受检异常；
2. 会 throw/try/catch/finally/throws，理解受检异常这个 Java 特产；
3. 把 try-with-resources 和 RAII 对上号；
4. 读懂 BookingService 里锁的 try/finally 写法；
5. 知道 Stream 管道里处理异常的常见考点。

### 3️⃣ 开工前自查

- [ ] 会 C++ 的 try/catch、RAII、析构时机
- [ ] A01 的 NPE 体验（null 解引用=异常而非段错误）
- [ ] Z02 里见过 500 响应长什么样

---

## 🗂 本站名词卡

| 名词 | 人话 | C++ 对照 |
|---|---|---|
| **throw** | 抛出异常对象 | throw |
| **try/catch/finally** | 捕获/无论成败都执行 | try/catch（无 finally，靠 RAII） |
| **throws** | 方法签名上声明"我可能抛 X" | noexcept 的反面 / 异常规范（已废弃） |
| **受检异常（checked）** | 编译器强制你处理或声明的异常 | 无对应物，Java 特产 |
| **非受检异常（unchecked）** | RuntimeException 及其子类，编译器不管 | 大多数 C++ 异常 |
| **Error** | JVM 级灾难（OOM 等），别接 | 无 |
| **try-with-resources** | 括号里声明的资源在块结束时自动关闭 | RAII/unique_ptr |
| **AutoCloseable** | 有一个 close() 方法的接口（资源资格证） | 析构函数的接口化 |
| **异常链** | catch 后把原因包进新异常继续抛 | 手工保存 cause |

---

## 🧠 概念人话

### 异常类型树（背这张图）

```text
Throwable
├── Error                 ← JVM 灾难：OutOfMemoryError、StackOverflowError（别接）
└── Exception
    ├── RuntimeException  ← 非受检：NPE、IllegalArgument、IllegalState、数组越界…
    └── 其他 Exception     ← 受检：IOException、SQLException、InterruptedException…
```

- **非受检（RuntimeException 系）**：多半是**程序 bug**（传了 null、下标越界、状态不对）。编译器不强迫你处理。本项目业务代码全用这类：`throw new IllegalStateException("已售罄")`。
- **受检（其他 Exception）**：多半是**环境问题**（文件没了、网络断了）。编译器强迫你二选一：try/catch 掉，或在方法签名上 `throws IOException` 甩给调用者。C++ 没有这个概念——**编译器当监工**。
- **Error**：留给 JVM 自爆，业务代码不要 catch。

### throws：责任转嫁单

```java
public void readFile(String path) throws IOException {   // 我不处理，谁调用谁处理
    Files.readString(Path.of(path));
}
```

C++ 的异常规范（throw(...)）早就废弃了；Java 的 throws 活得好好的，编译器真的会检查。

### try-with-resources：Java 版 RAII

```java
try (var conn = openConnection()) {     // 声明在括号里 = 自动 close()
    conn.query(...);
}   // ← 无论正常结束还是异常退出，close() 都会被调（且异常时先 close 再传播）
```

资格证是 `AutoCloseable` 接口（单方法 `close()`）。对照 C++：析构函数→close() 方法，作用域退出→try 块结束，栈展开→自动 close。**没有栈上对象，就用语法结构模拟 RAII**。可以声明多个资源，逆序关闭（后开先关，和 C++ 析构顺序一致）。

---

## 🔍 真实代码走查

### 活教材：BookingService.book 的锁与异常

`backend/train/src/main/java/com/javaweb/train/service/BookingService.java:36-77`：

```java
41:         // 1) 谁先 set 成功谁锁门（10 秒自动解锁，防"人走门忘关"）
42:         Boolean locked = redis.opsForValue()
43:                 .setIfAbsent(lockKey, "1", Duration.ofSeconds(10));
44:         if (locked == null || !locked) {
45:             throw new IllegalStateException("手速太快，请重试（车次处理中）");
46:         }
47:         try {
48:             // 2) 原子扣票：DECR，结果为负即"卖重复了"，必须还回去
49:             Long stock = redis.opsForValue().decrement(stockKey);
50:             if (stock == null) {
51:                 throw new IllegalStateException("车次未初始化（stock key 缺失）");
52:             }
53:             if (stock < 0) {
54:                 redis.opsForValue().increment(stockKey);   ← 先把票还回去
55:                 throw new IllegalStateException("已售罄");
56:             }
...
60:             TrainTrip trip = trips.findById(tripId)
61:                     .orElseThrow(() -> new IllegalArgumentException("车次不存在"));
...
63:             Booking b = new Booking();
...
70:             b = bookings.save(b);          // 4) 落库（H2）
72:             producer.orderCreated(...);    // 5) 广播 Kafka
73:             return b;
74:         } finally {
75:             redis.delete(lockKey);         // 6) 归还锁（无论成败都还）
76:         }
```

异常教学点逐个数：

1. **三种异常对应三种业务事故**：锁被占=IllegalStateException（状态不对）、票没了=IllegalStateException、车次不存在=IllegalArgumentException（参数不对）。**选异常类型就是在给事故分类**。
2. **第 54 行"先还票再抛"**：异常不是"一抛了之"——抛出前要把已扣的资源还回去。这是"补偿"思想。
3. **第 74-76 行 finally**：从第 47 行 try 进入后，无论 return、异常还是正常走完，锁**必还**。如果只写 try 不写 finally，第 55 行抛异常时锁就永远留在 Redis 里（10 秒后才自动过期）——所有人排队干等。**finally 是"无论成败都收拾现场"的强制条款**。
4. **`orElseThrow(() -> new IllegalArgumentException(...))`**：Optional 的标准用法，把"查不到"转成异常——C++ 里你写 `if (!found) throw ...`，Java 把两步合成一步。

### 异常怎么变成 HTTP 响应

Z02 实测过：给不存在的用户登录返回 500 + JSON：

```json
{"timestamp":"...","status":500,"error":"Internal Server Error","path":"/api/auth/login"}
```

链路：AuthController.java:49 `orElseThrow(IllegalStateException)` → 异常一路上抛到 Spring MVC → 框架兜底把它变成 500。异常**没被 catch 不是事故**，而是"向上汇报"的正规姿势——最终由框架统一翻译成 HTTP 状态码。

### catch 后返回 null 的活例子：JwtUtil

```java
// backend/train/.../security/JwtUtil.java:36-41
36:     /** 校验并返回 uid；不合法/过期都返回 null（教学：不区分两类失败）。 */
37:     public Long verify(String token) {
38:         try {
39:             Claims c = Jwts.parser().verifyWith(key).build()
40:                            .parseSignedClaims(token).getPayload();
41:             return ((Number) c.get("uid")).longValue();
42:         } catch (Exception e) {
43:             return null;            ← token 坏/过期都算"没身份"
44:         }
45:     }
```

边界场景"token 无效是预期内情况"，catch 后转成 null 让调用方（AuthInterceptor.java:22 `if (uid == null) → 401`）用简单判断处理——**catch 转换语义**的典型。

### AuthController 的兜底 catch

```java
// backend/train/.../controller/AuthController.java:56-63
56:     private static String sha256(String s) {
57:         try {
58:             byte[] d = MessageDigest.getInstance("SHA-256").digest(...);
59:             return HexFormat.of().formatHex(d);
60:         } catch (Exception e) {
61:             throw new RuntimeException(e);      ← 异常链：把原因打包再抛
62:         }
63:     }
```

`MessageDigest.getInstance` 声明 throws **NoSuchAlgorithmException**（受检），编译器强迫处理。算法名是写死的常量、不可能失败，所以 catch 后包成 RuntimeException 转成非受检继续抛——**受检异常的"甩锅"标准姿势**：`throw new RuntimeException(e)` 保留原因链（getCause 能查到根因）。

---

## 动手验证

### 实验 1：触发真实业务异常

```bash
$ TOKEN=$(curl -s -X POST http://127.0.0.1:8084/api/auth/login -H 'Content-Type: application/json' \
    -d '{"username":"zt01","password":"pw123456"}' | python3 -c 'import sys,json;print(json.load(sys.stdin)["token"])')
$ curl -s -X POST http://127.0.0.1:8084/api/bookings -H "Authorization: Bearer $TOKEN" \
    -H 'Content-Type: application/json' -d '{"tripId":999}'
```

后端日志（logs/train.log）会出现 `IllegalArgumentException: 车次不存在` 的完整栈帧——第 61 行 orElseThrow 抛的。**看栈帧从下往上找第一行属于自己包名的代码**，就是事发地。

### 实验 2：jshell 里玩 try-with-resources

```java
jshell> class R implements AutoCloseable {
   ...>   R() { System.out.println("开资源"); }
   ...>   public void close() { System.out.println("关资源"); }
   ...> }
jshell> try (var r = new R()) { System.out.println("干活"); throw new RuntimeException("炸了"); }
开资源
干活
关资源                     ← 异常也没拦住 close！
|  异常异常 java.lang.RuntimeException: 炸了
```

对照 C++：`{ R r; throw ...; }` 析构函数照样跑——同一语义，不同机制（析构 vs close 接口）。

### 实验 3：finally 一定执行吗？

```java
jshell> int f() { try { return 1; } finally { System.out.println("finally 跑了"); } }
jshell> f()
finally 跑了
$3 ==> 1                  ← return 都拦不住 finally
```

唯一拦得住的是 `System.exit()` 和 JVM 直接崩——业务代码碰不到。

---

## 思考题

1. BookingService 第 45 行抛异常时，锁还没进 try 块——此时需要还锁吗？为什么第 47 行才开 try？
2. `IllegalStateException` 和 `IllegalArgumentException` 的语义分界线是什么？为什么"车次不存在"选了后者？
3. 受检异常的设计初衷是好的（强迫处理），为什么 Kotlin/C# 都抛弃了它？说出至少一个痛点。
4. try-with-resources 里如果 close() 自己也抛异常，和 try 块里的异常谁"赢"？

## 练习题

**练习 1**：jshell 里写 `divide(int a, int b)`：b==0 时 throw `IllegalArgumentException("除数不能为0")`；调用 `divide(1,0)` 并 catch 打印 `e.getMessage()`。

**练习 2**：实现一个 `class Lock implements AutoCloseable`：构造时打印"上锁"，close 打印"解锁"。用 try-with-resources 写"上锁→干活→自动解锁"，再写一个故意抛异常的版本验证解锁仍执行。

**练习 3**：模仿 JwtUtil.verify 写 `static Long parseUid(String s)`：`Long.parseLong(s)` 失败（NumberFormatException，非受检）时返回 null。再把它改成抛异常版本，比较两种 API 风格的调用方代码差异。

### 完整参考答案

**思考题 1**：不需要。第 44-46 行锁没拿到就抛，根本没拥有锁，何谈归还。try 块从第 47 行才开始，恰好覆盖"拥有锁之后"的全部区间——**try 的范围=需要收拾现场的范围**，多包无害但会误导读者以为前面试过的事也可能泄漏资源。

**思考题 2**：IllegalArgument="你传给我的东西不对"（参数错）；IllegalState="参数没问题，但当前状态不允许这个操作"（状态错）。"车次不存在"是参数 tripId 指向不存在的资源——参数问题，选 IllegalArgument。锁被占/票售罄是"此刻状态不允许"——IllegalState。

**思考题 3**：痛点至少三：① 大量受检异常实际不可恢复，强迫 catch 产生海量空 catch/包 RuntimeException 样板；② lambda/Stream 里受检异常没法直接抛（方法签名不带 throws，见下文考点）；③ 版本升级加抛新受检异常会破坏所有调用方签名。所以现代 JVM 语言（Kotlin）干脆全用非受检。

**思考题 4**：try 块的异常赢。close() 的异常被"抑制"（suppressed），附在主异常上，`getSuppressed()` 可取——保证主案卷不被清理现场的次生事故掩盖。C++ 里析构抛异常叠加在异常上是直接 terminate，Java 这点更稳。

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

**练习 3 参考**：

```java
// 返回 null 版：调用方 if (uid == null) ...（AuthInterceptor 的风格）
jshell> Long parseUid(String s) { try { return Long.parseLong(s); } catch (NumberFormatException e) { return null; } }
// 抛异常版：调用方 try/catch 或什么都不写让它一路炸到框架
jshell> long parseUid2(String s) { return Long.parseLong(s); }
```

null 版把"失败"混进正常返回值，调用方必须记得判空（忘了就是 NPE）；异常版失败路径显式，但调用方要理解异常流。**预期内的常见失败→null/Optional；真正的异常情况→抛**。

---

## 本节小结
- 类型树：Error 别接；RuntimeException 系=非受检（bug）；其他=受检（环境问题，编译器强迫处理）。
- 本项目业务异常全用非受检，靠 Spring 统一翻译成 HTTP 500/400。
- finally = "无论成败都收拾现场"；BookingService 的 Redis 锁是活教材（book 方法 47-76 行）。
- try-with-resources = Java 版 RAII，资格证是 AutoCloseable；close 异常被抑制不掩盖主异常。
- catch 三种姿势：转换语义（verify→null）、包装再抛（sha256→RuntimeException）、直接兜底翻译（框架）。

## 下一站

[Java速通-A05-lambda与Stream：像Python的转置生成器.md](Java速通-A05-lambda与Stream：像Python的转置生成器.md)——`map/filter/collect` 管道你用 Python 写过一百遍，Java 的 Stream 是它的静态类型版。实战：对 demo01 的任务列表按标题过滤。
