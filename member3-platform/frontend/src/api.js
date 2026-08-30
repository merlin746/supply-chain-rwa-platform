import axios from 'axios'

const api = axios.create({
  baseURL: '/api',
  timeout: 10000
})

// 登录
export const login = data => api.post('/auth/login', data)

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
export const rejectLoan = id => api.put(`/loan/${id}/reject`)

export default api
