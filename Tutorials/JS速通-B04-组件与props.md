# JS速通 B04 · 组件与 props：把 App.vue 拆出 TripCard.vue

> **本站你走在大厅哪一格**：B03 里 App.vue 103 行"一锅端"（查询、列表、订单、登录都塞在一个 SFC 里）。
> 本篇学 Vue 的**拆分术**：单文件组件（SFC）能被复用的形态——**props 进、emits 出**。
> 动手目标：亲手把 train-ui 的车次列表拆出一个 `TripCard.vue` 子组件，两层文件互认（真实代码拆分）。

## 名词卡

| 名词 | 人话 |
|---|---|
| 组件（Component） | 可复用的"界面零件"：.vue 文件封装模板+逻辑+样式 |
| SFC（Single File Component） | 单文件组件的正式名称；三段式 `<template>/<script>/<style>` |
| `defineProps` | 子组件声明"我要收什么"（只读入参） |
| `defineEmits` | 子组件声明"我要喊什么"（向父组件发事件） |
| 单向数据流 | props 只能父→子；子想改祖父级？通过 emits 喊，让 **父** 来改 |
| `v-for` + `:key` | 列表渲染：用唯一 key 帮 Vue 做"最小 DOM 改动"diff |
| slot | 组件里"留空给调用方填内容"的插槽——本篇末尾预告，不深展 |

## 一、人话：组件是"函数"，props 是"参数"

没有任何玄虚：

```js
// 数学函数:        f(x) = x 的图
// Vue 组件:        TripCard(trip) = 一张车次卡片的 DOM
```

同样的输入渲染同样的界面（**纯函数级别**的可预测性）——所以组件能像函数一样复用、组合、测试。

数据方向有铁律：**props 单向（父→子）、事件单向（子→父）**。
子组件不能直接改 props 内容（哪怕它是数组里一项也不该动）——
就像函数参数不建议在函数里乱改全局变量。
子要"做事"，用 emit **"喊话"**，由父做决定：

```
父（App.vue）
 ├─ <TripCard :trip="t" @buy="book(t)" />    ← 传数据、听事件
 └─ @book 所绑的 book() 才是"改数据"的唯一负责者
子（TripCard.vue）
 └─ props 收 trip、只渲染；"购票"按钮 emit('book')，不直接改任何数据
```

## 二、真实代码走查：拆前的 App.vue（现状）

frontend/train-ui/src/App.vue（模板部分，:83-93 节选）：

```html
<table border="1" cellspacing="0" cellpadding="6" style="margin-top: 12px; border-collapse: collapse">
  <tr><th>车次</th><th>区间</th><th>时间</th><th>票价</th><th>余票</th><th>操作</th></tr>
  <tr v-for="t in trips" :key="t.id">
    <td>{{ t.trainNo }}</td>
    <td>{{ t.from }} → {{ t.to }}</td>
    <td>{{ t.depart }} - {{ t.arrive }}</td>
    <td>¥{{ t.price }}</td>
    <td>{{ t.stock }}/{{ t.total }}</td>
    <td><button :disabled="!token" @click="book(t)">购票</button></td>
  </tr>
</table>
```

两处拆分信号：

1. **重复的型**：每一 `<tr>` 的呈现规则（车次号→余票）是同一张卡片模板，未来可能另页复用。
2. **职责混杂**：`token` 的登录态、`msg` 的提示语、`book` 的下单逻辑都压在"列表一大块"里。

**拆分后我们要的形态**（预期）：

```
App.vue（父：数据与逻辑的家）
  ├─ <TripCard v-for="t in trips" :key="t.id" :trip="t" :canBook="!!token" @book="book(t)" />
  └─ 其余（登录框/我的订单）不动
TripCard.vue（子：纯呈现 + 喊话）
```

## 三、动手验证：拆出 TripCard.vue（15 分钟全流程）

### 步骤 1：新文件 frontend/train-ui/src/components/TripCard.vue

```vue
<script setup>
// props：我收什么（只读，名字叫 trip / canBook）
const props = defineProps({
  trip:    { type: Object, required: true },
  canBook: { type: Boolean, default: false },
})
// emits：我喊什么（事件名 book，载荷是整个 trip）
const emit = defineEmits(['book'])
</script>

<template>
  <tr>
    <td>{{ trip.trainNo }}</td>
    <td>{{ trip.fromCity }} → {{ trip.toCity }}</td>
    <td>{{ trip.departTime }} - {{ trip.arriveTime }}</td>
    <td>¥{{ trip.priceYuan }}</td>
    <td>{{ trip.stock }}/{{ trip.totalSeats }}</td>
    <td><button :disabled="!canBook" @click="emit('book', trip)">购票</button></td>
  </tr>
</template>
```

注意 `<script setup>` 里 `defineProps`/`defineEmits` 是**编译器宏**：**不用 import 也不能 import**，
Vite 编译时直接展开（B03 名词卡里的"编译器宏"）。

### 步骤 2：改父组件 App.vue 使用子组件

`<script setup>` 顶部加一行引用：

```js
import TripCard from './components/TripCard.vue'
```

模板里的 `<table>` 改为（用子组件替换 tbody 的 v-for）：

```html
<table border="1" cellspacing="0" cellpadding="6" style="margin-top: 12px; border-collapse: collapse">
  <tr><th>车次</th><th>区间</th><th>时间</th><th>票价</th><th>余票</th><th>操作</th></tr>
  <TripCard
    v-for="t in trips"
    :key="t.id"
    :trip="t"
    :can-book="!!token"
    @book="book(t)"
  />
</table>
```

要点：`:can-book` 与 props 里的 `canBook` 是**同一物**（模板里 kebab-case，声明里 camelCase，Vue 自动互认）；
`@book` 监听子组件的 `'book'` 事件，回调用**父的** `book(t)`——子只喊、父才改。

### 步骤 3：验证（HMR 直观看拆分过程）

保持 B03 的 `npm run dev` 在跑，改完文件**不刷新**页面：
- Console 会有 `[vite] hot updated: ...App.vue` / `...TripCard.vue`
- 页面表格**外观与拆分前完全一致**（同一个渲染），但查看 DevTools Elements 面板，每个 `<tr>` 已带 `data-v-xxxx` 属性——那是子组件的**样式隔离指纹**（scoped 才有；本例我们没写 style，可加一个 `<style scoped>` 试一点：td 边框色变）。

**功能回归**：登录→购票→支付→取消，四步全跑一遍，行为与拆前一致即拆分成功（这就是"重构后行为不变"的小试金石）。

### 步骤 3（进阶）：props 单向数据流的"违例实验"

在 TripCard.vue 里加两行<template>体会 Vue 会怎么骂：

```vue
<button @click="trip.stock--">偷偷扣余票（别学）</button>
```

Console 立刻报错：`Set operation on key "stock" failed: target is readonly`（props 是浅只读被 Proxy 包装）。
这就是框架在**为什么不能子改 props** 上的真实态度——把"越权"拦到位。
（还愿意多点注解：父之所见与子之所见是**同一对象**的引用，改了会牵动别处——恰恰又是 B 篇第一节"可变共享"的镜像。）

## 三点五、props 类型与校验：把"约定"写成代码

`defineProps` 支持对象式声明（本篇示例已用），全选项范式：

```js
const props = defineProps({
  trip:     { type: Object,  required: true },
  canBook:  { type: Boolean, default: false },
  discount: { type: Number,  default: 1.0, validator: v => v > 0 && v <= 1 },
})
```

- `type` 可以是多选：`type: [String, Number]`。
- `validator(v)` 返回 false 时**开发模式**告警（不影响生产），帮你尽早发现调用方传错。
- **默认值慎用对象/函数**：`default: () => ({})` 要返回工厂（默认值若写对象字面量，所有实例会共享同一份——又见 A10 坑 3 的 JS 版）。

这台"安检机"和 Java 的参数校验（spring-boot-starter-validation 的 @Min/@NotNull）是同一个心法：
**约定写进代码，让框架在你犯错的现场报警**。

## 二点五、props/emits 速查

| API | 用法 | 备注 |
|---|---|---|
| `defineProps(['trip'])` | 极简：只写名字 | 无类型 |
| `defineProps({ trip: {type: Object, required: true} })` | 带类型/必填校验 | 生产推荐（开发期 Vue 校验并告警） |
| `defineEmits(['book'])`、`emit('book', payload)` | 喊话 | 可 carry 数据；父用 `@book="..."` 接 |
| 模板里 camelCase→kebab-case | `:can-book` ↔ `canBook` | 自动互认 |
| `:key` 里的值必须是稳定唯一 | `:key="t.id"` | 不可用数组 index（增删时 diff 错位） |

## 四点五、slot 一句预告（本篇留个门缝）

真正的复用不止"数据回调"，还有**结构复用**——slot：

```vue
<!-- Card.vue（子） -->
<div class="card">
  <header>{{ title }}</header>
  <slot></slot>              <!-- 留空：调用方填什么就渲染什么 -->
</div>

<!-- 用法（父） -->
<Card title="车次详情">
  <p>从 {{ t.from }} 到 {{ t.to }}</p>
</Card>
```

`<slot>` 位置被调用方的内容**原样填入**——这是"组件版的模板参数"，比 props 更自由（能传结构而非纯数据）。
本篇不展开，记住"props 传数据、slot 传结构、emits 传意向"三分法即可。

## 思考题

1. 为什么 `:key` 不能用数组的 index？考虑"**删掉第 2 条**"时的 diff 过程（提示：Vue 按 key 对比让 DOM 复用最小化，index-as-key 会造成状态错位）。
2. TripCard 里的 @book 事件既可 `@book="book(t)"` 也可 `@book="book"`（不带括号）——第二种写法怎么拿到从子组件 emit 传来的 payload？两种各有适用场景？
3. 单向数据流与 Java 的"接口隔离原则"：组件被设计成"props 进、emits 出"对你以后拆**后端服务**有什么启发（大约 4 句话）？

## 练习题

1. 完整跑完本篇拆分（建 TripCard.vue、改 App.vue、HMR 验证、功能回归），并贴出 `ls frontend/train-ui/src/components/` 的结果证实文件在。
2. 给 TripCard 加 `stockBadge`（= B03 练 1 的三档：售罄/紧张/充足）——**放进子组件里**实现，并给"售罄"时的按钮 disabled（用 computed 计算属性，写清 props 与状态的关系）。
3. 仿同一手法把外卖 takeout-ui 的`<li v-for="d in dishes">`拆出 `DishCard.vue`（含下单按钮），对照菜单真码 frontend/takeout-ui/src/App.vue 里的菜单渲染与下单操作。

## 参考答案

**练 1**：核心三步，检查清单：
- 文件在新目录 `components/`：`ls` 能看到 `TripCard.vue`；
- 父模板 `<TripCard v-for=... :key="t.id">` 正确传入 `:trip`/`:can-book` 并 `@book` 绑回父的 book；
- DevTools Console 无报错，Rows 渲染整齐，操作流（购票/支付/取消）与拆前等行为。

**练 2**：

```vue
<script setup>
import { computed } from 'vue'
const props = defineProps({
  trip: { type: Object, required: true },
  canBook: { type: Boolean, default: false },
})
const emit = defineEmits(['book'])

const badge = computed(() =>
  props.trip.stock === 0 ? '售罄' : props.trip.stock <= 2 ? '紧张' : '充足')
const pending = computed(() => badge.value === '售罄')
</script>

<template>
  <!-- 五列渲染同前，余票列改成 -->
  <td>{{ trip.stock }} / {{ trip.totalSeats }}（{{ badge }}）</td>
  <td><button :disabled="!canBook || pending" @click="emit('book', trip)">购票</button></td>
</template>
```

要点：computed **依赖 props 计算**且随数据变化自动重算（B03 响应式体系直接复用），
"售罄"自然禁按钮——子组件最多报警，改数据仍是父的 book（单向数据流不破）。

**练 3**（要点摘录）：takeout-ui/App.vue 的 `<li v-for="d in dishes" :key="d.id">`（= 店铺/名称/价格/"下单"按钮）
拆出 `DishCard.vue`；和 TripCard 的差别是它是**列表项卡片**（模板里用 `<li>` 作根、同样 defineProps({dish...})+defineEmits(['order'])）。
父方法 `order(d)` 收 emit，与 `@click="order(d)"` 的接线思路完全同型——**这再次印证了"组件就是参数化函数"**。

## 本节小结
- SFC 是"三段式零件"：`<template>`（皮）+`<script setup>`（芯）+（可选）`<style scoped>`（隔离）。
- 数据契约的两端：`defineProps`（子收）与 `defineEmits`（子喊），**父拍板**才动数据——单向数据流。
- `:key` 需要稳定唯一（数据 id），别用 index；`defineProps/defineEmits` 是编译器宏，不用且不能 import。
- 可复用组件的设计顺序：先在父里跑通 → 找"同型视觉块" → 提 props/emits 的最小契约 → 拆文件 HMR 验证。
- 组件命名给"内容"不给"位置"：`TripCard` 而非 `Card2`；props 名给"含义"不给"类型"（`canBook` 而非 `flag`）。
- 同型思路适用后端：服务像组件，接口像 props；任何"偷偷改公共状态"的设计都会绊倒下一任维护者。

## 下一站

B05 Pinia：多个组件共享"登录态/订单缓存"时，props 一层层下传不现实——进入"前端内存数据库"式状态管理（store/getters/持久化）。
