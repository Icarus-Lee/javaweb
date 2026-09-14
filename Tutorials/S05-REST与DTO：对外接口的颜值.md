# S05 REST 与 DTO：对外接口的颜值

> 主线：本站你走到"客服工作台"。接得住（S04）还不够，还必须**答得体面**：状态码用对了，客户端不用读正文就懂；DTO 分清了，出进数据形状才不被数据库裸奔绑架。
>
> 学完本课你能：说出本项目真实用到的 9 个状态码的区别；亲手把"删除成功→204"的语义玩转；看懂 record 的一行 DTO 与"输入输出分离"的真实取舍。

## 本站名词卡

| 名词 | 一句话人话 |
|---|---|
| REST | 用 HTTP 既有的语义（方法+名词+状态码）组织 API，不发明新协议 |
| 2xx / 4xx / 5xx | 成功 / 客户端的问题 / 服务端的问题 |
| `ResponseEntity` | 显式掌管"状态码 + 响应头 + 响应体"的返回包装 |
| DTO | 专为接口出入参定义的数据传输对象（区别于数据库实体） |
| 输入 DTO / 输出 DTO | 分离"进来什么"与"出去什么" |
| `record` | Java 不可变小数据类，一行写完（构造 getter 一键齐） |

## 一、状态码语义速查（本项目真实出现过的全部）

| 码 | 人话 | 本项目真实实例 |
|---|---|---|
| 200 OK | 成功且有响应体 | `GET /api/tasks`、`POST /api/tasks`、`PUT /api/tasks/{id}` |
| 201 Created | 创建成功（严格 REST 配 `Location` 头） | 本项目新建用 200（取舍见下） |
| 204 No Content | 成功且**永远没有响应体** | `DELETE /api/tasks/{id}` 成功路径 |
| 400 Bad Request | 客户端请求本身有问题 | `@Valid` 校验失败、坏 JSON、`IllegalArgumentException` |
| 401 Unauthorized | 未登录/token 失效 | train 不带 token 下单 |
| 404 Not Found | 资源不存在 | `DELETE /api/tasks/99999`、未知路径 |
| 405 Method Not Allowed | 路径在、方法不对 | 对 `/api/tasks` 集合发 DELETE |
| 409 Conflict（S07 后启用） | 资源当前状态冲突 | `IllegalStateException`（现状是500 → S07 收编） |
| 415 Unsupported Media Type | Content-Type 喂错 | 表单格式打到 JSON 接口 |
| 500 Internal Server Error | 服务端异常（未分类） | 下单不存在的车次 |

记忆口诀：**2 成功、4 你（客户端）的锅、5 我（服务端）的锅。** 有一句工程上的要义：

> **状态码是接口说的第一句话。** 监控告警、网关重试、浏览器缓存都"先看第一句话"。把它用对，正文只是补充语境；把 500 当垃圾桶，整个系统都会胡说。

## 二、ResponseEntity：显式掌管状态码的两块样板

### 样板 1：demo-todo 的 delete——204 与 404 的分岔

```java
// backend/demo-todo/.../TaskController.java:42-47
@DeleteMapping("/{id}")
public ResponseEntity<Void> delete(@PathVariable Long id) {
    if (!repo.existsById(id)) return ResponseEntity.notFound().build();  // 404，无体
    repo.deleteById(id);
    return ResponseEntity.noContent().build();                           // 204，无体
}
```

三个读法：
- 泛型 `ResponseEntity<Void>` 直接宣告：**这个接口从设计上没有响应体**（删除的自然语义如此）；
- `notFound().build()` 内功来自 `ResponseEntity.status(404)` 的语义化工厂方法，`noContent().build()` 则是 `.status(204)` ——**看工厂名就懂语义**；
- "先 existence-check 再删"是一次显式分岔——一朵接口同时有两个有意义的终态。实测有史实：

```bash
# 创建一条以供删除
TID=$(curl -s -X POST http://127.0.0.1:8081/api/tasks -H 'Content-Type: application/json' \
     -d '{"title":"S05 deletion"}' | python3 -c 'import json,sys;print(json.load(sys.stdin)["id"])')

curl -s -o /dev/null -w 'HTTP=%{http_code}\n' -X DELETE http://127.0.0.1:8081/api/tasks/$TID
# HTTP=204

# 删第二次（已不在） → 404
curl -s -o /dev/null -w 'HTTP=%{http_code}\n' -X DELETE http://127.0.0.1:8081/api/tasks/$TID
# HTTP=404
```

### 样板 2：toggle——200 与 404 的同型分岔

```java
// backend/demo-todo/.../TaskController.java:32-40（节选）
@PutMapping("/{id}")
public ResponseEntity<Task> toggle(@PathVariable Long id) {
    return repo.findById(id)
               .map(t -> {
                   t.done = !t.done;
                   return ResponseEntity.ok(repo.save(t));    // 200 + 正文（改后的实体）
               })
               .orElse(ResponseEntity.notFound().build());    // 404 无体
}
```

`Optional.map(...).orElse(...)` 把"找没找到"分叉编译成两条 ResponseEntity 路径——**状态码由数据来定**，比 if/else 加手工 set 状态更整齐。

### 201 与 `Location`：要不要做严格 REST？

严格 REST 的 `POST /tasks` 成功应回：

```text
HTTP/1.1 201
Location: /api/tasks/{id}
```

**本项目的取舍**是**取消 201、直接 200 + 响应体含创建出的完整实体**：

```java
public Task create(@Valid @RequestBody Task task) {
    ...
    return repo.save(task);    // 200 + {"id":8,"title":"买牛奶","done":false}
}
```

理由：**前端从响应正文里直接得到 id**，不用再发一次 `GET` 。两种都合法；约定派（前端）更取 200+实体派，**关键是团队定一种，项目内一致**。（严格 201 写法见本章练习题答案。）

## 三、DTO：输入输出分离（裸实体 vs DTO 的对拍）

### 反例（demo-todo 教学首章的写法）：实体直通

```java
public Task create(@Valid @RequestBody Task task) {   // 进出都是数据库实体 Task
    ...
}
```

隐患三连（真实想想；不是危言耸听）：

1. **输出泄漏**：若 `Task` 上有 `ownerEmail`、`internalNote` 等，全跟着序列化出去了；将来加 `passwordHash` 字段，一次全裸奔；
2. **输入模糊**：客户端猜"我也传 `done:true` 行不行""传 `id:99` 会怎样"（项目里真实防御了：`task.id = null;` 强制重置）；
3. **重构恐惧**：数据库列名一改，全部接口形状立刻变——**前·后端合同依赖实现细节**。

### 正解（train 侧）：record 一行模拟出入两端

输入 DTO：

```java
// backend/train/.../BookingController.java:27-28
public record BookReq(Long tripId) {}
public record OrderOnlyReq(String orderNo) {}
```

使用处：

```java
public Booking book(@RequestBody BookReq req, HttpServletRequest http) { ... }
```

输出 DTO（业务层的返回结果——**只给前端真正需要的字段**）：

```java
// backend/train/.../BookingService.java:33
public record BookResult(String orderNo, int seatNo) {}
```

（本项目中 `book()` 直接返回 `Booking` 实体，`BookResult` 定义在侧营做 S06 之后的增量改造——这是个**可做的练习**：把 `book()` 内部最后改为 `return new BookResult(b.orderNo, b.seatNo);`，并把 `BookingController.book` 返回类型更新。）

record 一行四工具：**字段 final + 全参构造 + `orderNo()`/`seatNo()` 访问器 + equals/hashCode/toString**——不可变（不能在中间被器械乱改），打包输出时字段一清二白。

### 对照表（裸实体 vs DTO）

| 维度 | 裸实体直通 | DTO 分离 |
|---|---|---|
| 输出可控 | 一变实体全泄漏 | 只给必要字段 |
| 输入可控 | 客户端可"发明字段" | 只有声明的字段（能传其他也会被忽略） |
| 数据库改动 | 直接撕裂前端合同 | DTO 可先改外面、内部分阶段改 |
| 维护成本 | 一开始零 | 每接口多两三个小类（record 减轻） |

教学取舍大白话：**demo 直接用实体求快（快速入门）；真实服务立刻上 DTO**。本项目两派兼收——train/takeaway 走 DTO，demo-todo 走实体——让读者真踩两次盘再对上。

## 四、动手验证：一条一组状态码巡检（`-w '%{http_code}'`）

先起 warrants，然后一条一条打状态码（**只看第一句话**）：

```bash
cd ~/Projects/javaweb && bash infra/start-all.sh

# ① 200：列表
curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:8081/api/tasks
# 200

# ② 200：创建（若要出 201 见练习题）
curl -s -o /dev/null -w '%{http_code}\n' -X POST http://127.0.0.1:8081/api/tasks \
     -H 'Content-Type: application/json' -d '{"title":"S05巡检"}'
# 200

# ③ 404：未知路径
curl -s -o /dev/null -w '%{http_code}\n' -m 5 http://127.0.0.1:8081/api/nope
# 404

# ④ 400：@Valid 空标题
curl -s -o /dev/null -w '%{http_code}\n' -m 5 -X POST http://127.0.0.1:8081/api/tasks \
     -H 'Content-Type: application/json' -d '{"title":"  "}'
# 400

# ⑤ 401：train 未登录下单
curl -s -o /dev/null -w '%{http_code}\n' -m 5 -X POST http://127.0.0.1:8084/api/bookings \
     -H 'Content-Type: application/json' -d '{"tripId":1}'
# 401

# ⑥ 204：删除（id 以上一步为基础，实测本机是 11）
curl -s -o /dev/null -w '%{http_code}\n' -X DELETE http://127.0.0.1:8081/api/tasks/11
# 204

# ⑦ 405：对集合 DELETE（此路径无 @DeleteMapping）
curl -s -o /dev/null -w '%{http_code}\n' -X DELETE http://127.0.0.1:8081/api/tasks
# 405

# ⑧ 415：Content-Type 不对
curl -s -o /dev/null -w '%{http_code}\n' -X POST http://127.0.0.1:8081/api/tasks -d 'title=abc'
# 415
```

500 一条需登录流程（参考 S04 步骤拿 token）：

```bash
TOK=...   # 登录所得
curl -s -o /dev/null -w '%{http_code}\n' -m 5 -X POST http://127.0.0.1:8084/api/bookings \
     -H "Authorization: Bearer $TOK" -H 'Content-Type: application/json' -d '{"tripId":999}'
# 500    ← 不存在的车次，业务异常扑向兜底
```

再瞄一眼这条 500 的**默认错误 JSON**（Spring Boot 自动端出）：

```bash
curl -s -X POST http://127.0.0.1:8084/api/bookings -H "Authorization: Bearer $TOK" \
     -H 'Content-Type: application/json' -d '{"tripId":999}'
# {"timestamp":"2026-09-14T09:55:17.770+00:00","status":500,
#  "error":"Internal Server Error","path":"/api/bookings"}
```

**注意：这是框架性的错误格式**，不含你的业务话术（为什么下单失败？"车次不存在"其实话还没到靠这出口传出来）。谁把它收编成稳定的错误合同（如 `{"code","msg","status","path","ts"}`）？**下一课 S07 拿方案**。

## 思考题
<!-- 追加拆解：一句话看穿 record 的"额外福利" -->

在进入思考题之前，补一段 record 的"额外福利"顺带验收：record 天生**实现了 `equals`/`hashCode`**。这意味着 `BookReq`/`BookResult` 一旦放进集合（Set/Map key）做单测断言，**值相等即可达**——不用像普通类那样逐字段断言或手写比较器。这也是本项目选 record 而不是普通 class 当 DTO 的第三理由（前两条：不可变、代码短）。

- 适合场景：单测里 `assertEquals(new BookResult(1L, 5), res)` 一行即可（record 辅助）。
- 不适合场景：把 record 当"可变承载物"，又想中途改字段——那就要回到 class 或换 builder 风格，**record 只肯做"形状"，不肯做"容器"**。

1. `ResponseEntity.notFound().build()` 与"返回 null"（ResponseEntity 不包）有什么机制差别？哪些注解/自动机制会因你的返回类型不同而各就各位？
2. "POST 创建回 201 vs 200"哪个更 REST？为什么不严格 REST 也有"硬道理"？（提示：前端对 `Location` 头的使用率、与正文的便利）
3. `record BookResult(String orderNo, int seatNo)` 是否也该附上"字段隐藏"的纪律？若需要 location 内数据（座位号 + 订单号）够吗？，如果前端还需要 `status` 字段，record 改起来是不是零成本（加上一个字段），还是说"—个 DTO is 不可变"的代价变成了"每进程就再建一个 record"？

## 练习题

<!-- 加餐：把"response 三层"向社会礼仪化——客户端能感知的三层语义：状态码→错误码→人话 -->

**加餐一段：三层语义的分工表格**（把本课的状态码与 S07 的错误码串起来的过渡桥）。

| 层 | 载体 | 读者 | 典型取值 |
|---|---|---|---|
| HTTP 层 | 状态码 | 监控、网关、浏览器缓存 | 200/204/400/401/404/409/500 |
| code 层 | JSON 里的 `code` 字段 | 前端分支逻辑 | `BAD_INPUT`/`VALIDATION_FAIL`/`STATE_CONFLICT`/`INTERNAL` |
| msg 层 | JSON 里的 `msg` 字段 | 直接展示给用户 | "标题不能为空"/"已售罄"/"服务器开小差" |

三层各自演化互不绊脚：监控换报警阈值（动 HTTP 层）、前端改分支（动 code 层）、文案改口吻（动 msg 层）——**接口的三"颜值"层各整各的，谁来接锅都清楚**。

1. 把 demo-todo 的 `create` 改成**严格 REST**：`ResponseEntity.created(URI.create("/api/tasks/" + saved.id)).body(saved)`；并重测响应。
2. 它的 `-i` 完整版（打印头）验证 Location：

```bash
curl -si -X POST http://127.0.0.1:8081/api/tasks -H 'Content-Type: application/json' \
     -d '{"title":"REST规范"}'
```

### 参考答案

1. 改法：

```java
@PostMapping
public ResponseEntity<Task> create(@Valid @RequestBody Task task) {
    task.id = null;
    Task saved = repo.save(task);
    return ResponseEntity
            .created(URI.create("/api/tasks/" + saved.id))   // 201 + Location
            .body(saved);                                    // + 完整实体正文
}
```

2. 真实输出（id 随库里累积变）：

```text
HTTP/1.1 201
Location: /api/tasks/12
Content-Type: application/json

{"id":12,"title":"REST规范","done":false}
```

## 本节小结
- 状态码是第一句话；**204/401/404/405/415/400/500** 每一条都能找到本项目 curl 实测凭证。
- `ResponseEntity` 三件套显式说：**状态码 + 头 + 体**，`created()` 是严格 REST 的标准句型。
- DTO 是接口的官定形状：输入 `BookReq`、输出 `BookResult`；`record` 是实现这一形状的最省字节写法。
- demo 用裸实体、真实服务上 DTO——本项目两派并存供你对照，前者的终点是"教学玻基板"，后者的终点是"接口合同"；后三课都走 DTO。
- 默认错误 JSON 现在还是"框架味"，**S07 收编它**。

**下一站：S06 参数校验：Validation 说明。**
