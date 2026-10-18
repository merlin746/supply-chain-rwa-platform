import { createRouter, createWebHistory } from 'vue-router'
import { getUser, homeOf } from '../auth'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/login', component: () => import('../views/Login.vue'), meta: { title: '登录' } },
    { path: '/', redirect: '/dashboard' },
    { path: '/dashboard', component: () => import('../views/Dashboard.vue'), meta: { title: '业务总览' } },
    { path: '/core', component: () => import('../views/CoreEnterprise.vue'), meta: { title: '资产确权', roles: ['CORE_ENTERPRISE', 'ADMIN'] } },
    { path: '/supplier', component: () => import('../views/Supplier.vue'), meta: { title: '资产流转', roles: ['SUPPLIER', 'ADMIN'] } },
    { path: '/bank', component: () => import('../views/Bank.vue'), meta: { title: '融资管理', roles: ['BANK', 'ADMIN'] } },
    { path: '/:pathMatch(.*)*', redirect: '/dashboard' }
  ]
})

router.beforeEach(to => {
  const user = getUser()
  if (to.path !== '/login' && !user) return '/login'
  if (to.path === '/login' && user) return homeOf(user.role)
  if (to.meta.roles && !to.meta.roles.includes(user?.role)) return '/dashboard'
  document.title = `${to.meta.title || '工作台'} · RWA 供应链金融平台`
  return true
})

export default router
