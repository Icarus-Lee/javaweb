import axios from 'axios'

export const api = axios.create({ baseURL: '/api' })

api.interceptors.request.use((c) => {
  const token = localStorage.getItem('train_token')
  if (token) c.headers.Authorization = 'Bearer ' + token
  return c
})

api.interceptors.response.use(
  r => r.data,
  err => {
    const msg = err.response?.data?.message || err.message
    return Promise.reject(msg)
  }
)
