# JS速通 B01 · 浏览器里的世界：DOM 与事件

> **本站你走在大厅哪一格**：Java 后端那半座桥（A06-A10）走完了，现在换脚站在"浏览器"这一头。
> 你在浏览器里看到的每一个区块、每一次点击，背后都是同一个东西：**DOM（文档对象模型）+ 事件循环**。
> 本篇不需要跑后端，只需要一个浏览器——10 分钟下手改真实网页。
> 打开本站火车的页面（http://127.0.0.1:9090/train-ui/）当靶子即可。

## 名词卡

| 名词 | 人话 |
|---|---|
| DOM | 浏览器把 HTML 解析成的一棵**对象树**；JS 靠操作它来"改网页" |
| 节点（node） | 树上的一个元素/文本/注释，如 `<button>` |
| 事件（event） | 用户或系统发出的"值得注意的事"（click、input、keydown…） |
| 事件监听器（listener） | 你注册的函数：`el.addEventListener('click', fn)` |
| 事件循环（event loop） | JS 的"主厨成单线程 + 任务队列"执行模型 |
| DevTools Console | 浏览器内置的"JS 现场 REPL"，等价于浏览器里开 Python REPL |
| `document.querySelector` | 按 CSS 选择器找 DOM 节点（下文最常用 API） |

## 问题出发

一件真事：外卖小站 `takeout-ui` 打开后，用户在"我的订单"处等 3 秒就该看到骑手进度——这靠的是
`setInterval(loadMine, 3000)` 每 3 秒自己发一次请求"假装推送"。本篇要回答两个问题：
① 浏览器里的 JS 到底是什么（凭什么能改页面、还能"每 3 秒活一次"）；
② 这套"改 DOM + 事件循环"的心法在你翻真码（frontend/takeout-ui/src/App.vue）时怎么一眼看懂。
**跑起来看的姿势**：`bash infra/start-all.sh` 后开 http://127.0.0.1:9090/train-ui/ ，F12 → Console。

## 一、概念人话：网页是一个"活的对象树"

浏览器把 HTML 从标签串解析成**一棵节点树**，每个标签是一个节点对象：

```html
<table>
  <tr><th>车次</th>...</tr>
</table>
```

被浏览器翻译成内存里的对象图：`HTMLTableElement` → `HTMLTableRowElement` → ……
而**这棵树是活的**——JS 随时可以增删改它，页面就跟着变。所谓"前端"，最底层的含义就是**用 JS 改这棵树**。

对比 Python/C++：DOM 就像一棵"由浏览器托管的 std::map<string, Widget>"，JS 是"住进浏览器的访客"，通过一份宿主提供的 API 表去查询和修改这棵树。

## 二、动手验证：打开 DevTools，5 行代码改 DOM

先确保站点在跑（`bash infra/start-all.sh` 后浏览器开 `http://127.0.0.1:9090/train-ui/`），
按 **F12**（或右键 → 检查）打开 DevTools，切到 **Console** 面板，一行一行敲下面 5 行：

```js
// 1) 找到标题节点（本站 train-ui 的 h1 是 "🚄 火车购票小站"）
const h1 = document.querySelector('h1')

// 2) 改它的文字——页面**立刻**变，不用刷新
h1.textContent = '🚄 我刚用 JS 改了这行标题'

// 3) 改颜色和字号（style 直接当对象属性改）
h1.style.color = 'crimson'
h1.style.fontSize = '28px'

// 4) 看看表格行数（DOM 树查询）
const rows = document.querySelectorAll('table tr')
console.log('表格共', rows.length, '行（含表头 1 行）')

// 5) 给每行"余票"列上色：余票越少颜色越红
document.querySelectorAll('table tr').forEach(tr => {
  const tds = tr.querySelectorAll('td')
  if (tds[4] && Number(tds[4].textContent.match(/\d+/)) < 3) tds[4].style.color = 'tomato'
})
```

5 行敲完，你应该亲眼看到：标题变色变字、表格"余票"列里 <3 的数字变红。
这就是前端的**原料真相**——后面 Vue 篇的所有"响应式"最终都落到这样的 DOM 修改上（Vue 只是帮你想办法" onChange 自动改"）。

右侧 **Elements** 面板还可以**直接双击编辑 HTML**（所见即所得地当场改），这是练手第二法。

### 点击事件：min 5 行版

```js
const btn = document.createElement('button')
btn.textContent = '点我'
document.body.append(btn)
btn.addEventListener('click', (e) => alert('事件对象类型：' + e.type))
```

`addEventListener` 注册的回调**不是现在执行**，是"点击这件未来事发生时"由浏览器再调进来——
这就是**事件驱动**：代码变成"预埋的监听"，主线程把控制权让给用户，用户操作才回到你的函数。

## 三、event loop：JS 的"单线程 + 排队"心法

为什么 JS 没有 Java 的 `new Thread()`？答：**主线程永远只有 1 条**（另有一条渲染线程负责画）。
它靠"先干完眼前的活 + 把活排成队列"实现响应：

```
┌─────────── 主线程（JS 引擎）──────────┐
│  1. 跑完当前的同步代码（一次跑完，中途不插队）
│  2. 清空所有 microtask（Promise 的 .then）
│  3. 取 1 个 macrotask（点击回调、定时器、IO 回调）
│  4. 有必要就渲染一帧 → 回到 1
└──────────────────────────────────────┘
```

### 输出顺序的现场实验（把"先微后宏"做实）

```js
console.log('1 同步');
setTimeout(() => console.log('4 宏任务'), 0);      // 0 毫秒也不是现在
Promise.resolve().then(() => console.log('3 微任务'));
console.log('2 同步');
// 输出：1 同步 → 2 同步 → 3 微任务 → 4 宏任务
```

三个立刻能用的推论：

1. **别在主线程跑长循环**——那你把"服务员"堵住了，整个页面卡死不响应。
2. **`setTimeout(fn, 0)` 不是立刻**，是"登记到队列，等当前活干完"。
3. 前端和 GUI 框架（Qt、Windows 消息泵、gtk_main）是**同一个心法**：单线程事件循环 + 事件队列 + 用户回调。
   你写 C++ GUI 的教训（"在事件回调里 sleep 是原罪"）在 JS 里逐条适用。

**事件传播顺序**（点击一个按钮，事件一先一后路过父子）：
`window → document → …祖先 → 目标元素`（捕获），然后**倒序**回传（冒泡）。
你绑 `@click`（Vue 语法，本质仍是 addEventListener）时默认监听冒泡阶段——
所以"点里层按钮，外层 div 的 click 也触发"是常态。

## 三点五、工程实录：踩坑与修复（真机实测）

### 实录 1：主线程一个堵货循环，定时器延迟 3.5 倍——事件循环不是玄学

**问题**：同事抱怨"轮询为什么不准点？说好 3 秒一次， sometimes 快 1 秒慢 3 秒"。根子就在本篇的主线程模型。

**现场复现**（node 当浏览器引擎，同一套 event loop）：

```bash
node -e '
const t0 = Date.now();
setTimeout(() => console.log("定时到点 实际延迟(ms)=", Date.now()-t0), 100);
const start = Date.now();
while (Date.now() - start < 350) {}          // 模拟一次"computed 堵主线程"
console.log("阻塞主线程 350ms 结束");
setTimeout(()=>console.log("宏任务"),0);
Promise.resolve().then(()=>console.log("微任务"));
console.log("同步");
'
```

真实输出（本机实录 2026-09-15）：

```text
阻塞主线程 350ms 结束
同步
微任务
定时到点 实际延迟(ms)= 352
宏任务
```

**逐行解读**：`setTimeout(…,100)` 在主线程被 350ms 的 while 堵住后，**实际 352ms 才到点**——
"定 100ms"的真实语义是"**100ms 后登记进队**，何时执行要看主线程空不空"。输出顺序
`同步 → 微任务 → 宏任务` 也当场坐实了本篇"先清微任务、再取宏任务"的循环口诀。

**排查路径**：DevTools **Performance** 面板录 3 秒，火墙山一样的一格"Task"就是堵货回调。
**修复姿势**：不要在一个回调里干完整批计算（比如渲染 5 万行表格）——分片（`requestIdleCallback`）或
`Web Worker`；本项目 takeout-ui 只拉 5 条订单所以没事，就是这个提醒的真实底色。

### 实录 2：takeout-ui 的"每 3 秒一发"轮询，为什么关页面前要先"摘灯"

真码（frontend/takeout-ui/src/App.vue:55-56）：

```js
let timerId = null
onMounted(() => { loadMenu(); timerId = setInterval(loadMine, 3000) })
onUnmounted(() => clearInterval(timerId))      // ← 没这行，组件死了定时器还活着
```

**为什么这样写**：派单是 Kafka 异步的（W03），前端没有服务器推送通道，只能"轮询假装推送"。
若去掉 `onUnmounted` 那行，`router` 切走后组件"死了"，timer 还在 subscribing"每 3 秒发一次
`/apitakeout/orders/mine`"——DevTools Network 面板能看到鬼魅般的线程（内存泄漏 + 后端白负担）。
**一句警告**：真码里 `let timer = null`（App.vue:12）与 `let timerId = null`（App.vue:55）
两个定时器句柄并存，是教学演进痕迹——**要明确用哪个 handle 去 clear**，别把"没关灯"的原因埋在这种同名不同变量里。

## 二点九、预告：Vue 是"自动化的你"

本篇你全部手干活：querySelector 找节点、textContent 改字、addEventListener 预埋回调。
真实前端所以用 Vue，就是把这套"体力活"变成声明式：

| 你手动（本篇） | Vue 替你（B03 起） |
|---|---|
| `document.querySelector` 把节点抓进变量 | 模板里直接引用 JS 数据 |
| `el.textContent = trips[0].trainNo` | `{{ t.trainNo }}`——数据变，页面自动变 |
| `btn.addEventListener('click', fn)` | `@click="book(t)"` |
| setInterval 拉接口后手动写回每处 DOM | 只改 `trips.value = await api.get(...)`，其余全自动 |

之所以值得先手干一遍再上 Vue，是因为**你已经知道 Vue 底下在替你干什么**——
B03 里它自动更新 DOM 时，你不会误以为那是魔法。

## 二点五、DOM API 速查表（后端视角的"常用方法集"）

| API | 人话 | C++/Python 类比 |
|---|---|---|
| `document.querySelector('css选择器')` | 找**第一个**匹配的节点 | `document.querySelector('#app')` |
| `document.querySelectorAll(...)` | 找**全部**（静态 NodeList，可 forEach） | |
| `el.textContent` | 读写纯文本（不含标签） | |
| `el.innerHTML` | 读写 HTML 片段（含标签，注意 XSS 风险） | |
| `el.style.color = 'red'` | 改内联样式 | |
| `classList.add/remove/toggle('cls')` | 增删 CSS 类 | |
| `document.createElement(tag)` | 造新节点（还不在树上） | |
| `parent.append(child)` | 把节点挂上树 | |
| `el.remove()` | 从树摘除 | |

常见数值：`el.value` 是 input 当前值（train-ui 登录框的 `user` 输入对应它）；`el.disabled` 是禁用态。

**XSS 一句警示**：`innerHTML` 会把字符串**当 HTML 解析**——把用户输入塞进去等于
"允许别人改你的 DOM 结构"。所以本站前端全部用 `textContent` 与 Vue 的 `{{ }}`（同样转义），从不手拼 innerHTML。

### Console 的隐藏技能（比 console.log 更顺手）

在 DevTools Console 里这些内置快捷接口值得先记：

| 输入 | 干什么 |
|---|---|
| `$('h1')` | 等价 `document.querySelector('h1')` |
| `$$('table tr')` | 等价 `document.querySelectorAll(...)` |
| `copy(await fetch('/apitrain/trips').then(r=>r.json()))` | 把接口 JSON 一步丢进剪贴板 |
| `monitorEvents($('h1'), 'click')` | 实时打印某节点的每次事件，调事件流神器 |
| `getEventListeners($('h1'))` | 看节点上已注册了哪些监听（工作原理回溯） |
| `control + Enter`（多行模式） | Console 支持 Shift+Enter 换行写多行脚本 |

## 二点六、事件对象与常用事件类型速查

回调拿到的 `e`（Event 对象）上有常查的几个字段：`e.type`（click/input/…）、
`e.target`（真实触发者）、`e.currentTarget`（当前正在处理的绑定者）、
`e.stopPropagation()`（拦冒泡）、`e.preventDefault()`（拦默认行为，如点 `<a>` 不许跳）。

前端日常五类事件：

| 事件 | 何时触发 | 本站用法 |
|---|---|---|
| `click` | 点一下 | 购票按钮 `@click="book(t)"`（App.vue:91） |
| `input` | 输入框每敲一字 | 登录输入的实时校验 |
| `submit` / `keydown.enter` | 表单提交 / 回车 | 搜索框回车触发查询 |
| `mousemove` / `scroll` | 指针移动 / 滚动 | 高频事件（回调里别干重活） |
| `setInterval` / `setTimeout` | 定时器宏任务 | 外卖页轮询订单状态（下一段 B03） |

本站有个真实行为值得当场停表验证：frontend/takeout-ui/src/App.vue 里
`onMounted(() => { loadMenu(); timerId = setInterval(loadMine, 3000) })`——每 3 秒发一次请求刷新订单，
就是"用 setInterval 假装推送"的轮询（后端真正的推送方案见 demo-chat 的 SSE）。
在 takeout-ui 页面打开 DevTools 的 **Network** 面板得过 3 秒就能看到一排 `/apitakeout/orders/mine`，
这正是"事件循环把定时器回调逐个分发"的可见证据。

## 二点七、从"改 DOM"到"向服务器要数据"（把 A 篇的后端接回来）

本期你已会改"静态皮肤"；真实页面还要**从后端拉数据再渲染**，原料 API 是 `fetch`（浏览器自带的 HTTP 客户端，等价于 Python `urllib.request`）：

```js
// 以 nginx 反代地址取 train 的车次列表（对应 smoke.py 里 /apitrain/trips 那一步）
const r = await fetch('/apitrain/trips')     // Promise<响应>
const trips = await r.json()                  // 响应体解 JSON
console.log('共', trips.length, '个车次')
console.log(trips.map(t => `${t.trainNo}: ${t.fromCity}→${t.toCity} 余票 ${t.stock}`).join('\n'))
```

`await` 让异步调用写起来像同步（B02 详说 Promise），现在只需知道：**fetch 发请求 → 回调在事件循环某轮执行**，
期间主线程照旧响应点击——**"不阻塞 UI"就是异步编程的全部卖点**。
train-ui 生产代码 frontend/train-ui/src/api.js 里用 axios 库干了同样的活：封装 baseURL、自动带 token、统一解 JSON。

## 思考题

1. 你在 Console 里写 `while(true){}`，页面立刻整个卡死。用 event loop 的四个步骤解释"为什么连 DevTools 的停止按钮都不响应"。
2. `setTimeout(fn, 0)` 与"立即执行"差在哪一步？写出一句能证明差异的最小实验代码（打印顺序）。
3. 点一个 `<button>`，若它外层 `<div>` 也绑了 click，两个回调都会跑——谁先？如何让"点按钮"**不**触发外层（提示：`stopPropagation` / Vue 的 `.stop` 修饰符）？

## 练习题

1. 在 DevTools Console 里给本站 train-ui 页面加一个"把所有车次价格加 10 元"的 DOM 改写（querySelectorAll 遍历 `td:nth-child(4)`）。
2. 写 6 行以内 JS：动态生成一个 `<ol>`，把数组 `['上海虹桥','南京南','淄博']` 渲染成三行列表项，append 到 body。
3. 观察事件流：给 body 和一个内层按钮都绑 click，用 `console.log('body 收到')` / `console.log('button 收到')` 验证顺序，然后对按钮那处加 `e.stopPropagation()` 再对比。

## 参考答案

**练 1**：

```js
document.querySelectorAll('table tr td:nth-child(4)').forEach(td => {
  const y = Number(td.textContent.replace('¥', ''))
  if (y > 0) td.textContent = '¥' + (y + 10)
})
```

**练 2**：

```js
const ol = document.createElement('ol');
['上海虹桥','南京南','淄博'].forEach(s => {
  const li = document.createElement('li')
  li.textContent = s
  ol.append(li)
})
document.body.append(ol)
```

**练 3**：

```js
document.body.addEventListener('click', () => console.log('body 收到'))
const b = document.createElement('button')
b.textContent = '内层'
document.body.append(b)
b.addEventListener('click', e => console.log('button 收到'))
// 点击：先 'button 收到'（冒泡从内往外），后 'body 收到'
// 若把第二个绑定改成 e => { e.stopPropagation(); console.log('button 收到') }
// → 再点只打出 'button 收到'，外层回调被拦下
```

## 本节小结
- DOM 是浏览器解析 HTML 得到的**活节点树**；JS 一切"改页面"最终都落在 querySelector 发现节点、改 textContent/style、append/remove 节点。
- DevTools Console 是现场的 JS REPL，Elements 面板支持所见即所得改 HTML。
- 事件是"未来发生才回调"的模型；addEventListener 注册默认监听**冒泡**阶段，可用 stopPropagation 拦截。
- event loop = 单线程 + 任务队列 + 渲染间隙；和 GUI 事件循环是同一心法，别在主线程堵活。
- 下一刻的"Vue 响应式"并没有新魔法——它只是把这 5 行 DOM 修改**变成由数据变化自动触发**。
- 关键内功：知道 Network 面板看请求、Console 试脚本、Elements 面板查结构——三面板联调贯穿 B 篇所有教程。
- 一个自查习惯：改完页面没生效，先看 Network 面板"请求有没有发出、发的对不对"，再看 Console 报错，最后才怀疑代码。

## 下一站

B02 从 Python/C++ 视角重学 JS 语言核心：let/var 的作用域、箭头函数的 this、模板串的 f-string 化、解构的多变量拆包——这些是读懂 train-ui App.vue 真码（frontend/train-ui/src/App.vue）的前置语法。
