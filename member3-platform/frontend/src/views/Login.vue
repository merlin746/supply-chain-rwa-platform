<script setup>
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { login } from '../api'
import { setUser, homeOf } from '../auth'
import { ElMessage } from 'element-plus'

const router = useRouter()
const form = ref({ username: '', password: '' })
const loading = ref(false)

const demoAccounts = [
  { username: 'byd', label: '比亚迪 · 核心企业' },
  { username: 'kdl', label: '科达利 · 一级供应商' },
  { username: 'jnyt', label: '聚能永拓 · 二级供应商' },
  { username: 'cytf', label: '长园特发 · 三级供应商' },
  { username: 'ccb', label: '建设银行 · 资金方' }
]

function fill(u) {
  form.value.username = u
  form.value.password = '123456'
}

async function submit() {
  if (!form.value.username || !form.value.password) {
    ElMessage.warning('请输入用户名和密码')
    return
  }
  loading.value = true
  try {
    const res = await login(form.value)
    if (!res.data.success) {
      ElMessage.error(res.data.message || '登录失败')
      return
    }
    setUser(res.data.data)
    ElMessage.success(`欢迎，${res.data.data.realName}`)
    router.push(homeOf(res.data.data.role))
  } catch (e) {
    ElMessage.error('后端服务未启动或网络异常')
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="login-wrap">
    <el-card class="login-card">
      <div class="login-title">RWA 供应链金融平台</div>
      <div class="login-sub">应收账款数字凭证 · 信用穿透 · 融资清算</div>
      <el-form label-position="top" @keyup.enter="submit">
        <el-form-item label="用户名">
          <el-input v-model="form.username" placeholder="请输入用户名"/>
        </el-form-item>
        <el-form-item label="密码">
          <el-input v-model="form.password" type="password" show-password placeholder="请输入密码"/>
        </el-form-item>
        <el-button type="primary" style="width:100%" :loading="loading" @click="submit">登 录</el-button>
      </el-form>
      <el-divider>演示账号（密码均为 123456）</el-divider>
      <div class="demo-accounts">
        <el-tag v-for="a in demoAccounts" :key="a.username" class="demo-tag" @click="fill(a.username)">
          {{ a.label }}
        </el-tag>
      </div>
    </el-card>
  </div>
</template>

<style scoped>
.login-wrap {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(135deg, #1f2d3d 0%, #2b4b6f 100%);
}
.login-card { width: 420px; }
.login-title { font-size: 22px; font-weight: 700; text-align: center; }
.login-sub { font-size: 13px; color: #909399; text-align: center; margin: 8px 0 20px; }
.demo-accounts { display: flex; flex-wrap: wrap; gap: 8px; justify-content: center; }
.demo-tag { cursor: pointer; }
</style>
