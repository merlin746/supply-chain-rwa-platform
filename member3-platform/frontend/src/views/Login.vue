<script setup>
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { login, getHealth } from '../api'
import { setUser, homeOf } from '../auth'
import { ElMessage } from 'element-plus'
import { Connection, ArrowRight, User, Lock, Check } from '@element-plus/icons-vue'
import { onMounted } from 'vue'

const router = useRouter()
const form = ref({ username: '', password: '' })
const loading = ref(false)
const available = ref(null)
const selected = ref('')

const demoAccounts = [
  { username: 'byd', label: '比亚迪 · 核心企业' },
  { username: 'kdl', label: '科达利 · 一级供应商' },
  { username: 'jnyt', label: '聚能永拓 · 二级供应商' },
  { username: 'cytf', label: '长园特发 · 三级供应商' },
  { username: 'ccb', label: '建设银行 · 资金方' }
]

function fill(u) {
  selected.value = u
  form.value.username = u
  form.value.password = '123456'
}

async function submit() {
  if (loading.value) return
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
  } catch { /* API interceptor displays the service error. */ } finally {
    loading.value = false
  }
}
onMounted(async () => {
  try { available.value = (await getHealth()).data.data.status === 'UP' } catch { available.value = false }
})
</script>

<template>
  <div class="login-page">
    <section class="login-story">
      <div class="story-grid"></div>
      <div class="login-brand"><span class="brand-mark"><el-icon><Connection /></el-icon></span><span>RWA<span class="brand-dot">.</span><small>供应链金融平台</small></span></div>
      <div class="story-copy"><div class="story-kicker"><span></span> REAL-WORLD ASSETS / SUPPLY CHAIN</div><h1>让真实资产<br>连接每一份<span>信用。</span></h1><p>从一张应收账款开始，<br>让核心企业信用抵达供应链的每一端。</p></div>
      <div class="credit-illustration" aria-label="核心企业通过数字凭证向多级供应商传递信用">
        <svg viewBox="0 0 500 170" aria-hidden="true"><path d="M85 75 H225 Q245 75 245 55 V40 H350 M245 75 V125 H350" fill="none" stroke="#437368" stroke-width="1.5"/><circle cx="185" cy="75" r="4" fill="#93c9a8"/><circle cx="300" cy="40" r="4" fill="#93c9a8"/><circle cx="300" cy="125" r="4" fill="#93c9a8"/></svg>
        <div class="credit-origin"><span class="credit-icon">企</span><strong>核心企业</strong><small>真实贸易 · 信用背书</small><div><i></i> 应收账款已确权</div></div>
        <div class="credit-destination first"><span>01</span><div><strong>一级供应商</strong><small>数字资产接收</small></div><el-icon><Check /></el-icon></div>
        <div class="credit-destination second"><span>02</span><div><strong>多级供应商</strong><small>信用持续流转</small></div><el-icon><Check /></el-icon></div>
      </div>
      <div class="story-footer"><span>确权有据</span><i></i><span>流转有序</span><i></i><span>融资可溯</span></div>
    </section>
    <section class="login-form-area">
      <div class="login-service"><span class="status-dot" :class="{ online: available }"></span>{{ available === null ? '正在连接平台' : available ? '平台服务在线' : '暂未连接后端服务' }}</div>
      <div class="login-form-content"><div class="page-eyebrow">WELCOME TO RWA</div><h2>登录企业工作台</h2><p class="login-description">管理您的数字资产，开启可信的供应链协作。</p>
        <el-form label-position="top" @submit.prevent="submit"><el-form-item label="企业账号"><el-input v-model.trim="form.username" size="large" placeholder="请输入账号" autocomplete="username" :prefix-icon="User" /></el-form-item><el-form-item label="登录密码"><el-input v-model="form.password" size="large" type="password" show-password placeholder="请输入密码" autocomplete="current-password" :prefix-icon="Lock" /></el-form-item><el-button native-type="submit" class="login-submit" size="large" type="primary" :loading="loading">进入工作台<el-icon><ArrowRight /></el-icon></el-button></el-form>
        <div class="demo-heading"><span>快速体验</span><small>选择演示角色，自动填入账号</small></div>
        <div class="demo-accounts"><button v-for="a in demoAccounts" :key="a.username" type="button" class="demo-account" :class="{ selected: selected === a.username }" @click="fill(a.username)"><span>{{ a.label.split(' · ')[0] }}</span><small>{{ a.label.split(' · ')[1] }}</small><el-icon v-if="selected===a.username"><Check /></el-icon></button></div>
        <div class="login-hint">演示账号密码均为 123456 · 不涉及真实资金交易</div>
      </div>
      <footer>© {{ new Date().getFullYear() }} RWA 供应链金融平台</footer>
    </section>
  </div>
</template>

<style scoped>
.login-page{display:grid;grid-template-columns:1.04fr 1fr;min-height:100vh;background:#fff}.login-story{position:relative;overflow:hidden;background:#143c35;color:#fff;display:flex;flex-direction:column;padding:48px clamp(36px,5vw,86px)}.story-grid{position:absolute;inset:0;opacity:.24;background-image:linear-gradient(#6d9b8215 1px,transparent 1px),linear-gradient(90deg,#6d9b8215 1px,transparent 1px);background-size:52px 52px;pointer-events:none}.login-brand{position:relative;display:flex;gap:12px;align-items:center;font-size:29px;font-weight:650;letter-spacing:2px}.login-brand .brand-mark{background:#2a594b}.login-brand .brand-dot{color:#99c9a6}.login-brand small{display:block;font-size:10px;color:#a5bfb3;letter-spacing:1.8px;margin-top:3px;font-weight:400}.story-copy{position:relative;margin-top:clamp(70px,12vh,130px)}.story-kicker{font-size:9px;letter-spacing:2px;color:#a8bdb1;display:flex;gap:10px;align-items:center}.story-kicker span{background:#9bcaa9;width:18px;height:1px}.story-copy h1{font-size:clamp(36px,3.6vw,56px);line-height:1.45;letter-spacing:1px;margin:26px 0 23px;font-weight:500}.story-copy h1 span{color:#a9d0ae}.story-copy p{font-size:12px;line-height:2.1;color:#94b0a4}.credit-illustration{width:100%;max-width:500px;height:190px;position:relative;margin:27px 0 25px}.credit-illustration svg{position:absolute;inset:0;width:100%;height:100%}.credit-origin{position:absolute;top:35px;left:0;background:#21493e;border:1px solid #47715e;border-radius:10px;width:150px;padding:16px;box-shadow:0 10px 25px #092d251a}.credit-icon{float:left;margin-right:10px;padding:8px;border:1px solid #577c65;border-radius:6px;font-size:12px;color:#b4d7b1}.credit-origin strong{font-size:12px;font-weight:500;display:block;margin-top:3px}.credit-origin small{font-size:8px;color:#9ebaa7;display:block;margin-top:5px}.credit-origin>div{font-size:8px;color:#a3c9aa;border-top:1px solid #3a5f4c;margin-top:16px;padding-top:10px}.credit-origin i{display:inline-block;background:#9bc89d;width:4px;height:4px;border-radius:50%;margin-right:5px}.credit-destination{position:absolute;right:0;display:flex;gap:11px;align-items:center;background:#22493e;border:1px solid #3c6753;padding:13px 12px;width:160px;border-radius:7px}.credit-destination.first{top:22px}.credit-destination.second{top:119px}.credit-destination>span{font-family:Consolas,monospace;font-size:11px;color:#7caa8b}.credit-destination strong{font-size:11px;font-weight:400;display:block}.credit-destination small{font-size:8px;color:#96b39f;display:block;margin-top:5px}.credit-destination>.el-icon{margin-left:auto;color:#94c6a0;font-size:10px}.story-footer{margin-top:auto;padding-top:25px;display:flex;align-items:center;gap:24px;font-size:10px;letter-spacing:1px;color:#90ad9b}.story-footer i{height:12px;width:1px;background:#3d6250}.login-form-area{position:relative;display:flex;flex-direction:column;align-items:center;justify-content:center;padding:100px 40px 70px}.login-service{position:absolute;top:42px;right:46px;display:flex;align-items:center;gap:7px;color:#8e9a9e;font-size:10px}.login-form-content{width:100%;max-width:370px}.login-form-content h2{font-size:27px;margin:14px 0 11px;letter-spacing:-.5px;font-weight:600}.login-description{font-size:11px;color:#89969e;margin-bottom:37px;line-height:1.8}.login-form-content :deep(.el-form-item){margin-bottom:23px}.login-form-content :deep(.el-input__wrapper){height:44px;background:#fcfdfd}.login-form-content :deep(.el-form-item__label){font-size:11px;color:#596b75;padding-bottom:8px}.login-submit{width:100%;display:flex;justify-content:space-between;margin-top:9px;font-size:12px}.demo-heading{display:flex;align-items:center;justify-content:space-between;border-top:1px solid #ecf0f2;margin-top:34px;padding-top:25px;font-size:11px;color:#657783}.demo-heading small{font-size:9px;color:#a3adb3}.demo-accounts{display:grid;grid-template-columns:repeat(3,1fr);gap:8px;margin-top:15px}.demo-account{position:relative;background:#fff;border:1px solid #e4e9ec;border-radius:6px;padding:12px 8px;text-align:left;color:#536574}.demo-account>span{font-size:11px;display:block}.demo-account>small{font-size:8px;color:#9ba5ad;display:block;margin-top:6px}.demo-account:hover,.demo-account.selected{border-color:#78b4a0;background:#f1f8f4}.demo-account.selected>span{color:#087f72}.demo-account>.el-icon{position:absolute;right:7px;top:10px;font-size:10px;color:#087f72}.login-hint{font-size:9px;color:#a5afb5;margin-top:15px;line-height:1.7}.login-form-area footer{position:absolute;bottom:24px;font-size:9px;letter-spacing:.5px;color:#b0b9bd}
@media(max-width:1050px){.login-story{padding:35px}.story-kicker{letter-spacing:1px;font-size:8px}.credit-destination{width:140px}.credit-origin{width:140px}.story-copy h1{font-size:38px}.login-form-area{padding:90px 32px 70px}}
@media(max-width:750px){.login-page{grid-template-columns:1fr}.login-story{padding:25px 30px}.story-copy,.credit-illustration,.story-footer{display:none}.login-brand{font-size:23px}.login-brand .brand-mark{width:35px;height:36px;font-size:21px}.login-brand small{font-size:9px}.login-form-area{min-height:calc(100vh - 88px);padding:65px 25px 65px}.login-service{top:22px;right:27px}.login-form-content h2{font-size:25px}.login-form-area footer{bottom:20px}}
</style>
