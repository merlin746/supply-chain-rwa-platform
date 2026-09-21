import { createRouter, createWebHistory } from 'vue-router'
import Dashboard from '../views/Dashboard.vue'
import CoreEnterprise from '../views/CoreEnterprise.vue'
import Supplier from '../views/Supplier.vue'
import Bank from '../views/Bank.vue'
import Login from '../views/Login.vue'
import { getUser } from '../auth'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/login', component: Login },
    { path: '/', redirect: '/dashboard' },
    { path: '/dashboard', component: Dashboard },
    { path: '/core', component: CoreEnterprise },
    { path: '/supplier', component: Supplier },
    { path: '/bank', component: Bank }
  ]
})

router.beforeEach(to => {
  if (to.path !== '/login' && !getUser()) {
    return '/login'
  }
  if (to.path === '/login' && getUser()) {
    return '/dashboard'
  }
  return true
})

export default router
