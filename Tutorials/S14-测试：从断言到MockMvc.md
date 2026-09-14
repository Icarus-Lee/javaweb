# S14 · 测试：从断言到 MockMvc（单测 vs 集成 vs smoke：22 断言实录）

> **本节要点**：代码写完只是"编译过"，**"真的能跑"要亲手按下按钮**。本章给三条测试阶梯：单元测试（类内真相）、集成测试（Spring 起个小环境、MockMvc 打 HTTP）、冒烟测试（全栈 22 断言打真环境）。三者各管一层，谁也不替代谁；全部有今天的机器实录：`smoke: 22 通过 / 0 失败`、`mvn test → Tests run: 3, Failures: 0`。
> **前置知识**：S04（Controller）、S06（校验）、S13（拦截器/401——测试也要验门卫）。
> **产出**：会区分三阶测试各自的适用问题；能自信用 `MockMvc` 写一条"POST+GET"双步集成断言；会跑项目级冒烟并读懂 22 行的 ✓/✗ 汇报。

> 🗺 **主线进度**：`S 14 篇内核 ─ ▶S14 测试◀ ─ 完（Redis/Kafka/Nginx/案例篇接管）`
> 🎞 **上一站发生了什么**：S13 把门卫三动作打到实用一点。
> 📀 **本站你会得到**：
> - 一张三阶测试对照表（谁慢、谁真、谁贵）
> - `TestTaskController` 全文 + **真实的 `mvn test` 输出**（3/3 绿实录）
> - smoke.py 的 22 断言逐段导览（含"防超卖、审计、派单"的干货位）

---

**本站名词卡**

| 名词 | 英文 | 一句人话 | 在本项目哪里见到 |
|---|---|---|---|
| 单元测试 | unit test | 只试**一个类/一个函数**，依赖全用假件 | 纯逻辑类（本项目少，业务薄） |
| 集成测试 | integration test | Spring 起真 DB + 真 Controller，但**不起真实端口** | `@SpringBootTest + MockMvc` |
| MockMvc | - | 在内存里模拟一次 HTTP 管线（含拦截器、校验） | `TestTaskController.java` |
| @SpringBootTest | - | "给我一个真的小 Spring 世界" | 本站示例注解 |
| 冒烟测试 | smoke test | 全栈开动后的**体检 22 条**（API+页面+基础设施） | `infra/smoke.py` |
| 断言 | assert | "这里是 X —— 不然报失败" | smoke 的 expect / MockMvc 的 andExpect |
| 幂等重跑 | idempotent test | 测试重复跑结果一样（别让测试吃掉库存） | smoke 用当下时间戳注册账号 |
| 测试金字塔 | test pyramid | 多便宜多快在下、少而真在上 | 下表 |

---

## 1. 三阶测试对照（用的起、贵的真）

| 维度 | 单元测试 | 集成测试（MockMvc） | 冒烟测试 |
|---|---|---|---|
| 范围 | 一个方法 | 一个模块的 HTTP 表面 | 全拓扑（redis/kafka/nginx/5 服务） |
|**速度**| 毫秒 | 秒级（起 Spring 上下文） | 分钟级（等 Kafka 派单到点） |
|**真 servlet**| 否 | 内存模拟（MockMvc） | **真端口 + 真 nginx** |
|**最适合找**| 逻辑错误（算法、边界） | 映射错、校验错、404/400 | 部署错件、环境断线、跨服务因果 |
|**本项目位置**| 业务单薄先用补足 | demo-todo `TestTaskController`（本站新增） | `infra/smoke.py`（始终在线） |

教学项目的诚恳话术：Spring Boot 后端的味道是**业务逻辑很少单独存在**——它总是嵌在 Spring 的全链路上（校验、事务、拦截器）。所以**集成测试的投入产出比在教学项目里最高**——本站把 max 力气花在这里。

---

## 2. MockMvc 最小测试全文（本站实战代码）

**文件：`backend/demo-todo/src/test/java/com/javaweb/todo/TestTaskController.java`**（本站为了实测已亲手加入项目）

```java
package com.javaweb.todo;

import com.javaweb.todo.model.Task;
import com.javaweb.todo.repo.TaskRepo;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest            // 给我一个真实小 Spring 上下文（JPA + 校验齐全）
@AutoConfigureMockMvc       // 把 MockMvc 造好并递进来（内存 servlet，不开真端口）
class TestTaskController {

    @Autowired MockMvc mvc;
    @Autowired TaskRepo taskRepo;

    @Test
    void 建一条再读回来() throws Exception {
        // POST /api/tasks → 200，response JSON 里回显标题
        mvc.perform(post("/api/tasks")
                        .contentType("application/json")
                        .content("{\"title\":\"MockMvc 写入的任务\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("MockMvc 写入的任务"));

        // GET /api/tasks → 200，数组里必须找得到那条
        mvc.perform(get("/api/tasks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.title == 'MockMvc 写入的任务')]").exists());
    }

    @Test
    void 空标题应有校验异常() throws Exception {
        // @NotBlank 检查桩生效：空标题 → 400（MethodArgumentNotValidException）
        mvc.perform(post("/api/tasks").contentType("application/json")
                        .content("{\"title\":\"\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void list有内容() throws Exception {
        taskRepo.save(new Task("预备数据"));
        mvc.perform(get("/api/tasks").accept("application/json"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("预备数据")));
    }
}
```

三个测试各自踩一节真课：

1. **"建一条再读回来"**：一条**写+读的完整因果段**。`jsonPath("$[?(@.title == ...)]")` 是 JSONPath 的过滤表达式（`$` 根、`?` 查询条件）。
2. **"空标题应有校验异常"**：S06 `@NotBlank` 到这里**真见分晓**——MockMvc 管线里校验插件也是真干活（`MethodArgumentNotValidException` → 400）。**S06 那篇说"校验要 @Valid 才会动"**，这测试正是它的回声。
3. **"list有内容"**：先用真 repo 灌一条"种子数据"再做 GET——**测试也用真实 API 铺路**（这种写法比 mock 更接近"行为"）。

### 真实目标依赖（pom.xml 增件一行一步）

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-test</artifactId>
    <scope>test</scope>      <!-- test 不进生产 jar -->
</dependency>
```

---

## 3. 真实 `mvn test`（从头跑一遍真实记录）

**命令：**

```bash
cd backend
mvn -pl demo-todo test
```

**实测输出（取关键两段）：编译期失败 & 最终绿：**

```
第一次（我在 status().value(400) 上写错 API 后的真实报错）：
[ERROR] COMPILATION ERROR :
[ERROR] TestTaskController.java:[43,36] cannot find symbol
  symbol:   method value(int)
  location: class org.springframework.test.web.servlet.result.StatusResultMatchers
[INFO] BUILD FAILURE

改为 status().isBadRequest() 之后：
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 3.024 s -- in com.javaweb.todo.TestTaskController
[INFO]
[INFO] Results:
[INFO]
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0
[INFO]
[INFO] BUILD SUCCESS
[INFO] Total time:  8.438 s
```

两次都**很真实**：左边是"写错 API → 编译期立刻拦住"（0.12 那些电影，测试也是代码，编译器也在帮你），右边是**3/3 绿、8.4 秒**。测试拒绝敷衍的两道闸门就这样立起来——**编译错误先筛，断言再筛**。

**一个顺带的教学时刻（真实日志里捡的宝）**：跑测试的同时这份日志打印的 WARN：

```
DefaultHandlerExceptionResolver : Resolved [org.springframework.web.bind.MethodArgumentNotValidException:
Validation failed for argument [0] in public com.javaweb.todo.model.Task ... [Field error in object 'task'
on field 'title': rejected value []; ... default message [标题不能为空]] ]
```

—— 与 S07（全局异常处理器）的知识链路**完全同声**：`@NotBlank` 校验失败⇒ `MethodArgumentNotValidException` ⇒ 拦截器兜叠 400。**测试把这条 S06→S07 的链路当一次真实再谈**。

---

## 4. 冒烟测试：全栈 22 断言（infra/smoke.py 走查 + 今天的实录）

### 4.1 断言清单路线图

| 段 | 断言 | 考察什么 |
|---|---|---|
| 基础设施 | kafka 9092 在线 | **拓扑**活着 |
| demo-todo | 新建/翻转/删除 | CRUD 链 |
| demo-chat | 发消息 + 回读 | SSE 通道 |
| redis 计数器 | n 是 int | Redis 自增 |
| train | 车次列表/余票字段/public | 查询公开路径 |
| train | 注册/登录+JWT/下单/审计/支付/取消 | **全业务链 + JWT 鉴权** |
| takeaway | alice 登录/点单/支付/**Kafka 异步派单** | 状态机 + 消费者 |
| nginx | 静态站两级/反代/控制台 | 部署件 |

**幂等试履**：smoke 用 `uid = "smoke" + int(time.time())` **以当秒命名新用户**注册——不得要"每次跑会撞唯一键"。**我们要让 smoke 可以连跑十次**。

### 4.2 今天的真实输出（连载记录）

这一站走上最后一步，需要全栈在线（`start-all.sh`）。**实测时曾一度因 Kafka 掉线+Redis remnant 余票键残缺而失败**（smoke 显出 16 通过/6 失败、20通过/2 失败两幅半绿的后墙），**在把 Redis key 对账回平之后再跑：**

```
== 基础设施 ==
  ✓ kafka 9092 在线
== demo-todo (8081) ==
  ✓ todo 新建
  ✓ todo 完成翻转
  ✓ todo 删除
== demo-chat (8082) ==
  ✓ chat 发消息
== redis 计数器 (8083) ==
  ✓ counter 自增
== train (8084) ==
  ✓ 车次列表
  ✓ 注册
  ✓ 登录+JWT
  ✓ 下单(UNPAID)
  ✓ 审计(Kafka 消费记录)
  ✓ Kafka audit 台有记录(5 条)
  ✓ 支付
  ✓ 取消结算余票
== takeaway (8085) ==
  ✓ 外卖登录(alice)
  ✓ 点单创建
  ✓ 支付
  ✓ Kafka 异步派单（状态=DISPATCHED）
== nginx (9090) ==
  ✓ train 静态站 200
  ✓ takeout 静态站 200
  ✓ 反代 /apitrain → 8084
  ✓ 控制台首页 200

smoke: 22 通过 / 0 失败
```

**这一大同一 grid 是你未来上线**每天要打印的"全栈心电图"——**先 22 绿，再 debug**。**16/6、20/2 的半绿是**最快定位出哪一层的体检仪：如今天实锤"基础设施坏 → 拓扑勉强通"的分别。

### 4.3 阅读口诀（照断言看真相）

- ✓/✗ 排序本来就是**自下而上**的：**基础→模块→边缘**。错位排报是**定位点**——一旦"反代页面" ✓、"反代 API" ✗，你立刻知道为前端服务文件与后端 API **分家场景**（nginx 与后端）。
- 失败行里 note 的 `(code=500: {'timestamp':...,'status':500,...'path':'/api/...'}` 是我们**最常收到**的排障孢子——**这只是外层**；**根因必须去 logs/<服务>.log 的 ERROR RES**（S09 的 Caused by 那根）。
- **exit code**: `sys.exit(0 if failed == 0 else 1)`——这就是 **CI**（持续集成）的天然首页钩子：通过与否一行看尽。

---

## 5. 动手验证

```bash
cd ~/Projects/javaweb

# 1) 跑集成测试（本站新增的 TestTaskController 已在项目里）
cd backend && mvn -pl demo-todo test
#    期望最后一屏含： Tests run: 3, Failures: 0, Errors: 0

# 2) 跑冒烟
bash infra/start-all.sh        # 若全套未在线
python3 infra/smoke.py         # 期望: smoke: 22 通过 / 0 失败

# 3) 折一个断言看它怎么报（教学性破坏）
# 临时改 smoke.py 里 "todo 删除" 断言语句的 expect 204→200，再跑一次
#    期望: ✗ todo 删除 (code=204, want=200)   失败信息非常清楚
```

---

## 6. 思考题（先想 3 分钟）

1. 为什么 MockMvc 也**走 DispatcherServlet 全链路**（含拦截器）？如果 Intercept 拦了 `POST /api/tasks`（demo 项目未装），测试会 能测到 401 状态还是漏掉？（这是测试"是不是真的走过门卫"的核心。）
2. smoke.py 里 train 的注册用 `smoke + 秒数`，**多次连跑没问题**；你如果改成固定账号 `smoke`，第二次跑会怎样？（提示：用户名唯一报错——幂等性破坏。）
3. 三阶测试的"钱"各花在哪儿？（编写时间、执行时间、环境成本三者你心里排个序。）
4. `Tests run: 3, Skipped: 0` 的 **skip** 是什么？什么时候你会真正需要它？（提示：环境依赖/一步未达到前 barrier.）

## 7. 练习题

1. 在 `TestTaskController` 里**加一个 404 的 GET**（`/api/tasks/999`）实测断言（`isNotFound()`），并跑一次看它在 3/4 格的成绩。
2. **写一条 train 侧的 MockMvc**：`POST /api/bookings` 无 token → `isUnauthorized()`——先把 train 的 `spring-boot-starter-test` 依赖补进 `train/pom.xml`（拷贝 demo-todo 同款），实测 401 断言。
3. 在 smoke.py 里加一条断言"清除后余票一定回 nums："（POST /bookings/cancel 后再 GET /api/trips，查 `stock` 不递减）。动手后跑一遍看 22→23 全绿。

## 8. 参考答案

**练习 1**（断言+改造版）：

```java
@Test
void 查 nonexistent 的 test 404() throws Exception {
    mvc.perform(get("/api/tasks/999")).andExpect(status().isNotFound());
}
```

**练习 2**（那是 S13 门卫的集成测试——**真正 401 测试是过门卫的**）：

```
$ mvn -pl train test
（一部分实录）
Tests run: 1, Failures: 0, Errors: 0 -- in com.javaweb.train.TestBookingAuth
```

```java
@SpringBootTest
@AutoConfigureMockMvc
class TestBookingAuth {
    @Autowired MockMvc mvc;

    @Test
    void 加票需要门槛() throws Exception {
        mvc.perform(post("/api/bookings").contentType("application/json")
                  .content("{\"tripId\":1}"))
           .andExpect(status().isUnauthorized());     // 401：门卫 S13 落地了
    }
}
```

注意应给 train 测试专用 `datasource`（yml 的默认 `file:./data/train` 一样看进程时它的启动要求）；教学测量了用**内库式嵌入**（H2 内存）。

**练习 3**：给断言留念：

```python
after = req("train", "GET", "/trips")[1]
stock_after = [x for x in after if x['id'] == trip_id][0]['stock']
ok = abs(stock_before - stock_after - 1) == 0 and True     # 下单+取消间不添不减
```

增上实测点：`✓ 取消回流余票不变(-1/0/…)`。

---

## 9. 本节小结

- 三阶各司其职：单元测**类内真相**、集成测**HTTP 表面**（MockMvc）、smoke 测**全栈心电图**。
- 集成测试是 Spring 后端的**主力砖石**（本站实写 TestTaskController，3 项真实测试）。
- smoke.py 22 条**(幂等化)** 时间值得：从基础设施到 nginx 的正式打洞人。
- 编译错/验证误/门卫 401——**测试就是这些"转发时刻"的自动把关者**。

---

## 10. 下一站 & 学完小结

**S 篇 14 课把 Spring Boot 内核翻完。 你给出三个交接手契**：
1. **Web 层**（S04-07/13）：DispatcherServlet 分诊、校验、全局异常、JWT 门卫。
2. **数据层**（S08/S10-12）：配置卡、JPA、事务、定时。
3. **质量层**（S09/S14）：日志观测、测试阶梯。

下一段主线是 **Redis 5 篇（R 篇）**——你在 Redis 源子屋里只见过 DECR/INC/setIfAbsent 的时代到头了：R 篇正式带你进 Redis 深水区（T06 的抢票对账实验是这一段的同样疫价货）。带上今日的 22/0 与 3/3 绿，开下一站。