# JS速通 B02 · ES2015+ 重头戏：let/var、箭头函数、模板串、解构

> **本站你走在大厅哪一格**：B01 你在浏览器里"手动改了 DOM"；本篇把**语言本身**补齐——
> 只讲四样现代 JS（ES2015+）里天天用的东西：**变量声明、箭头函数、模板串、解构**。
> 全篇以 Python/C++ 的视角对照（变量作用域 / f-string / 多值拆包），读完的验收标准：
> 不查资料读懂 train-ui 的 App.vue 全部 65 行 `<script setup>`（frontend/train-ui/src/App.vue）。

## 名词卡

| 名词 | 人话 |
|---|---|
| let / const | "块级作用域"变量与常量；现代 JS 只该写的两种 |
| var | 老式"函数作用域+提升"变量；读旧代码要认识，别再写 |
| 箭头函数 `=>` | 短函数写法：`x => x*2`；**不捕获 this** |
| 普通函数的 this | 由"怎么被调用"决定的隐式参数——坑的来源 |
| 模板串 `` ` `` | 反引号字符串：支持 `${expr}` 插值与换行，≈ Python f-string |
| 解构（destructuring） | 从数组/对象里**按形状拆值**：`const {token} = await api.post(...)` |
| 展开运算符 `...` | 数组/对象拍平拼接：`[...a, x]`、`{...state, page: 2}` |
| ES2015 | JS 语言的"大版本 6"（2015 年起），现代 JS 的分水岭 |

## 一、let/const/var：从 Python/C++ 看作用域

Python 的规则是"函数内赋值即局部"，JS 一度被 var 搞复杂，ES2015 后简化：

```js
function demo() {
  if (true) {
    let a = 1;        // a 的作用域只到 } 结束（块级，像 C++ 的 int）
    const B = 2;      // 不可再赋值，类似 C++ 的 const
    var c = 3;        // 整个函数可见 + 声明提升（hoisting）到函数顶部
  }
  // console.log(a)   // ReferenceError: a 不存在 —— let 块级出了就没了
  console.log(c);     // 3 —— var 无视块，只认函数
}
```

**三条使用军规**：

1. 默认 `const`；会重新赋值的（比如 `trips.value = ...` 那种）才 `let`。
2. `var` 只在读旧代码时认识它；新代码一个都别写。
3. const 变量**内容仍可改**（`const arr = []; arr.push(1)` 合法）——它管"引用不变"，不冻结内容（真冻结要 `Object.freeze`）。

跟 Python 的对照：`let/const ≈ Python 普通变量命名约定（不真不可变）`；真正的区别是 JS 有**块级**这个概念而 Python 没有 `{}`。

## 二、箭头函数：更短，还避开 this 的坑

箭头函数三种缩短：

```js
const double = x => x * 2;                       // 单参单表达：return 省略
const add = (x, y) => x + y;                     // 多参括号必写
const render = () => {
  console.log('多行语句用 {}');
};                                               // 多行用正文块

setTimeout(() => loadTrips(), 3000);                     // 回调场景的最常见姿势
```

**与普通函数的真正区别只有一个词：this。**

```js
const obj = {
  name: '站长',
  hi1: function () { setTimeout(function () { console.log(this.name) }); },  // undefined
  hi2: function () { setTimeout(() => console.log(this.name)); },            // '站长'
};
obj.hi1(); obj.hi2();
```

- 普通函数的 `this` 是"**谁调的我就是谁**"——`setTimeout` 里被当普通函数调，this 掉成 `undefined`/window。
- 箭头函数**没有自己的 this**，用的是**定义处外层的 this**——词法作用域。

对 C++ 同学一把抓：箭头函数像 **lambda 按引用抓 `this`**（C++ 的 `[this]`），普通函数像自由函数。
Python 类比：箭头函数天生等于"bound method"——不存在 self 接错线的问题。

> App.vue 里 8 个 async 函数全用箭头/短体写法，就是为了在 `setTimeout`/回调里 this 语义贴近直觉。

## 二点五、this 的四种来源（一张表管几百行代码）

| 场景 | this 指向 | 备注 |
|---|---|---|
| 对象方法调用 `obj.fn()` | `obj` | "让我调谁 Method，this 就是谁" |
| 普通直调 `fn()` | `undefined`（严格模式）/ window | 掉线主因 |
| 箭头函数 | **定义处外层**的 this | 词法作用域，永不"自己取" |
| `new Fn()` 造对象 | 新造的那个对象 | 等价 C++ 构造器的 this |
| `bind/call/apply` | 手工指定 | 转接老 API 时偶尔用 |

App.vue 全用箭头函数的深层原因，就是绕开前两行的"this 会漂"。
不必死背这张表——**只用箭头函数 + 对象方法，this 问题自动消失**；
读旧代码时再回头翻表。

## 三、模板串：JS 版 f-string

普通引号字符串**不能插值**，模板串（反引号）能：

```js
const user = 'alice', price = 42;
// Python:    f"欢迎 {user}，票价 ¥{price}"
const s1 = `欢迎 ${user}，票价 ¥${price}`;
const s2 = '欢迎 ' + user + '，票价 ¥' + price;    // 等价但难读

// 多行原生（换行合法）
const summary = `车次 G1024
区间 上海虹桥 → 苏州
余票 ${3 - 1}`;
```

`${}` 里可以跑任意表达式：`${price * 1.1}`、`${arr.map(x => x.name).join('、')}`。
**App.vue 里的真例**（frontend/train-ui/src/App.vue:14 与 :26）：

```js
const qs = from.value && to.value ? `?from=${from.value}&to=${to.value}` : ''
message = `下单成功：${b.orderNo.slice(0, 8)}，座位号 ${b.seatNo}（5 分钟内未支付自动取消）`
```

这两行是"模板串 + 三元表达式"的组合，且第二行把**字符串 + 数字混合插值**，等于 f-string 的日常水平。
注意：Python f-string 直接写 `{}` 即可，JS 必须 `${}`——少了美元符不是插值，是原字符。

## 四、解构与展开：多值拆包（Python 的多目标赋值、C++ 的 tie/structured binding）

### 对象解构

```js
const { token, username } = await api.post('/auth/login', {...})
// 等价于先接收响应对象，再 token = resp.token; username = resp.username
```

App.vue 真码（:55）：`const { token, username } = await r`——一行拿两个字段。
Python 对照 `token, username = resp['token'], resp['username']`（甚至 `resp | dict`）；
C++ 对照 C++17 结构化绑定 `auto [token, username] = pair`。

### 数组解构 + 默认值

```js
const [first, second = '缺省'] = ['上海虹桥'];      // first='上海虹桥', second='缺省'
const [, , third] = ['a', 'b', 'c'];                // 跳位取 c
```

### 展开运算符 ...

```js
const arr = [...a, x, ...b];                        // 数组拼接
const next = { ...state, page: state.page + 1 };    // 对象浅拷贝 + 覆盖一个键（不动原对象）
const [head, ...rest] = [1, 2, 3, 4];               // head=1, rest=[2,3,4]
```

一条和 Python 相似的钩子：`{...o}` 浅拷贝与 Python `dict(o)` 同层——嵌套列表/对象**仍是同一份**（A10 的"深浅拷贝坑"在这里完全同一版本）。

### 动手验证：在 Node/浏览器 REPL 里一鼓作气跑本篇所有语法

无需浏览器，直接 Node：`node /tmp/esdemo.js`（或 DevTools Console，效果同）。

```js
// /tmp/esdemo.js
const from = '上海虹桥', to = '苏州';
const trips = { from: from, stock: 3 };

// 1) let/const 重赋值规则
let stock = 3;
stock += 1;                       // ok：let 可重赋值
try { stock = { boom: 1 }; } catch (e) {}   // 仍可：对象本身可变

// 2) 箭头 + 模板串
const seats = ['1号', '2号', '3号'];
console.log(`G1024 余 ${seats.length} 个座位：${seats.join('、')}`);

// 3) 解构多方法组合（模仿 api.post 返回两字段）
const fakeResp = { token: 'abc', username: 'alice', role: 'USER' };
const { token, username, role = 'GUEST' } = fakeResp;    // role 用默认值撑底
console.log(token, username, role);

// 4) 展开：拼新对象而不动原对象
const trips2 = { ...trips, sold: 1 };
console.log(trips, '← 原版没被改');        // { from: '上海虹桥', stock: 3 }
console.log(trips2);
```

**预期输出**：

```
G1024 余 3 个座位：1号、2号、3号
abc alice USER
{ from: '上海虹桥', stock: 3 } ← 原版没被改
{ from: '上海虹桥', stock: 3, sold: 1 }
```

## 五、真实代码走查：这些问题在 App.vue 里各就各位

按 frontend/train-ui/src/App.vue 从上往下，出自本篇的每样语法：

| 行号 | 真码摘录 | 现在你能读懂的 |
|---|---|---|
| 3-7 | `const trips = ref([])` 等 8 个声明 | `ref` 在 B03 才讲；此处先看成"变量" |
| 14 | `` `?from=${from.value}&to=${to.value}` `` | 模板串拼查询串 |
| 23 | `const b = await api.post('/bookings', ...)` | 记住 await，B02 学的就是"异步读作同步" |
| 26 | `` `下单成功：${b.orderNo.slice(0, 8)}，座位号 ${b.seatNo}...` `` | 模板串 + 表达式插值 |
| 55 | `const { token, username } = await r` | 对象解构一次拿两值 |
| 97-99 | `{{ o.orderNo.slice(0, 8) }}`、`o.status === 'CANCELLED'` | 模板区使用这些 JS 值 |

65 行 `<script setup>` 里没有 var、没有 function 关键字的 this 坑、
没有一处手拼 `'a' + v + 'b'`——**这就是现代 JS 语法的极简事实**。
读完 B02 你已是"读代码级" JS 者；写代码级还差 B03 的响应式（数据怎么自动跟着动）。

## 思考题

1. `const arr = []; arr.push(1)` 合法，但 `const x = 1; x = 2` 报错——const"冻结"的到底是什么？要冻结内容用什么？
2. 普通函数与箭头函数的核心差异在经济上只有一个：this 的取值方式。写一段 10 行代码，让 `obj.method()` 中 setTimeout 回调，用箭头输出正确 name、用普通函数输出 undefined——并解释为什么会掉。
3. `{...obj}` 与 JSON.parse(JSON.stringify(obj)) 各自在什么场景下正确/错误？（深拷贝前者的坑 + 后者不能存函数/undefined 的说明）

## 练习题

1. 用解构 + 默认值改写一行：接收 `{ orderNo, seatNo = 1, status = 'UNPAID' }` 并打印三个变量（给一个缺 seatNo 的输入实测默认值生效）。
2. 用展开运算符写 `mergeLessons(base, extra)`：返回"base 字段全保留、extra 字段被覆盖"的新对象，且 base 不被改（用最少三行验证）。
3. 把 App.vue:26 的那条"`下单成功：xxx，座位号 n`"用普通字符串写一遍（+ 拼接），再读一遍说明为什么模板串更可读；顺便用 `slice(0, 8)` 试出 32 位 UUID 短缩效果。

## 参考答案

**练 1**：

```js
const show = ({ orderNo, seatNo = 1, status = 'UNPAID' }) =>
  console.log(`订单 ${orderNo}｜座位 ${seatNo}｜状态 ${status}`);

show({ orderNo: 'e59bf9f1' });           // 订单 e59bf9f1｜座位 1｜状态 UNPAID
show({ orderNo: 'xxxx', seatNo: 7, status: 'PAID' });
```

**练 2**：

```js
const mergeLessons = (base, extra) => ({ ...base, ...extra });
const base = { trip: 'G1024', seats: 3 };
const out = mergeLessons(base, { seats: 0, from: '上海虹桥' });
console.log(base.seats === 3, out);     // true  { trip:'G1024', seats:0, from:'上海虹桥' }
```

**练 3**：

```js
const orderNo = 'e59bf9f1-3f2c-4f4b-a184-2f7a52c9a7f3';
// 模板串版（App.vue:26 原始）
const m = `下单成功：${orderNo.slice(0, 8)}，座位号 ${7}（5 分钟内未支付自动取消）`;
// 普通拼接版（等价，难读）
const p = '下单成功：' + orderNo.slice(0, 8) + '，座位号 ' + 7 +
          '（5 分钟内未支付自动取消）';
console.log(m === p);                   // true
// slice(0, 8) 从 36 位 UUID 里截出 8 位短码，页面展示用（App.vue 与 takeout-ui 同款写法）
```

## 本节小结
- `const` 优先、`let` 次之、`var` 只读不写——ES2015 后作用域回归"块级"直觉，贴近 C++。
- 箭头函数按**定义处**取 this（词法作用域），回调/定时器里不再掉线；对应 C++ `[this]` 的 lambda。
- 模板串是 JS 的 f-string：反引号 + `${表达式}`，多行原生支持。
- 解构 = 按形状拆包（对象/数组，可带默认值），Python/c++17 structured binding 同款；`...` 展开是最常用的"拷贝+覆盖"一步。
- 拷贝警告：`{...o}` 与深拷贝的差距（A10 坑 3 的镜像），嵌套对象仍共享。

## 下一站

B03 上主角：Vue3 组合式 API——setup、ref、onMounted、响应式原理（Proxy 拦截），
以 frontend/train-ui/src/App.vue 的真代码逐行走查，并跑起 `npm run dev` 实际见到数据驱动。
