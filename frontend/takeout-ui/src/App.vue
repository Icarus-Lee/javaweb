<script setup>
import { ref, onMounted, onUnmounted } from 'vue'
import { api } from './api.js'

const dishes = ref([])
const orders = ref([])
const token = ref(localStorage.getItem('takeout_token') || '')
const user = ref(localStorage.getItem('takeout_user') || '')
const role = ref(localStorage.getItem('takeout_role') || '')
const msg = ref('')
let timer = null

async function loadMenu() {
  dishes.value = await api.get('/menus')
}

async function loadMine() {
  if (!token.value) return
  orders.value = await api.get('/orders/mine')
}

async function order(d) {
  if (!token.value) { alert('请先登录'); return }
  const o = await api.post('/orders', { shopId: d.shopId, dishId: d.id, quantity: 1 })
  msg.value = `订单 ${o.orderNo.slice(0, 8)} 已创建（CREATED），去支付`
  await api.post(`/orders/${o.orderNo}/pay`)
  msg.value += '，已支付！等待骑手派单…'
  loadMenu(); loadMine()
}

async function deliver(o) {
  await api.post(`/rider/${o.orderNo}/deliver`)
  msg.value = '已确认送达'
  loadMine()
}

async function login(isRegister) {
  const p = prompt('密码（演示固定 123456）', isRegister ? '' : '123456')
  if (!p) return
  try {
    if (isRegister) {
      const r0 = await api.post('/auth/register', { username: user.value.trim(), password: p.trim(), role: role.value === 'RIDER' ? 'RIDER' : 'USER' })
    }
    const { token, role } = await api.post('/auth/login', { username: user.value.trim(), password: p.trim() })
    token.value = token; role.value = role; user.value = user.value.trim()
    localStorage.setItem('takeout_token', token)
    localStorage.setItem('takeout_role', role)
    localStorage.setItem('takeout_user', user.value)
    msg.value = '已登录'
    loadMine()
  } catch (e) { msg.value = String(e) }
}

// 轮询：由于派单是 Kafka 异步的，下单后要"过会儿再看"
let timerId = null
onMounted(() => { loadMenu(); timerId = setInterval(loadMine, 3000) })
onUnmounted(() => clearInterval(timerId))
</script>

<template>
  <div style="max-width: 820px; margin: 24px auto; font-family: sans-serif">
    <h1>🍔 外卖小站（骑手编号 9000 随时候命）</h1>
    <p v-if="msg" style="color: #a06">{{ msg }}</p>
    <div style="display: flex; gap: 8px">
      <input v-model="user" placeholder="用户名（alice / rider9）" />
      <button @click="login(false)">登录</button>
      <button @click="login(true)">注册（均为 USER；骑手示例账号 rider9 存在）</button>
      <span v-if="token">欢迎，{{ user }}{{ role === 'RIDER' ? '（骑手端）' : '' }}</span>
    </div>
    <hr />
    <h3>菜单（Redis 缓存 60 秒）</h3>
    <ul>
      <li v-for="d in dishes" :key="d.id">
        店{{ d.shopId }}·{{ d.name }}——¥{{ d.priceYuan }}
        <button :disabled="!token || role === 'RIDER'" @click="order(d)">下单</button>
      </li>
    </ul>
    <h3>订单</h3>
    <ul>
      <li v-for="o in orders" :key="o.orderNo">
        {{ o.orderNo.slice(0, 8) }}｜¥{{ o.totalYuan }}｜{{ o.status }}
        <span v-if="o.riderId">｜骑手 {{ o.riderId }}</span>
        <button v-if="role === 'RIDER' && o.status === 'DISPATCHED'" @click="deliver(o)">送达</button>
      </li>
    </ul>
  </div>
</template>
