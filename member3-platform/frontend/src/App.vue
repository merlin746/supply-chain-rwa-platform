<script setup>
import { ref, computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { getHealth } from './api'
import { getUser, clearUser } from './auth'
import { ElMessageBox } from 'element-plus'

const route = useRoute()
const router = useRouter()
const chainOk = ref(false)
const user = ref(getUser())

const isLoginPage = computed(() => route.path === '/login')

const roleLabel = computed(() => {
  switch (user.value?.role) {
    case 'CORE_ENTERPRISE': return '核心企业'
    case 'SUPPLIER': return '供应商'
    case 'BANK': return '资金方'
    case 'ADMIN': return '管理员'
    default: return ''
  }
})

async function check() {
  try {
    const res = await getHealth()
    chainOk.value = res.data?.data?.status === 'UP'
  } catch {
    chainOk.value = false
  }
}
check()
setInterval(check, 30000)

function go(path) {
  router.push(path)
}

async function logout() {
  await ElMessageBox.confirm('确定退出登录吗？', '提示', { type: 'warning' })
  clearUser()
  router.push('/login')
}
</script>

<template>
  <router-view v-if="isLoginPage" />

  <el-container v-else class="layout">
    <el-header class="header">
      <div class="brand">RWA 供应链金融平台</div>
      <div class="header-right">
        <div class="chain-status">
          <span :class="chainOk ? 'dot ok' : 'dot bad'"></span>
          {{ chainOk ? '系统在线' : '系统检测中' }}
        </div>
        <el-dropdown v-if="user">
          <span class="user-chip">
            {{ user.realName || user.username }}
            <el-tag size="small" style="margin-left:6px">{{ roleLabel }}</el-tag>
          </span>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item disabled>{{ user.enterpriseName || '平台' }}</el-dropdown-item>
              <el-dropdown-item divided @click="logout">退出登录</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </div>
    </el-header>

    <el-container>
      <el-aside width="220px" class="aside">
        <el-menu :default-active="route.path" @select="go">
          <el-menu-item index="/dashboard">平台总览</el-menu-item>
          <el-menu-item index="/core">核心企业 Portal</el-menu-item>
          <el-menu-item index="/supplier">供应商 Portal</el-menu-item>
          <el-menu-item index="/bank">银行 Portal</el-menu-item>
        </el-menu>
      </el-aside>

      <el-main class="main">
        <router-view />
      </el-main>
    </el-container>
  </el-container>
</template>
