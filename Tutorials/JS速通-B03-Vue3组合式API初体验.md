# JS速通 B03 · Vue3 组合式 API 初体验：ref、onMounted 与响应式原理

> **本站你走在大厅哪一格**：A 篇你写 Java，B01/B02 你会了浏览器和 JS 语法——本篇终于迎来 JS 侧的**主角**：Vue3。
> 课程的三层任务：① 概念：`<script setup>` 里 ref/onMounted/响应式各是什么；
> ② 原理：响应式不是魔法，是 **Proxy 的"读写拦截"**；③ 实战：**跑起 train-ui**（`npm run dev`），
> 对着 frontend/train-ui/src/App.vue 的真代码逐行走查，看到你敲键盘 → 页面自己变的全链路。

## 名词卡

| 名词 | 人话 |
|---|---|
| 组合式 API（Composition API） | Vue3 的函数式写法：一切逻辑写在 `setup`（`<script setup>` 是其编译糖） |
| `ref(x)` | 把值"包成响应式"：改 `.value` Vue 就知道、页面自动跟 |
| `reactive(obj)` | 对象版的响应式（Proxy 深层拦截） |
| `onMounted` | 组件挂载完成后的钩子——front-end 版的 `@PostConstruct` |
| 编译器宏 | `<script setup>` 里"`ref`不用 import 也行"的那批函数，Vite 编译期处理 |
| Proxy | JS 语言级拦截器：对属性的 get/set/dele "插一杠子"（Java 的动态代理嫡亲） |
| 依赖收集 | ref 被"读"时记录谁在读，被"写"时通知他们重算/重渲染 |
| SFC | Single File Component：`.vue` 文件 = 模板 + 逻辑 + 样式的三段式单文件 |

## 零点五、从 B01/B02 到 B03：需要的语法查漏

B03 之前至少应认识这些（均在 B02）：`<script setup>` 里的 `const`、箭头函数、
`async/await`、模板串、`.map(...)`/`.filter(...)` 链式调用。若有一项陌生，先回 B02 的四节补记。

## 一、人话：Vue 帮你省掉的三类活

B01 里你手动干过的三件事，全部被 Vue 自动化：

```
手动 DOM                    → Vue
querySelector 找节点        → 模板 {{ t.train }}（数据即声明）
改完 DOM 手动写回去         → trips.value = 新数组，页面自动 diff 更新
addEventListener 预埋回调   → @click="book(t)"
```

Vue 的贡献一句话：**"页面 = f(数据)"**——你只改数据，框架负责把 DOM 调成数据想要的形状。
而你 B01 亲手捞过 DOM 之后应该明白：**越少的 querySelector 越不会出 bug**，这就是声明式的全部卖点。

## 二、响应式原理：Proxy 拦截一场"读写对话"

最小模型先上（30 行能说明 90% 的问题）：

```js
// /tmp/reactive-demo.js —— node 直接跑
let current = null                            // 当前正在"算"的副作用函数
const deps = new Map()                        // key → 订它的副作用集合

function track(key){ if (current) deps.set(key, current) }          // 读时注册
function trigger(key){ deps.get(key)?.() }                          // 写时呼叫

function reactive(obj) {
  return new Proxy(obj, {
    get(t, k){ track(k); return t[k] },               // 拦"读"
    set(t, k, v){ t[k] = v; trigger(k); return true } // 拦"写"
  })
}

function effect(fn){ current = fn; fn(); current = null }

const state = reactive({ count: 0, label: '未点' })
effect(() => document && console.log('渲染：count =', state.count))
state.count = 1                                  // → 自动再跑 effect
```

**预期输出**：

```
渲染：count = 0
渲染：count = 1
```

guard 第二行打印出"+1 后自动重渲染"的发生时刻——**这就是"数据驱动页面"四字的技术含义**：

- `ref(0)` 底层是 Proxy 拦截**读**（注册"谁依赖我"）与**写**（通知他们重算）。
- Vue 的模板编译产物本质是一个个 effect 函数（render），它读 state 时自动被记录、写 state 时自动重跑。

**Vue3 与 Vue2 的差别**：Vue2 用 `Object.defineProperty` 只能拦截"已有属性"，
侦测不到"新增/删除键"；Vue3 用 ES2015 的 Proxy（语言规范级）**完全拦截**，数组/新增键天然受其拦截。
Java 同学：`java.lang.reflect.Proxy` 是同一概念的两块实现，Spring AOP 的那套"方法拦截"也在这里表态。

**一个最容易踩的坑（记掉）**：

```js
const count = ref(0)
console.log(count)          // Ref对象 {value: 0}——不是 0！
const { value } = count
value = 1                   // ← 却不会触发响应（解构丢代理）
```

**约定**：组合式 API 里，`ref` 用 `x.value` 读写；**模板里自动解包（不用 .value）**；
`reactive` 解构同理会被截断——保持"不随意解构响应式对象"才是好习惯（需要解构的转化用 toRefs）。

## 三、真实代码走查：train-ui App.vue 的前 20 行

```js
// frontend/train-ui/src/App.vue:1-12（节选）
<script setup>
import { ref, onMounted } from 'vue'
import { api } from './api.js'

const trips = ref([])                       // 车次列表（响应式）
const token = ref(localStorage.getItem('train_token') || '')
const user  = ref(localStorage.getItem('train_user') || '')
const from  = ref('上海虹桥')                // 搜索出发地
const to    = ref('')                        // 目的地
const msg   = ref('')
const myOrders = ref([])
```

五个设计点逐一评：

1. `<script setup>`：组合式 API 的**编译糖**——里面每个声明都自动"暴露给模板"（不用 return）。
2. `token` 从 localStorage 起始：**登录态刷新不丢**（B05 的 Pinia 场景前身）。
3. `from` 已预热 `'上海虹桥'`（种子车次的出发地），页面打开即能查。
4. **`.value` 的疆界牢牢记死**：`script` 里必须 `from.value`，模板里写 `from` 即可。

再看逻辑（App.vue:13-32）：

```js
async function loadTrips() {
  const qs = from.value && to.value ? `?from=${from.value}&to=${to.value}` : ''
  trips.value = await api.get('/trips' + qs)     // ← 响应式赋值：一行触发全页刷新
}

async function book(trip) {
  if (!token.value) { alert('请先登录'); return }
  const b = await api.post('/bookings', { tripId: trip.id })
  msg.value = `下单成功：${b.orderNo.slice(0, 8)}，座位号 ${b.seatNo}（5 分钟内未支付自动取消）`
  ...
  await loadTrips(); await loadMine()            // ← 下单后重拉数据，页面自动改
}

onMounted(loadTrips)                              // ← 组件挂载完就查（Init）
```

这里最核心的教学点：**`trips.value = await api.get(...)` 是整个页面唯一的"写"**。
模板 `<tr v-for="t in trips">` 会自己刷新；你不再手动 querySelector、都不必直接碰 DOM。
对比 B01 里手洗 DOM 的 5 行——你交出去的"控制权"换来了**逻辑与视图的拆账**。

## 三点五、生命周期钩子速查（对照 Java/Spring）

| Vue 组合式 | 人话 | Java 对照 |
|---|---|---|
| `setup`（`<script setup>` 本体） | 组件"出生前"的准备工作 | 构造方法 |
| `onBeforeMount` | 首次渲染前 | `@PostConstruct`（早一拍） |
| `onMounted` | 组件已在 DOM 里 | 最常用的 init 钩子（App.vue:64） |
| `onUnmounted` | 组件将死（清理定时器等） | `@PreDestroy` |
| `watch(dep, cb)` | 某数据变了就执行 | 观察者/回调的正式注册器 |

本站两个真实用例：App.vue:64 `onMounted(loadTrips)`（进页就查询）；
takeout-ui App.vue 的 `onMounted(() => { loadMenu(); timerId = setInterval(loadMine, 3000) })`
+ `onUnmounted(() => clearInterval(timerId))`——**进出页面都要"开门负责、关门续灯"**，
这与 Spring Bean 的构造/销毁守则如出一辙。

## 四、动手验证：跑起 train-ui，亲手做两个现场实验

```bash
cd frontend/train-ui && npm run dev
# 输出 Local: http://localhost:5180/  （vite.config.js: port 5180）
```

浏览器开 `http://localhost:5180`，然后**开 DevTools Console**，做两件事：

### 实验 1：改数据 → 看页面自动变

在 Console 直接覆写数据（`window.__VUE__` 手机会卡；改用 Chrome 的 **Pinia/Vue devtools 面板**思路——
简化版在源码里给 `trips` 露一个全局句柄）：

更实用的替代（不用额外配置）——**在页面搜索框敲字**：`from`/`to` 是 `v-model` 绑定的 ref，
你每敲一个字母都触发 input 事件 → `from.value` 被更新 → 点"查询"触发 `loadTrips()`
→ `trips.value` 更新 → **表格自己重排**。这整条链你可以在 Network 面板看请求序列来确认。

### 实验 2：改一处 ts 代码，秒看页面更新（HMR 预览，B06 详讲）

```bash
# 保持 dev server 在跑；另开终端：
sed -i 's/火车购票小站/火车购票小站（改过一行）/g' frontend/train-ui/src/App.vue
```

回过神刷新浏览器都**不用**——HMR 会把热更新直接打到页面上（Console 也有一行 [vite] hmr update）。
这是 B06 的前菜：**Vite 的 dev server 模式与生产 build 的本质区别**。

> **Vue Devtools 装了？** 选项 Devtools 的 Vue 面板可直接在 Components 里看 `trips` `token` 的实时值——
> 比 console 调试快十倍（Chrome 商店搜 Vue.js devtools）。

## 思考题

1. 为什么模板里写 `{{ trips }}` 不写 `{{ trips.value }}`？而**`loadTrips` 函数体内**必须写 `trips.value`——这条双规是哪一侧（编译器 vs 运行时）处理的？
2. `reactive` 解构后失去响应性的根源是什么（提示：Proxy 靠"每次读写都经过它"）？哪些写法能保住响应性？
3. App.vue 若把 `onMounted(loadTrips)` 改成 `onMounted(() => loadTrips())`，最薄的差异是什么？更早的钩子 `setup`/`onBeforeMount` 里发请求会少什么？

## 练习题

1. 给 App.vue 加一个 `stockBadge(t)`：`t.stock === 0` 返回 `'售罄'`，`<= 2` 返回 `'紧张'`，否则 `'充足'`，并在模板表格新列里用它（写完跑 `npm run dev` 验证）。
2. 用 `ref` 写一个秒表：`const count = ref(0)`，按钮点击时 `count.value++`，页面显示 `点击次数：{{ count }}`（8 行内）。
3. 往 `/tmp/reactive-demo.js` 的 `effect` 里多加一个依赖（`state.label`），然后改 label——观察**只**改 label 时两个 effect 谁被触发（学习"依赖收集按字段精确通知"）。
4. 给 App.vue 的"查询"按钮做防抖 500ms（`setTimeout` 句柄，每次清除再重排），并用 DevTools Network 面板观察两次快速点击只发出一次请求。

## 参考答案

**练 1**：

```js
// <script setup> 里加：
function stockBadge(t) {
  if (t.stock === 0) return '售罄'
  if (t.stock <= 2) return '紧张'
  return '充足'
}
// <template> 表格 <th> 加一列"状态"，<td> 加：
// <td :class="t.stock <= 2 ? 'warn' : ''">{{ stockBadge(t) }}</td>
```

跑 `npm run dev`：G1024（3/3）显示"充足"，D3102（1/1）显示"紧张"——**只要 trips.value 变，这一列的整段区域自动重算**。

**练 2**：

```html
<script setup>
import { ref } from 'vue'
const count = ref(0)
</script>
<template>
  <button @click="count++">点我</button>
  <p>点击次数：{{ count }}</p>
</template>
```

要点：模板里绑定的是 ref 的"值层"（Vue 自动解包），所以 `{{ count }}` 无 `.value`；而 script 里 `count++` 也会自动解包（`<script setup>` 编译糖处理）。

**练 3**：

```js
effect(() => console.log('渲染A：count =', state.count))
effect(() => console.log('渲染B：label =', state.label))
state.label = '新标签'
// 输出：只打印一行"渲染B：label = 新标签"，count 的 effect 完全不跑
```

机理：`get` 拦截按**具体字段**登记（deps.set('label', effectB)），写 label 只吊写字段的订阅者——
这就是"字段级通知"而不是"对象级广播"换来的性能。

**练 4**：把 App.vue 的"查询"按钮加上"防抖 500ms"效果（输入后停顿再点才发请求）——用 `setTimeout` 句柄的清筒写法：

```js
let h = null
function searchDebounced() {
  if (h) clearTimeout(h)
  h = setTimeout(loadTrips, 500)
}
// 模板里把 @click="loadTrips" 换成 @click="searchDebounced"
```

原理：每次点击先**清掉上一次排好的任务**再重新排——事件循环视角下，定时器只是"队列里的宏任务"，
主动取消就是把它从队列里摘除（与 takeout-ui 的 setInterval(saveMy, 3000) 同一家族）。

## 本节小结
- 组合式 API = "一切放进 setup"：`ref()` 包变量、`onMounted` 等钩子、函数里 await api 异步取数。
- 响应式 = Proxy 的读写拦截 + 依赖收集：读注册、写通知，你永远只写数据不碰 DOM。
- `.value` 是 ref 双面的疆界：script 里带，模板里自动解包；解构会截断响应性。
- App.vue 的 65 行把 B01+B02 的手活全部收编：一行 `trips.value = await api.get(...)` 相当于手洗 DOM 的整页刷新。
- 关键内功：知道 Console 试脚本、Network 面板看请求、Vue Devtools 查组件——三面板联调贯穿 B 篇所有教程。
- v-model 双向绑定 = "input 事件 + :value 单向绑定"的合体糖；登录框的 `user` ref 每敲一字都在更新。
- Vue Devtools 的 Components 面板可直接看 `trips`/`token` 的实时值，比 console 调试快十倍。
- HMR 已初见：改代码不刷新就生效——下一站先拆"子组件与 props"，再进 B05 的状态库与 B06 的构建。

## 下一站

B04 组件与 props：把 App.vue 103 行的大组件拆出真正可复用的 TripCard.vue——SFC 单文件组件的完整形态（`<template>`/props/emits/父子通信）。
