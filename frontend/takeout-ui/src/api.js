import axios from 'axios'

export const api = axios.create({ baseURL: '/apitakeout' })

api.interceptors.request.use(c => {
  const token = localStorage.getItem('takeout_token')
  if (token) c.headers.Authorization = 'Bearer ' + token
  return c
})
api.interceptors.response.use(r => r.data, err => Promise.reject(err.response?.data?.message || err.message))
