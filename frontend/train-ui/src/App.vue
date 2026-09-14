<script setup>
import { ref, onMounted } from 'vue'
import { api } from './api.js'

const trips = ref([])
const token = ref(localStorage.getItem('train_token') || '')
const user = ref(localStorage.getItem('train_user') || '')
const from = ref('上海虹桥')
const to = ref('')
const msg = ref('')
const myOrders = ref([])

async function loadTrips() {
  const qs = from.value && to.value ? `?from=${from.value}&to=${to.value}` : ''
  trips.value = await api.get('/trips' + qs)
}

async function loadMine() {
  if (!token.value) return
  myOrders.value = await api.get('/bookings/mine')
}

async function book(trip) {
  if (!token.value) { alert('请先登录'); return }
  const b = await api.post('/bookings', { tripId: trip.id })
  msg.value = `下单成功：${b.orderNo.slice(0, 8)}，座位号 ${b.seatNo}（5 分钟内未支付自动取消）`
  if (confirm(`订单已创建，座位 ${b.seatNo}，模拟支付？（确定=支付）`)) {
    await api.post('/bookings/pay', { orderNo: b.orderNo })
    msg.value = `已支付，出票成功！座位号 ${b.seatNo}`
  }
  await loadTrips(); await loadMine()
}

async function pay(orderNo) {
  await api.post('/bookings/pay', { orderNo })
  msg.value = '支付成功'
  await loadMine(); await loadTrips()
}

async function cancel(orderNo) {
  await api.post('/bookings/cancel', { orderNo })
  msg.value = '已取消，余票已回滚'
  await loadMine(); await loadTrips()
}

async function login(isRegister) {
  const p = prompt(isRegister ? '设置密码' : '输入密码', isRegister ? '' : '123456')
  if (!p) return
  try {
    if (isRegister) {
      await api.post('/auth/register', { username: user.value.trim(), password: p.trim() })
    }
    const r = api.post('/auth/login', { username: user.value.trim(), password: p.trim() })
      .catch(() => Promise.reject('登录失败'))
    const { token, username } = await r
    token.value = token; user.value = username
    localStorage.setItem('train_token', token)
    localStorage.setItem('train_user', username)
    msg.value = '已登录'
    await loadMine()
  } catch (e) { msg.value = String(e) }
}

onMounted(loadTrips)
</script>

<template>
  <div style="max-width: 820px; margin: 24px auto; font-family: sans-serif">
    <h1>🚄 火车购票小站</h1>
    <p v-if="msg" style="color: #0a7">{{ msg }}</p>
    <div style="display: flex; gap: 8px">
      <input v-model="user" placeholder="用户名" />
      <button @click="login(false)">登录</button>
      <button @click="login(true)">注册</button>
      <span v-if="token">欢迎，{{ user }}</span>
    </div>
    <hr />
    <div style="display: flex; gap: 8px">
      <input v-model="from" placeholder="出发地" />
      <input v-model="to" placeholder="目的地（可空）" />
      <button @click="loadTrips">查询</button>
    </div>
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
    <h3>我的订单</h3>
    <ul>
      <li v-for="o in myOrders" :key="o.id">
        {{ o.orderNo.slice(0, 8) }}｜座位 {{ o.seatNo }}｜{{ o.status }}
        <button v-if="o.status === 'UNPAID'" @click="pay(o.orderNo)">支付</button>
        <button v-if="o.status !== 'CANCELLED'" @click="cancel(o.orderNo)">取消</button>
      </li>
    </ul>
  </div>
</template>
