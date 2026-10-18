import axios from 'axios'
import { ElMessage } from 'element-plus'
import { getUser, clearUser } from './auth'

const api = axios.create({
  baseURL: '/api',
  timeout: 45000
})

api.interceptors.request.use(config => {
  const token = getUser()?.token
  if (token) config.headers.Authorization = `Bearer ${token}`
  return config
})

api.interceptors.response.use(response => {
  if (response.data?.success === false) {
    const error = new Error(response.data.message || '操作未完成，请重试')
    ElMessage.error(error.message)
    return Promise.reject(error)
  }
  return response
}, error => {
  if (error.response?.status === 401) {
    const sentAuthorization = error.config?.headers?.Authorization
    const token = getUser()?.token
    if (sentAuthorization && sentAuthorization !== `Bearer ${token}`) return Promise.reject(error)
    if (!token && window.location.pathname !== '/login') return Promise.reject(error)
    clearUser()
    if (window.location.pathname !== '/login') {
      window.dispatchEvent(new Event('rwa-session-expired'))
      ElMessage.warning('登录已过期，请重新登录')
    } else {
      ElMessage.error(error.response?.data?.message || '账号或密码错误')
    }
  } else {
    ElMessage.error(error.response?.data?.message || (error.code === 'ECONNABORTED'
      ? '处理超时，请刷新列表核对结果后再操作'
      : '服务暂时不可用，请检查连接后重试'))
  }
  return Promise.reject(error)
})

// 登录
export const login = data => api.post('/auth/login', data)
export const logout = () => api.post('/auth/logout', null, { timeout: 2500 })
export const getSession = () => api.get('/auth/me')

// 总览与链状态
export const getOverview = () => api.get('/dashboard/overview')
export const getHealth = () => api.get('/health')
export const getChainStatus = () => api.get('/chain/status')

// 企业与发票
export const getEnterprises = () => api.get('/enterprise/list')
export const getInvoices = params => api.get('/enterprise/invoices', { params })
export const createInvoice = data => api.post('/enterprise/invoice', data)
export const auditInvoice = (id, action) => api.put(`/enterprise/invoice/${id}/audit`, { action })

// RWA 凭证
export const getTokens = params => api.get('/token/list', { params })
export const mintToken = invoiceId => api.post('/token/mint', { invoiceId })
export const splitToken = data => api.post('/token/split', data)
export const getTopology = () => api.get('/token/topology')
export const verifyToken = tokenId => api.get(`/token/${tokenId}/verify`)

// 融资
export const getLoans = params => api.get('/loan/list', { params })
export const applyLoan = data => api.post('/loan/apply', data)
export const approveLoan = id => api.put(`/loan/${id}/approve`)
export const disburseLoan = id => api.put(`/loan/${id}/disburse`)
export const settleLoan = id => api.put(`/loan/${id}/settle`)
export const rejectLoan = id => api.put(`/loan/${id}/reject`)

export default api
