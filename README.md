# javaweb —— SpringBoot + Vue3 + Redis + Kafka + Nginx 教学项目（火车购票 & 外卖平台）

面向**已有 C/C++/Python 基础的学习者**的 Java 后端教学项目：3 个迷你 demo 过渡 + 2 个完整案例（火车站购票、外卖平台），五个框架**有机结合**（每篇教程都指向真实服务代码），65 篇中文教程（思考题+练习题+完整参考答案）。

## 一键运行

```bash
# 0) 环境（本机已装好）：JDK21 + Maven + Redis + Nginx（pacman），tools/ 下有 Kafka 4.3.1
cd ~/Projects/javaweb
bash infra/build.sh       # 编译 5 个后端 jar + 2 个前端 dist
bash infra/start-all.sh   # 一键起 Redis/Kafka/5 个后端/Nginx 反代
bash infra/smoke.sh       # 22 项断言全绿（API+页面）
bash infra/stop-all.sh    # 全停
```

| 入口 | 地址 |
|---|---|
| Nginx 汇总控制台 | http://127.0.0.1:9090 |
| 火车购票小站（Vue3 静态站） | http://127.0.0.1:9090/train-ui/ |
| 外卖小站 | http://127.0.0.1:9090/takeout-ui/ |
| train API | http://127.0.0.1:8084/api/**（nginx 反代 /apitrain） |
| takeaway API | http://127.0.0.1:8085/api/**（/apitakeout） |
| demo-todo / chat / counter | 8081 / 8082 / 8083 |

演示账号：train 侧注册即用；takeaway 种子账号 `alice/123456`（顾客）与 `rider9/123456`（骑手）。

## 后端模块（Maven reactor，Spring Boot 3.5.4 / Java 21 / H2）

| 模块 | 端口 | 教学目标 |
|---|---|---|
| demo-todo | 8081 | 第一个 Spring Boot：Controller/JPA/CRUD/校验 |
| demo-chat | 8082 | SSE 推模型（服务端主动说话） |
| demo-counter | 8083 | Redis 计数器 + 限量秒杀雏形（原子 DECR） |
| train | 8084 | ★ 购票：JWT 登录拦截器、Redis 席位预扣+分布式锁防超卖、座位序号发放、Kafka 下单事件→审计台、@Scheduled 超时关单 |
| takeaway | 8085 | ★ 外卖：点单→支付→**Kafka 异步派单骑手（AAA 状态机）**→送达；菜单 cache-aside 缓存+防穿透"空哨兵"；双角色 JWT |

数据库：H2 文件库 `data/*.mv.db`（首次启动自动建表+种子数据；`ddl-auto: update`）。
Kafka topics：`train-order-events`、`takeout-order-events`（单一 Broker KRaft 模式）。

## 前端

`frontend/train-ui` 与 `frontend/takeout-ui`：Vue3 + Vite + Pinia + Axios。
- 开发模式：`npm run dev`（vite 代理 /api → 后端）
- 生产模式：`npm run build` 后由 **Nginx 静态托管**（start-all.sh 自动构建缺失的 dist）

## Nginx（infra/nginx/nginx.conf）

- 9090 汇总站：`/train-ui/`、`/takeout-ui/` 静态 dist
- `/apitrain/*` → 127.0.0.1:8084/api/*，`/apitakeout/*` → 127.0.0.1:8085/api/*
- gzip 开启（HTML/JS/JSON）；教程 N01-N04 逐条拆解配置

## 测试与对账

- `infra/smoke.py`：22 条断言（Java 服务健康、JWT 下发、下单、Kafka 消费端异步生效确认、反代页面）
- 教程 T06/W05 里有防超卖与缓存穿透的**对账实验**（并发抢票 vs 库存余量的真实数字）

## 教程（Tutorials/ 65 篇）

零起点 4 → Java 速通（A01-A10，从 C++/Python 视角）→ JS/Vue 速通（B01-B06）→ Spring Boot 内核 14 篇（S01-S14，IoC/DI/MVC/JPA/事务/JWT/测试）→ Redis 5（含抢票锁）→ Kafka 6（Kraft 起停到事务消息）→ Nginx 4 → 火车票 6 → 外卖 7 → 收官 3。
每篇结构：制片厂式主线三件套（本站你走在大厅哪一格）→ 本站名词卡 → 概念人话 → 真实代码走查（文件+行号）→ 动手验证（可执行或 curl 一行命令）→ 思考题+练习题 → **完整参考答案** → 小结 → 下一站。

详见 `Tutorials/README.md`。