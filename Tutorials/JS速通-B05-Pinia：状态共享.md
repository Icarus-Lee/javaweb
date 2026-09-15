# JS速通 B05 · Pinia：状态共享——前端的"内存数据库"

> **本站你走在大厅哪一格**：B04 的组件能"props 进、emits 出"了，但**跨页跨组件**的共享数据
> （登录态、已拉取的菜单缓存）用 props 一层层下传，层级一深就变"意见箱转报"。
> 本篇上 **Pinia**（Vue 官方状态库）——store/state/getters/action 三件套 + localStorage 持久化。
> 一句话先入脑：**Pinia 是前端的"内存数据库"**——被所有储组件订阅，但**数据以响应式的方式存于 store，不持久就不隔夜**（隔夜要走 localStorage）。

## 名词卡

| 名词 | 人话 |
|---|---|
| store（仓库） | Pinia 的一个"模块化数据库"：state（数据）+ getters（派生）+ actions（修改）|
| Pinia | Vue3 官方状态库：每个 store 用 `defineStore` 定义 |
| state | store 的原始数据（必须以**函数返回对象**的形式写） |
| getters | 派生值/计算字段（参数自动接受 state）；≈ B03 讲的 computed 的全局版 |
| actions | 改 state 的方法（可 async）；**数据修改的唯一负责者** |
| `storeToRefs` | 从 store 解构保住响应性的专用宏（B03"解构截断"的正解） |
| localStorage | 浏览器 5MB 级"隔夜柜"：字符串 kv，无过期；登录态就靠它 |
| 内存数据库 | 不是 SQL 数据库的比喻：**刷新即失**（volatile），用重新异步拉取来重建 |

## 零点五、从 B04 到 B05 的过渡判定

升级信号清单（出现任一就考虑 store）：

- 两个不相邻的组件读/改同一份数据（如"顶栏与订单面板都要判断 token"）；
- 事件链超过两层（子 → 父 → 祖父）才通的值；
- 刷新恢复逻辑散落在**多个**组件淮地。

本站 train-ui 目前只 1 个组件，散 ref 也能活——**是"还没到，不是永远不"**。

## 问题出发

今早真实的联合调试：外卖前端把登录彻底做好后，测试同学输入**错误密码**，
页面上弹出的是一句英文 "Request failed with status code 500"——用户一脸懵。
这现象跨了**三层**：浏览器怎么发（本篇不管）、服务端怎么答（默认 JSON、语义不对——S07 收编）、
以及**前端怎么把响应变成一条人话 msg**（本篇要讲的 axios 拦截器与 store 分工）。
本篇先把"状态共享"讲清，实录里再拉**完整一次登录失败**的三层解梗。
**跑起来看的姿势**：`curl -s -X POST :8085/api/auth/login -d '{"username":"alice","password":"wrong"}' -H 'Content-Type: application/json'`。

## 一、概念人话：为什么不是"props 串到地老天荒"

B04 的 TripCard 收一个 `:trip` prop，一层 props 传很优雅。但想象这些场景：

```
登录态 token：
  App.vue（顶栏）    要知道"是否登录"
  BookingPanel       要知道"能否下单"
  TicketDialog       要知道"下单人的名字"
→ 若用 props：顶栏知道数据，一路下传给两层孙组件——每层都要"快递员"
```

**Pinia 的答案**：把数据从"局部变量"升级为"全员可达的**单例仓库**"——谁需要谁进来直取。

```
┌ store：userStore（模块化仓库，全局单例）┐
│  state:   { token: '', username: '' }  ← 响应式数据
│  getters: isLoggedIn（token 非空）      ← 派生
│  actions: login(name,pw) / logout      ← 修改
└─────────────────────────────────────────┘
   ↑↑↑ 任何组件 import 后直接读写/订阅 ↑↑↑
```

## 二、真实代码走查：本站前端为什么**还没用** Pinia（前面的取舍）

一个事实要坦白：本站 train-ui/takeout-ui 的 `package.json` 里**都声明了 pinia 依赖、
`main.js` 里也都挂了 `createPinia()`**（frontend/train-ui/src/main.js:3-5）：

```js
import { createApp } from 'vue'
import { createPinia } from 'pinia'
import App from './App.vue'

createApp(App).use(createPinia()).mount('#app')
```

但 App.vue 里**实际不用 store**，登录态直接散落在 8 个 `ref` 里（App.vue:5-12）：

```js
const token = ref(localStorage.getItem('train_token') || '')
```

两个原因：

1. 本站页面就**一个组件**（App.vue 一层），不存在"跨页跨组件"，props/store 都未到出场深度。
2. **教学定位**：B05 之后的章节（后续 S 系后端篇）也不重前端大型化——Pinia 属于"会定义、能写、懂何时拆"的预备弹药即可。

所以本篇是**教程向你展示"如果项目长成 5 层组件树后怎么拆"**——对照版本才有价值。

## 三、动手验证：给 train-ui 写一个 userStore（15 分钟）

### 步骤 1：定义 store——frontend/train-ui/src/stores/user.js

```js
import { defineStore } from 'pinia'

export const useUserStore = defineStore('user', {
  // state：仓库的原始数据（必须函数返回——像"每次实例都拿新的"？不，单例只new一次）
  state: () => ({
    token: localStorage.getItem('train_token') || '',   // 兜底：刷页后仍记得
    username: localStorage.getItem('train_user') || '',
  }),

  // getters：派生值。函数形式，自动拿上面的 state
  getters: {
    isLoggedIn: (s) => !!s.token,
    welcome: (s) => (s.token ? `欢迎，${s.username}` : '未登录'),
  },

  // actions：改数据的入口（唯一合规通道）
  actions: {
    async login(username, password) {
      const { token, username: name } = await api.post('/auth/login', { username, password })
      this.token = token                     // ← this 上即 state，直接改
      this.username = name
      localStorage.setItem('train_token', token)          // 隔夜柜
      localStorage.setItem('train_user', name)
    },
    logout() {
      this.token = ''; this.username = ''
      localStorage.removeItem('train_token'); localStorage.removeItem('train_user')
    },
  },
})
```

说明两点：
- `defineStore('user', {...})` 的 **'user' 是仓库 id**（要求全局唯一，DevTools 靠它分门别户）。
- state 里初始化**顺手从 localStorage 恢复**——这就是"无需插件的最朴素持久化"。

### 步骤 2：在任何组件里用

```vue
<script setup>
import { useUserStore } from '../stores/user'
import { storeToRefs } from 'pinia'

const user = useUserStore()                    // 拿单例仓库
const { token, username } = storeToRefs(user)   // 解构保响应性（B03 解构截断的正解）

async function onLogin() {
  try {
    await user.login(user.username, '密码')
  } catch (e) { alert(e) }
}
</script>

<template>
  <input v-model="user.username" />     <!-- 或用 storeToRefs 之后的 username -->
  <span v-if="user.isLoggedIn">{{ user.welcome }}</span><!-- getter 当普通属性用-->
  <button @click="user.logout()">退出</button>
</template>
```

**对照逻辑**（读 App.vue 的 8 个散 ref 版同一个登录功能的差异）：

| 关注点 | 散 ref（现状） | Pinia store |
|---|---|---|
| 登录态落库 | 5 行手写 setItem（App.vue:57-58） | action 里一处统一 |
| 登出 | 散在各组件各自清理 | `store.logout()` 一键 |
| 派生 | `v-if="token"` 大量散布 | `user.isLoggedIn` 语义发表 |
| 跨组件 | 必须 props/事件传递 | 任意组件 `useUserStore()` |

### 步骤 3：持久化的两层含义（务必分清）

| 层 | 工具 | 寿命 | 说明 |
|---|---|---|---|
| **内存**（store 的 state） | Pinia | 刷新即**失** | 响应式的单价，页面级共享 |
| **隔夜**（localStorage） | 5MB 字符串 kv | 关浏览器也**在** | 刷新/重启后 state 用它"回填" |

**这就是"前端内存数据库"的完整语义**：Pinia 是会话中的主存储，**localStorage 只是用来"隔夜恢复"的冰箱**。
（更完整的两向对称——写 action 时同步落袋、初始化时反向恢复——你已经在本篇步骤 1 里看到样板。）

**Browser 现场验证**：`devtools → Application → Local Storage → localhost:5180`，
登录后能看到 `train_token / train_user` 两个键——刷新页面（F5）**列表立刻重建**，无需再登录。
然后 `localStorage.removeItem('train_token')` 再刷新——登录态消失："冰箱清空，隔夜失效"。

## 三点六、工程实录：踩坑与修复（真机实测）

### 实录 1：一次登录失败，三层各自"说了一句什么话"（三层截图逐层生成）

**问题**：错密码登录，页面显示 "Request failed with status code 500"，用户没法从这句话获得行动。
**现场复现**（服务端，2026-09-15 实录）：

```bash
curl -s -w '\nHTTP=%{http_code}\n' -X POST http://127.0.0.1:8085/api/auth/login \
     -H 'Content-Type: application/json' -d '{"username":"alice","password":"wrong"}'
```

真实输出：

```json
{"timestamp":"2026-09-15T01:55:36.260+00:00","status":500,"error":"Internal Server Error","path":"/api/auth/login"}
HTTP=500
```

第一个问题当场暴露：**端口发送的错误 JSON 里根本没 message 字段**（框架格式，不是业务错）→ S07 的收编对象。
那前端怎么落成那句英文？看真码（frontend/takeout-ui/src/api.js:14-17）：

```js
api.interceptors.response.use(
  r => r.data,
  err => Promise.reject(err.response?.data?.message || err.message)
)
```

`err.response.data.message` 是 undefined（**框架 JSON 无 message**）→ 退到 `err.message`
＝ axios 自己描述的 "Request failed with status code 500"。所以页面上 `msg.value = String(e)`
（takeout-ui App.vue:47 的 catch）就真的把这行英文 toast 出去了。

**修复路径**（真改两处）：
- 服务端：S07 的 GlobalExceptionHandler 把"用户名或密码不匹配"翻译成 `{"code":"AUTH_FAIL","msg":"用户名或密码错误"}`；
- 前端：拦截器里的 `err.response?.data?.message` **终于等到真 message**，零改动即显示人话——
  这正是"前端 promise 链写好后，后端一改，前端立刻变好"的公共合同威力。

### 实录 2：前端"登出"是"自己擦桌"——服务端 token 仍然有效（真机证实）

**问题**：测试同学迷茫：前端"退出登录"只是把 localStorage 擦了，那这枚 JWT 到底还认不认？
**现场复现**：先用合法 token 取我的订单一次（200），再**在前端 Application 面板删掉** `takeout_token`
等价于"不带了"——分别打两枪：

```bash
# ① 带着旧 JWT（前端已"登出"）——服务器仍然认账：
TOK=eyJhbGciOiJIu...      # 登录所得
curl -s -o /dev/null -w '%{http_code}\n' -m 5 \
     http://127.0.0.1:8084/api/bookings/mine -H "Authorization: Bearer $TOK"
# 200               ← JWT 无状态，前端删除不影响服务端效力

# ② 完全不带 token：
curl -s -o /dev/null -w '%{http_code}\n' -m 5 http://127.0.0.1:8085/api/orders/mine
# 401               ← AuthInterceptor 拿不到 Bearer，直接 401
```

真实行为（本机实录）：**① 200，② 401**。
**读法**：logout 中的 `localStorage.removeItem(...)` 只清前端"隔夜柜"——服务端 token除在 120 分钟 TTL 内仍然"合法"。
**这是"前端状态≠权限"的最硬证据**（本文表 4 的 Turnto：Pinia/localStorage 的可信度=0 班）。
真实工程里的补救：登出要**服务端参与**（Redis 黑名单 JWT 或超短 TTL），这正是 W04/S13 的课题。

## 四、Pinia vs 后端"session/Redis"的对照（别混淆）

| 视角 | Pinia store | 服务端 session/Redis |
|---|---|---|
| 存哪 | **用户浏览器内存**（每个标签页各存一份，删标签页即失） | **服务器进程内存/Redis**（全用户共享真相） |
| 可信度 | 用户**可以改**（DevTools 直接删改 localStorage，token 可被盗伪造） | 服务端才可信（JWT 校验仍须做） |
| 一致性 | 各端各自快照，**天然可能不同步** | 单一事实来源 |
| 修数据 | action（响应式通知所有订阅组件） | 本站是 API 操作 service / Redis 原子 |

**一条安全铁律**：前端 localStorage 只能存"**方便回填**"的数据（token、偏好），
**任何时候不作为权限的裁决者**——每个 API 请求照旧要带 token 过 `AuthInterceptor`（backend/train/.../AuthInterceptor.java:14-30），
服务端按 Redis/DB 校验才是真闸。

## 思考题

1. Pinia store 与 Vue3 的 `provide/inject`（组件树内依赖注入）各适合什么场景？三个判断维度（数据来源、生命周期、作用范围）里，跨页登录态为什么选 Pinia？
2. state 初始化直接 `localStorage.getItem(...)` 与"初始化空值，onMounted 时再回填"各有什么优点/隐患？（单点 vs 副作用）
3. takeout-ui 的 token、user、role、`msg` 若搬到 Pinia，哪些适合进 state、哪些**不该**进（提示：msg 是临时 UI 反馈——"会话瞬时"者不入库，除非要跨组件共享）？

## 练习题

1. 独立写出 `stores/booking.js`：存 `myOrders` 列表，getter `unpaidCount`（未支付条数），action `refresh()`（拉 `/bookings/mine`），并挂进 App.vue 的"我的订单"区块替代现有 `myOrders` ref。
2. 把 userStore 的 `login` 增强"失败时 throw 发布 msg"（App.vue 现状用 catch 里 `msg.value = String(e)`）——改成 store action 里记账到 `state.lastError`，页面订阅渲染，验证刷新后 lastError **不**残留（不求持久）。
3. 用 DevTools 的 Application 面板手动改 `train_user` 值，再刷新页面——看见"欢迎，xxx"已是新名。写 2 句话解释为什么前端"数据看起来不假"，却**完全不构成** 权限凭证。

## 参考答案

**练 1**：

```js
// frontend/train-ui/src/stores/booking.js
import { defineStore } from 'pinia'
import { api } from '../api'

export const useBookingStore = defineStore('booking', {
  state: () => ({ myOrders: [] }),
  getters: {
    unpaidCount: (s) => s.myOrders.filter(o => o.status === 'UNPAID').length,
  },
  actions: {
    async refresh() { this.myOrders = await api.get('/bookings/mine') },
  },
})
```

组件里：

```vue
<script setup>
import { useBookingStore } from '../stores/booking'
const booking = useBookingStore()
</script>
<template>
  <h3>我的订单（未支付 {{ booking.unpaidCount }}）</h3>
  <ul>
    <li v-for="o in booking.myOrders" :key="o.id">{{ o.orderNo.slice(0,8) }}｜{{ o.status }}</li>
  </ul>
</template>
```

**练 2**：

```js
// store 里加
state: () => ({ ..., lastError: '' }),
actions: {
  async login(u, p) {
    try { ... } catch (e) {
      this.lastError = String(e); throw e
    }
  },
  clearError() { this.lastError = '' },
}
// 组件：<p v-if="user.lastError" style="color:tomato">{{ user.lastError }}</p>
```

刷新不残留的原因：`lastError` 只在 state（内存），**没有进 localStorage** ——刷新即空，正合"会话瞬时数据不入库（隔夜柜）"。

**练 3**（两句版）：localStorage 是浏览器**本地**的隔夜柜，用户可随时改/删它——它绝无权限地位。
真正的裁决在服务端：AuthInterceptor 拿到的 JWT 在每次请求时经 JwtUtil.verify（HS256 签名校验）重查——
而 token 本身由服务端签发，**前端只是代为保管搬运**。

## 本节小结
- Pinia = defineStore（id + state/getters/actions）、`storeToRefs` 保响应性、**跨组件内存库**。
- getters 是"带缓存的派生"；actions 是"修改数据的唯一通道"；state 加 localStorage 回置=最朴素持久化。
- **内存 vs 隔夜**两条线：Pinia state 刷新即失；localStorage 关浏览器犹存—— Refresh/重启两者要用对。
- 前端状态**不是权限**：localStorage 可被用户改写；真正的裁决永远在服务端（JWT+Redis）。
- 本站页面还单层，散 ref 直取也无妨——**何时升级到 store**：出现"两个组件同读改一份数据"就是信号。

## 下一站

B06 收官：Vite 与构建——dev server/HMR 的原理（为什么改代码即时生效）、
`npm run build` 出来的 dist 里到底有什么（frontend/train-ui/dist 真实目录）、
以及本站 nginx-reload.sh 里 dist 是怎么挂到 http://127.0.0.1:9090/train-ui/ 上的真实流程。
