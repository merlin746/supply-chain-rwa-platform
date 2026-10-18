<script setup>
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Grid, DocumentChecked, Connection, Wallet, ArrowDown, SwitchButton, Menu, Close, ArrowRight } from '@element-plus/icons-vue'
import { getHealth, getSession, logout as endSession } from './api'
import { authUser as user, clearUser, setUser, homeOf } from './auth'
import { ElMessageBox } from 'element-plus'

const route = useRoute()
const router = useRouter()
const online = ref(false)
const mobileMenu = ref(false)
const loggingOut = ref(false)
const roleLabel = computed(() => ({ CORE_ENTERPRISE: '核心企业', SUPPLIER: '供应商', BANK: '金融机构', ADMIN: '管理员' })[user.value?.role] || '')
const navigation = computed(() => [
  { path: '/dashboard', label: '业务总览', hint: 'Overview', icon: Grid },
  { path: '/core', label: '资产确权', hint: 'Asset issuance', icon: DocumentChecked, roles: ['CORE_ENTERPRISE', 'ADMIN'] },
  { path: '/supplier', label: '资产流转', hint: 'Transfer & finance', icon: Connection, roles: ['SUPPLIER', 'ADMIN'] },
  { path: '/bank', label: '融资管理', hint: 'Financing', icon: Wallet, roles: ['BANK', 'ADMIN'] }
].filter(item => !item.roles || item.roles.includes(user.value?.role)))

async function checkHealth() {
  try { online.value = (await getHealth()).data?.data?.status === 'UP' } catch { online.value = false }
}
function expire() { mobileMenu.value = false; router.replace('/login') }
function navigate(path) { mobileMenu.value = false; router.push(path) }
async function logout() {
  if (loggingOut.value) return
  try { await ElMessageBox.confirm('退出后需要重新登录才能继续操作。', '退出当前账号', { confirmButtonText: '退出登录', cancelButtonText: '取消' }) } catch { return }
  const token = user.value?.token
  loggingOut.value = true
  try { await endSession() } catch { /* Local credentials are always cleared below. */ } finally {
    loggingOut.value = false
    if (user.value?.token === token) { clearUser(); expire() }
  }
}
let healthTimer
onMounted(async () => {
  window.addEventListener('rwa-session-expired', expire)
  checkHealth()
  healthTimer = window.setInterval(checkHealth, 60000)
  if (user.value) {
    const token = user.value.token
    try {
      const session = (await getSession()).data.data
      if (user.value?.token !== token) return
      setUser({ ...user.value, ...session })
      if (route.meta.roles && !route.meta.roles.includes(session.role)) router.replace(homeOf(session.role))
    } catch {
      if (user.value?.token === token) { clearUser(); expire() }
    }
  }
})
onUnmounted(() => { window.clearInterval(healthTimer); window.removeEventListener('rwa-session-expired', expire) })
</script>

<template>
  <router-view v-if="route.path === '/login'" />
  <div v-else class="workspace-shell">
    <button v-if="mobileMenu" class="sidebar-shade" aria-label="关闭导航" @click="mobileMenu=false"></button>
    <aside class="workspace-sidebar" :class="{ 'is-open': mobileMenu }">
      <router-link to="/dashboard" class="brand-lockup" @click="mobileMenu=false">
        <span class="brand-mark"><el-icon><Connection /></el-icon></span>
        <span><strong>RWA<span class="brand-dot">.</span></strong><small>供应链金融平台</small></span>
      </router-link>
      <div class="nav-section-label">WORKSPACE <span>工作空间</span></div>
      <nav class="workspace-nav" aria-label="主导航">
        <button v-for="item in navigation" :key="item.path" class="nav-item" :class="{ active: route.path === item.path }" @click="navigate(item.path)">
          <el-icon><component :is="item.icon" /></el-icon>
          <span>{{ item.label }}<small>{{ item.hint }}</small></span>
          <el-icon v-if="route.path === item.path" class="nav-arrow"><ArrowRight /></el-icon>
        </button>
      </nav>
      <div class="sidebar-note"><span class="sidebar-note-icon">↗</span><strong>让信用，沿供应链流动</strong><p>从真实贸易到数字资产<br>连接每一级供应商</p></div>
      <div class="sidebar-footer"><span class="status-dot" :class="{ online }"></span>{{ online ? '平台服务在线' : '正在连接服务' }}<span>V1.1</span></div>
    </aside>
    <div class="workspace-content">
      <header class="workspace-topbar">
        <div class="breadcrumb"><button class="icon-button menu-toggle" aria-label="展开导航" @click="mobileMenu=!mobileMenu"><el-icon><component :is="mobileMenu ? Close : Menu" /></el-icon></button><span>工作空间</span><span class="breadcrumb-separator">/</span><strong>{{ route.meta.title }}</strong></div>
        <div class="topbar-right"><span class="workspace-label"><i></i> 企业工作台</span><el-dropdown trigger="click" @command="logout"><button class="account-button"><span class="account-avatar">{{ user?.enterpriseName?.slice(0,1) || '企' }}</span><span class="account-copy"><strong>{{ user?.enterpriseName || user?.realName }}</strong><small>{{ roleLabel }}</small></span><el-icon><ArrowDown /></el-icon></button><template #dropdown><el-dropdown-menu><el-dropdown-item command="logout" :icon="SwitchButton">退出登录</el-dropdown-item></el-dropdown-menu></template></el-dropdown></div>
      </header>
      <main id="main-content" class="workspace-main"><router-view :key="`${user?.id}-${route.path}`" /></main>
      <footer class="workspace-footer"><span>RWA 供应链金融平台</span><span>真实贸易 · 数字确权 · 信用流转</span></footer>
    </div>
  </div>
</template>
