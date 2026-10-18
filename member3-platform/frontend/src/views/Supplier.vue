<script setup>
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { applyLoan, getEnterprises, getLoans, getTokens, splitToken } from '../api'
import { getUser } from '../auth'
import StatusBadge from '../components/StatusBadge.vue'
import { daysUntil, formatDate, formatMoney, shortId, unwrapList } from '../utils/format'

const ACTIVE_LOAN_STATUSES = new Set(['APPLIED', 'APPROVED', 'DISBURSED'])
const OPERABLE_TOKEN_STATUSES = new Set(['UNCIRCULATED', 'CIRCULATING'])
const user = getUser()

const tokens = ref([])
const loans = ref([])
const enterprises = ref([])
const loading = ref(false)
const loadError = ref('')
const loanDialog = ref(false)
const splitDialog = ref(false)
const splitRow = ref(null)
const loanRow = ref(null)
const submittingLoan = ref(false)
const submittingSplit = ref(false)
const assetSearch = ref('')
const assetStatus = ref('')
const loanSearch = ref('')
const loanStatus = ref('')

const loanForm = ref({
  tokenId: '',
  supplierId: user?.enterpriseId || null,
  bankId: null,
  amount: 0,
  interestRate: 4.2,
  termDays: 90,
  remark: ''
})
const splitForm = ref({ fromTokenId: '', toEnterpriseId: null, value: 0 })

const banks = computed(() => enterprises.value.filter(item => item.enterpriseType === 'BANK'))
const splitTargets = computed(() => enterprises.value.filter(item =>
  item.enterpriseType === 'SUPPLIER' && Number(item.id) !== Number(user?.enterpriseId)))
const entName = id => enterprises.value.find(item => Number(item.id) === Number(id))?.name || `企业 #${id ?? '—'}`

const activeLoansByToken = computed(() => {
  const map = new Map()
  for (const loan of loans.value) {
    if (ACTIVE_LOAN_STATUSES.has(loan.status) && !map.has(loan.tokenId)) map.set(loan.tokenId, loan)
  }
  return map
})

const totalAssetValue = computed(() => tokens.value.filter(item => !['SETTLED', 'REVOKED'].includes(item.status)).reduce((sum, item) => sum + Number(item.valueAmount || 0), 0))
const availableTokens = computed(() => tokens.value.filter(item => canSplit(item)).length)
const lockedTokens = computed(() => tokens.value.filter(item => !canSplit(item)).length)
const financingAmount = computed(() => loans.value
  .filter(item => ['APPROVED', 'DISBURSED'].includes(item.status))
  .reduce((sum, item) => sum + Number(item.amount || 0), 0))

const filteredTokens = computed(() => {
  const keyword = assetSearch.value.trim().toLowerCase()
  return tokens.value.filter(item => {
    const matchesStatus = !assetStatus.value || item.status === assetStatus.value
    const haystack = [item.tokenId, item.parentTokenId, item.slotId, item.txHash].join(' ').toLowerCase()
    return matchesStatus && (!keyword || haystack.includes(keyword))
  })
})

const filteredLoans = computed(() => {
  const keyword = loanSearch.value.trim().toLowerCase()
  return loans.value.filter(item => {
    const matchesStatus = !loanStatus.value || item.status === loanStatus.value
    const haystack = [item.applicationNo, item.tokenId, entName(item.bankId)].join(' ').toLowerCase()
    return matchesStatus && (!keyword || haystack.includes(keyword))
  })
})

function activeLoan(row) {
  return activeLoansByToken.value.get(row.tokenId)
}

function hasFutureMaturity(row) {
  const raw = Number(row?.dueTimestamp)
  if (!Number.isFinite(raw) || raw <= 0) return false
  return (raw < 10_000_000_000 ? raw * 1000 : raw) > Date.now()
}

function financingTermLimit(row) {
  const raw = Number(row?.dueTimestamp)
  if (!Number.isFinite(raw) || raw <= 0) return 0
  const dueTime = raw < 10_000_000_000 ? raw * 1000 : raw
  return Math.max(0, Math.floor((dueTime - Date.now()) / 86_400_000))
}

function canSplit(row) {
  return OPERABLE_TOKEN_STATUSES.has(row.status)
    && Number(row.valueAmount) > 0
    && hasFutureMaturity(row)
    && !activeLoan(row)
}

function canFinance(row) {
  return OPERABLE_TOKEN_STATUSES.has(row.status)
    && Number(row.valueAmount) > 0
    && financingTermLimit(row) >= 1
    && !activeLoan(row)
}

function lockReason(row) {
  const loan = activeLoan(row)
  if (loan?.status === 'APPLIED') return `融资申请 ${loan.applicationNo || ''} 待审批，凭证已临时锁定`
  if (loan?.status === 'APPROVED') return '融资申请已审批，凭证已进入质押锁定'
  if (loan?.status === 'DISBURSED') return '银行已放款，凭证锁定至到期兑付'
  if (row.status === 'PLEDGED') return '凭证处于质押状态，不能拆分或重复融资'
  if (row.status === 'SETTLED') return '凭证已完成兑付清算'
  if (row.status === 'OVERDUE') return '凭证已逾期，请联系资金方处理'
  if (Number(row.valueAmount) <= 0) return '凭证余额为 0'
  if (!hasFutureMaturity(row)) return '凭证已到期或缺少到期日，不能继续拆分或融资'
  return ''
}

function dueHint(timestamp) {
  const days = daysUntil(timestamp)
  if (days === null) return '未设置到期日'
  if (days < 0) return `已到期 ${Math.abs(days)} 天`
  if (days === 0) return '今日到期'
  return `距到期 ${days} 天`
}

async function confirmAction(message, title, type = 'warning') {
  try {
    await ElMessageBox.confirm(message, title, {
      type,
      confirmButtonText: '确认',
      cancelButtonText: '取消'
    })
    return true
  } catch {
    return false
  }
}

async function load() {
  loading.value = true
  loadError.value = ''
  try {
    const params = user?.enterpriseId ? { enterpriseId: user.enterpriseId } : {}
    const loanParams = user?.enterpriseId ? { supplierId: user.enterpriseId } : {}
    const [tokenResponse, enterpriseResponse, loanResponse] = await Promise.all([
      getTokens(params),
      getEnterprises(),
      getLoans(loanParams)
    ])
    const tokenList = unwrapList(tokenResponse)
    const loanList = unwrapList(loanResponse)
    tokens.value = user?.enterpriseId
      ? tokenList.filter(item => Number(item.enterpriseId) === Number(user.enterpriseId))
      : tokenList
    loans.value = user?.enterpriseId
      ? loanList.filter(item => Number(item.supplierId) === Number(user.enterpriseId))
      : loanList
    enterprises.value = unwrapList(enterpriseResponse)
    if (!loanForm.value.bankId && banks.value.length) loanForm.value.bankId = banks.value[0].id
  } catch (error) {
    loadError.value = error?.response?.data?.message || error?.message || '资产与融资数据加载失败，请稍后重试。'
  } finally {
    loading.value = false
  }
}

function openLoan(row) {
  if (!canFinance(row)) {
    ElMessage.warning(lockReason(row) || '当前凭证不能申请融资')
    return
  }
  loanRow.value = row
  const termLimit = financingTermLimit(row)
  loanForm.value = {
    tokenId: row.tokenId,
    supplierId: user?.enterpriseId || row.enterpriseId,
    bankId: banks.value[0]?.id || null,
    amount: Number(row.valueAmount || 0),
    interestRate: 4.2,
    termDays: Math.min(90, termLimit),
    remark: ''
  }
  loanDialog.value = true
}

function openSplit(row) {
  if (!canSplit(row)) {
    ElMessage.warning(lockReason(row) || '当前凭证不能拆分')
    return
  }
  splitRow.value = row
  splitForm.value = { fromTokenId: row.tokenId, toEnterpriseId: null, value: 0 }
  splitDialog.value = true
}

async function submitLoan() {
  const maxAmount = Number(loanRow.value?.valueAmount || 0)
  const amount = Number(loanForm.value.amount)
  if (!loanForm.value.bankId) {
    ElMessage.warning('请选择融资银行')
    return
  }
  if (!amount || amount <= 0 || amount > maxAmount) {
    ElMessage.warning(`融资金额需大于 0 且不超过 ${formatMoney(maxAmount)}`)
    return
  }
  if (Number(loanForm.value.termDays) < 1) {
    ElMessage.warning('融资期限至少为 1 天')
    return
  }
  const termLimit = financingTermLimit(loanRow.value)
  if (Number(loanForm.value.termDays) > termLimit) {
    ElMessage.warning(`融资期限不能超过凭证剩余账期 ${termLimit} 天`)
    return
  }
  const confirmed = await confirmAction(
    `以凭证 ${shortId(loanForm.value.tokenId, 12, 6)} 申请 ${formatMoney(amount)} 融资？提交后凭证将在审批期间锁定，不能拆分或重复申请。`,
    '确认融资申请',
    'info'
  )
  if (!confirmed) return

  submittingLoan.value = true
  try {
    await applyLoan(loanForm.value)
    ElMessage.success('融资申请已提交，凭证已进入待审批锁定状态')
    loanDialog.value = false
    await load()
  } catch {
    // API 拦截器统一展示错误，保留表单方便用户修正。
  } finally {
    submittingLoan.value = false
  }
}

async function submitSplit() {
  const maxValue = Number(splitRow.value?.valueAmount || 0)
  const value = Number(splitForm.value.value)
  if (!splitForm.value.toEnterpriseId) {
    ElMessage.warning('请选择接收方供应商')
    return
  }
  if (!value || value <= 0 || value > maxValue) {
    ElMessage.warning(`拆分金额需大于 0 且不超过 ${formatMoney(maxValue)}`)
    return
  }
  if (!canSplit(splitRow.value)) {
    ElMessage.warning(lockReason(splitRow.value) || '凭证状态已变化，请刷新后重试')
    return
  }

  const isTransfer = value === maxValue
  const confirmed = await confirmAction(
    `${isTransfer ? '整体转让' : '拆分'} ${formatMoney(value)} 给 ${entName(splitForm.value.toEnterpriseId)}？${isTransfer ? '当前凭证的全部权益将转移。' : `原凭证将保留 ${formatMoney(maxValue - value)}。`}`,
    isTransfer ? '确认整体转让' : '确认凭证拆分',
    'info'
  )
  if (!confirmed) return

  submittingSplit.value = true
  try {
    const response = await splitToken(splitForm.value)
    const result = response.data.data
    ElMessage.success(result?.type === 'TRANSFER'
      ? `已整体转让给 ${entName(splitForm.value.toEnterpriseId)}`
      : `拆分成功，子凭证 ${result?.child?.tokenId || '已生成'}`)
    splitDialog.value = false
    await load()
  } catch {
    // API 拦截器统一展示错误。
  } finally {
    submittingSplit.value = false
  }
}

onMounted(load)
</script>

<template>
  <section class="portal-page">
    <header class="page-header">
      <div>
        <div class="page-eyebrow">SUPPLIER · 资产运营</div>
        <h1>{{ user?.enterpriseName || '供应商工作台' }}</h1>
        <p class="page-description">管理持有的应收账款凭证，向下游无损拆分支付，或提交银行融资申请盘活资产。</p>
      </div>
      <StatusBadge status="ONLINE" label="资产账户正常" />
    </header>

    <el-alert v-if="loadError" type="error" :title="loadError" :closable="false" show-icon>
      <template #default><el-button link type="primary" @click="load">重新加载</el-button></template>
    </el-alert>

    <div class="metrics-grid" v-loading="loading">
      <article class="metric-card"><span class="metric-label">资产余额</span><strong>{{ formatMoney(totalAssetValue) }}</strong><small>{{ tokens.length }} 张持有凭证</small></article>
      <article class="metric-card"><span class="metric-label">可运营凭证</span><strong class="metric-accent">{{ availableTokens }}</strong><small>可拆分或申请融资</small></article>
      <article class="metric-card"><span class="metric-label">受限凭证</span><strong>{{ lockedTokens }}</strong><small>审批中、质押或已清算</small></article>
      <article class="metric-card"><span class="metric-label">融资中金额</span><strong>{{ formatMoney(financingAmount) }}</strong><small>{{ loans.filter(item => ACTIVE_LOAN_STATUSES.has(item.status)).length }} 笔进行中申请</small></article>
    </div>

    <div class="workflow-strip" aria-label="供应商资产运营流程">
      <div class="workflow-step"><span>1</span><div><b>接收凭证</b><small>核对金额与账期</small></div></div>
      <div class="workflow-arrow">→</div>
      <div class="workflow-step is-current"><span>2</span><div><b>选择资产用途</b><small>拆分支付或质押融资</small></div></div>
      <div class="workflow-arrow">→</div>
      <div class="workflow-step"><span>3</span><div><b>银行审批兑付</b><small>状态全程可追踪</small></div></div>
    </div>

    <article class="surface-card data-card">
      <div class="section-heading">
        <div><span class="section-kicker">MY RWA ASSETS</span><h2>我的链上资产</h2></div>
        <span class="muted">{{ filteredTokens.length }} / {{ tokens.length }} 张</span>
      </div>
      <div class="toolbar">
        <el-input v-model="assetSearch" clearable placeholder="搜索 Token ID、来源或 Slot" class="search-input" />
        <el-select v-model="assetStatus" clearable placeholder="全部状态" class="status-filter">
          <el-option label="待流转" value="UNCIRCULATED" />
          <el-option label="流转中" value="CIRCULATING" />
          <el-option label="已质押" value="PLEDGED" />
          <el-option label="已兑付" value="SETTLED" />
          <el-option label="已逾期" value="OVERDUE" />
        </el-select>
        <el-button :loading="loading" @click="load">刷新</el-button>
      </div>
      <div class="table-wrap">
        <el-table :data="filteredTokens" row-key="id" v-loading="loading" class="business-table">
          <el-table-column label="凭证" min-width="205">
            <template #default="{ row }"><div class="primary-cell"><span class="mono" :title="row.tokenId">{{ shortId(row.tokenId, 13, 7) }}</span><small>{{ row.parentTokenId ? `来源 ${shortId(row.parentTokenId, 10, 5)}` : '核心企业开立 · 根凭证' }}</small></div></template>
          </el-table-column>
          <el-table-column label="资产余额" min-width="145">
            <template #default="{ row }"><span class="money-cell">{{ formatMoney(row.valueAmount) }}</span></template>
          </el-table-column>
          <el-table-column label="到期信息" min-width="150">
            <template #default="{ row }"><div class="primary-cell"><span>{{ formatDate(row.dueTimestamp) }}</span><small :class="{ overdue: daysUntil(row.dueTimestamp) < 0 }">{{ dueHint(row.dueTimestamp) }}</small></div></template>
          </el-table-column>
          <el-table-column label="状态" min-width="120"><template #default="{ row }"><StatusBadge :status="row.status" /></template></el-table-column>
          <el-table-column label="业务限制" min-width="230">
            <template #default="{ row }"><span v-if="lockReason(row)" class="lock-reason">{{ lockReason(row) }}</span><span v-else class="available-label">可拆分 · 可融资</span></template>
          </el-table-column>
          <el-table-column label="操作" width="210" fixed="right">
            <template #default="{ row }">
              <div class="row-actions">
                <el-tooltip :content="lockReason(row) || '拆分或整体转让给下游供应商'" :disabled="canSplit(row)" placement="top">
                  <span><el-button size="small" :disabled="!canSplit(row)" @click="openSplit(row)">拆分支付</el-button></span>
                </el-tooltip>
                <el-tooltip :content="lockReason(row) || '向银行提交融资申请'" :disabled="canFinance(row)" placement="top">
                  <span><el-button size="small" type="primary" :disabled="!canFinance(row)" @click="openLoan(row)">申请融资</el-button></span>
                </el-tooltip>
              </div>
            </template>
          </el-table-column>
          <template #empty><el-empty description="暂无匹配的可用凭证" :image-size="72" /></template>
        </el-table>
      </div>
    </article>

    <article class="surface-card data-card">
      <div class="section-heading">
        <div><span class="section-kicker">FINANCING TRACKER</span><h2>融资进度</h2></div>
        <span class="muted">审批期间对应凭证自动锁定</span>
      </div>
      <div class="toolbar">
        <el-input v-model="loanSearch" clearable placeholder="搜索申请编号、Token ID 或银行" class="search-input" />
        <el-select v-model="loanStatus" clearable placeholder="全部状态" class="status-filter">
          <el-option label="待审批" value="APPLIED" />
          <el-option label="已审批" value="APPROVED" />
          <el-option label="已放款" value="DISBURSED" />
          <el-option label="已兑付" value="SETTLED" />
          <el-option label="已驳回" value="REJECTED" />
        </el-select>
      </div>
      <div class="table-wrap">
        <el-table :data="filteredLoans" row-key="id" v-loading="loading" class="business-table">
          <el-table-column label="申请编号" min-width="145"><template #default="{ row }"><span class="mono">{{ row.applicationNo || '—' }}</span></template></el-table-column>
          <el-table-column label="质押凭证" min-width="180"><template #default="{ row }"><span class="mono" :title="row.tokenId">{{ shortId(row.tokenId, 12, 6) }}</span></template></el-table-column>
          <el-table-column label="融资银行" min-width="170"><template #default="{ row }">{{ entName(row.bankId) }}</template></el-table-column>
          <el-table-column label="融资金额" min-width="140"><template #default="{ row }"><span class="money-cell">{{ formatMoney(row.amount) }}</span></template></el-table-column>
          <el-table-column label="条件" min-width="140"><template #default="{ row }"><div class="primary-cell"><span>{{ row.interestRate }}% 年化</span><small>{{ row.termDays }} 天</small></div></template></el-table-column>
          <el-table-column label="状态" width="120"><template #default="{ row }"><StatusBadge :status="row.status" /></template></el-table-column>
          <template #empty><el-empty description="尚未提交融资申请" :image-size="72" /></template>
        </el-table>
      </div>
    </article>
  </section>

  <el-dialog v-model="splitDialog" title="凭证拆分支付" width="580px" :close-on-click-modal="false">
    <div class="dialog-intro success-intro"><b>金额守恒</b><span>子凭证继承原凭证的 Slot、到期日与核心企业信用，拆分金额与剩余金额之和始终等于原余额。</span></div>
    <el-form label-position="top">
      <div class="asset-summary">
        <div><span>原凭证</span><b class="mono">{{ shortId(splitForm.fromTokenId, 14, 7) }}</b></div>
        <div><span>可拆分余额</span><b>{{ formatMoney(splitRow?.valueAmount) }}</b></div>
      </div>
      <el-form-item label="接收方供应商" required>
        <el-select v-model="splitForm.toEnterpriseId" placeholder="选择下级供应商" style="width:100%">
          <el-option v-for="target in splitTargets" :key="target.id" :label="target.name" :value="target.id" />
        </el-select>
      </el-form-item>
      <el-form-item label="拆分金额（元）" required>
        <el-input-number v-model="splitForm.value" :min="0.01" :max="Number(splitRow?.valueAmount || 0)" :precision="2" :step="10000" controls-position="right" style="width:100%" />
        <div class="field-help">输入全部余额将执行整体转让；部分拆分会生成新的子凭证。</div>
      </el-form-item>
    </el-form>
    <template #footer><el-button :disabled="submittingSplit" @click="splitDialog = false">取消</el-button><el-button type="primary" :loading="submittingSplit" @click="submitSplit">确认拆分</el-button></template>
  </el-dialog>

  <el-dialog v-model="loanDialog" title="凭证质押融资" width="600px" :close-on-click-modal="false">
    <div class="dialog-intro"><b>申请说明</b><span>提交后凭证会进入待审批锁定，银行审批通过后状态变为已质押，期间不可拆分或重复融资。</span></div>
    <div class="asset-summary">
      <div><span>质押凭证</span><b class="mono">{{ shortId(loanForm.tokenId, 14, 7) }}</b></div>
      <div><span>凭证余额</span><b>{{ formatMoney(loanRow?.valueAmount) }}</b></div>
    </div>
    <el-form label-position="top">
      <el-form-item label="融资银行" required>
        <el-select v-model="loanForm.bankId" style="width:100%" placeholder="选择资金方"><el-option v-for="item in banks" :key="item.id" :label="item.name" :value="item.id" /></el-select>
      </el-form-item>
      <div class="form-grid">
        <el-form-item label="融资金额（元）" required><el-input-number v-model="loanForm.amount" :min="0.01" :max="Number(loanRow?.valueAmount || 0)" :precision="2" :step="10000" controls-position="right" style="width:100%" /></el-form-item>
        <el-form-item label="融资期限（天）" required>
          <el-input-number v-model="loanForm.termDays" :min="1" :max="financingTermLimit(loanRow)" controls-position="right" style="width:100%" />
          <div class="field-help">不得超过凭证剩余账期 {{ financingTermLimit(loanRow) }} 天</div>
        </el-form-item>
      </div>
      <el-form-item label="意向年化利率（%）"><el-input-number v-model="loanForm.interestRate" :min="0" :max="100" :precision="2" controls-position="right" style="width:100%" /></el-form-item>
      <el-form-item label="申请备注"><el-input v-model="loanForm.remark" type="textarea" :rows="3" maxlength="300" show-word-limit placeholder="可填写资金用途或补充说明" /></el-form-item>
    </el-form>
    <template #footer><el-button :disabled="submittingLoan" @click="loanDialog = false">取消</el-button><el-button type="primary" :loading="submittingLoan" @click="submitLoan">提交融资申请</el-button></template>
  </el-dialog>
</template>

<style scoped>
.portal-page { display: grid; gap: 20px; }
.page-header { align-items: flex-end; margin-bottom: 0; }
.metrics-grid, .workflow-strip { margin-bottom: 0; }
.surface-card { margin-bottom: 0; padding: 22px 24px; }
.page-header h1 { margin: 5px 0 7px; color: #13233a; font-size: clamp(26px, 3vw, 34px); letter-spacing: -.5px; }
.metric-card { display: flex; flex-direction: column; min-height: 126px; }
.metric-card strong { margin: 12px 0 7px; color: #14243b; font-size: clamp(23px, 2.5vw, 31px); line-height: 1; }
.metric-card small, .metric-label { color: #6c788b; font-size: 12px; }
.metric-label { font-weight: 650; }
.metric-card .metric-accent { color: #0b8176; }
.workflow-step { display: flex; align-items: center; gap: 10px; min-width: 0; }
.workflow-step > span { display: grid; place-items: center; width: 30px; height: 30px; flex: 0 0 auto; border-radius: 50%; color: #0b8176; background: #e9f7f4; border: 1px solid #bfe5df; font-weight: 750; }
.workflow-step div { display: grid; gap: 2px; }
.workflow-step b { color: #23334b; font-size: 13px; }
.workflow-step small { color: #7b8798; white-space: nowrap; }
.workflow-arrow { color: #a7b1bf; }
.section-heading { display: flex; justify-content: space-between; align-items: flex-start; gap: 16px; margin-bottom: 18px; }
.section-heading h2 { margin: 3px 0 0; color: #17283f; font-size: 18px; }
.section-kicker { color: #0b8176; font-size: 10px; font-weight: 750; letter-spacing: .12em; }
.toolbar { justify-content: flex-start; margin-bottom: 14px; }
.search-input { width: min(360px, 100%); }
.status-filter { width: 150px; }
.table-wrap { width: 100%; overflow: hidden; }
.primary-cell { display: grid; gap: 4px; }
.primary-cell span { color: #24354d; }
.primary-cell small { color: #8792a3; }
.primary-cell small.overdue { color: #b4233c; }
.money-cell { color: #17283f; font-weight: 700; font-variant-numeric: tabular-nums; }
.lock-reason { display: inline-block; color: #9a5b0a; font-size: 12px; line-height: 1.45; }
.available-label { color: #087d72; font-size: 12px; font-weight: 650; }
.row-actions { display: flex; align-items: center; gap: 8px; }
.dialog-intro { display: grid; gap: 4px; margin: -4px 0 18px; padding: 12px 14px; border-radius: 9px; color: #546277; background: #f5f8fa; font-size: 13px; line-height: 1.55; }
.dialog-intro b { color: #273950; }
.success-intro { background: #eef9f7; }
.asset-summary { display: grid; grid-template-columns: 1fr 1fr; gap: 10px; margin-bottom: 18px; }
.asset-summary > div { display: grid; gap: 5px; padding: 12px; border: 1px solid #e6ebf0; border-radius: 9px; background: #fbfcfd; }
.asset-summary span { color: #7a8798; font-size: 11px; }
.asset-summary b { color: #20324a; font-size: 14px; }
.form-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 14px; }
.field-help { margin-top: 6px; color: #7d899a; font-size: 12px; }

:deep(.business-table) { --el-table-header-bg-color: #f7f9fb; --el-table-border-color: #edf0f4; }
:deep(.business-table th.el-table__cell) { color: #637085; font-size: 12px; font-weight: 700; }
:deep(.business-table td.el-table__cell) { padding: 13px 0; }
:deep(.el-dialog) { max-width: calc(100vw - 28px); border-radius: 14px; }

@media (max-width: 760px) {
  .page-header { align-items: flex-start; }
  .workflow-arrow { display: none; }
  .workflow-step { width: 100%; }
  .toolbar { align-items: stretch; }
  .search-input, .status-filter { width: 100%; }
  .form-grid, .asset-summary { grid-template-columns: 1fr; }
}
</style>
