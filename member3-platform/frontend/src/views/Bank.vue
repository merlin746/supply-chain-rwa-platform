<script setup>
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  approveLoan,
  disburseLoan,
  getChainStatus,
  getEnterprises,
  getLoans,
  getTokens,
  rejectLoan,
  settleLoan,
  verifyToken
} from '../api'
import { getUser } from '../auth'
import StatusBadge from '../components/StatusBadge.vue'
import { daysUntil, formatDate, formatMoney, shortId, unwrapList } from '../utils/format'

const user = getUser()
const loans = ref([])
const tokens = ref([])
const enterprises = ref([])
const chain = ref({})
const loading = ref(false)
const loadError = ref('')
const search = ref('')
const statusFilter = ref('')
const actionLoading = ref({})

const verifyDialog = ref(false)
const verifyData = ref(null)
const verifyLoading = ref(false)
const verifyError = ref('')
const verifyTokenId = ref('')

const tokenById = computed(() => new Map(tokens.value.map(item => [item.tokenId, item])))
const pendingCount = computed(() => loans.value.filter(item => item.status === 'APPLIED').length)
const approvedCount = computed(() => loans.value.filter(item => item.status === 'APPROVED').length)
const disbursedAmount = computed(() => loans.value
  .filter(item => item.status === 'DISBURSED')
  .reduce((sum, item) => sum + Number(item.amount || 0), 0))
const settledAmount = computed(() => loans.value
  .filter(item => item.status === 'SETTLED')
  .reduce((sum, item) => sum + Number(item.amount || 0), 0))
const verifyPassed = computed(() => verifyData.value?.verified === true)
const chainMode = computed(() => chain.value.mode === 'ONCHAIN' ? 'ONCHAIN' : 'OFFCHAIN')
const chainStatus = computed(() => {
  if (chain.value.mode !== 'ONCHAIN') return 'LOCAL_DEMO'
  return chain.value.verified === true ? 'ONLINE' : 'OFFLINE'
})
const entName = id => enterprises.value.find(item => Number(item.id) === Number(id))?.name || `企业 #${id ?? '—'}`

const filteredLoans = computed(() => {
  const keyword = search.value.trim().toLowerCase()
  return loans.value.filter(item => {
    const matchesStatus = !statusFilter.value || item.status === statusFilter.value
    const haystack = [item.applicationNo, item.tokenId, entName(item.supplierId)].join(' ').toLowerCase()
    return matchesStatus && (!keyword || haystack.includes(keyword))
  })
})

function setActionLoading(key, value) {
  actionLoading.value = { ...actionLoading.value, [key]: value }
}

function tokenFor(row) {
  return tokenById.value.get(row.tokenId)
}

function isMatured(row) {
  const dueTimestamp = tokenFor(row)?.dueTimestamp
  const days = daysUntil(dueTimestamp)
  return days === null || days <= 0
}

function maturityHint(row) {
  const token = tokenFor(row)
  if (!token?.dueTimestamp) return '到期日以服务端校验为准'
  const days = daysUntil(token.dueTimestamp)
  if (days < 0) return `已到期 ${Math.abs(days)} 天，可发起兑付`
  if (days === 0) return '今日到期，可发起兑付'
  return `距到期 ${days} 天，暂不可兑付`
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
    const loanParams = user?.enterpriseId ? { bankId: user.enterpriseId } : {}
    const [loanResponse, chainResponse, enterpriseResponse, tokenResponse] = await Promise.all([
      getLoans(loanParams),
      getChainStatus(),
      getEnterprises(),
      getTokens()
    ])
    const loanList = unwrapList(loanResponse)
    loans.value = user?.enterpriseId
      ? loanList.filter(item => Number(item.bankId) === Number(user.enterpriseId))
      : loanList
    chain.value = chainResponse?.data?.data || {}
    enterprises.value = unwrapList(enterpriseResponse)
    tokens.value = unwrapList(tokenResponse)
  } catch (error) {
    loadError.value = error?.response?.data?.message || error?.message || '融资与链状态加载失败，请稍后重试。'
  } finally {
    loading.value = false
  }
}

async function approve(row) {
  const confirmed = await confirmAction(
    `批准 ${entName(row.supplierId)} 的 ${formatMoney(row.amount)} 融资申请？审批后凭证 ${shortId(row.tokenId, 12, 6)} 将进入质押状态。`,
    '确认审批通过',
    'info'
  )
  if (!confirmed) return
  const key = `approve-${row.id}`
  setActionLoading(key, true)
  try {
    await approveLoan(row.id)
    ElMessage.success('审批完成，凭证已进入质押状态')
    await load()
  } catch {
    // API 拦截器统一展示错误。
  } finally {
    setActionLoading(key, false)
  }
}

async function disburse(row) {
  const confirmed = await confirmAction(
    `确认向 ${entName(row.supplierId)} 放款 ${formatMoney(row.amount)}？放款后凭证保持质押，直至到期兑付。`,
    '确认执行放款'
  )
  if (!confirmed) return
  const key = `disburse-${row.id}`
  setActionLoading(key, true)
  try {
    await disburseLoan(row.id)
    ElMessage.success('放款成功，融资进入存续期')
    await load()
  } catch {
    // API 拦截器统一展示错误。
  } finally {
    setActionLoading(key, false)
  }
}

async function settle(row) {
  if (!isMatured(row)) {
    ElMessage.warning(maturityHint(row))
    return
  }
  const confirmed = await confirmAction(
    `确认对申请 ${row.applicationNo} 执行到期兑付？完成后凭证将进入 SETTLED 终态，此操作不可撤销。`,
    '确认到期兑付'
  )
  if (!confirmed) return
  const key = `settle-${row.id}`
  setActionLoading(key, true)
  try {
    await settleLoan(row.id)
    ElMessage.success('到期兑付完成，凭证已清算')
    await load()
  } catch {
    // API 拦截器统一展示错误。
  } finally {
    setActionLoading(key, false)
  }
}

async function reject(row) {
  const confirmed = await confirmAction(
    `确定驳回 ${entName(row.supplierId)} 的融资申请 ${row.applicationNo}？驳回后凭证将解除申请锁定。`,
    '确认驳回申请'
  )
  if (!confirmed) return
  const key = `reject-${row.id}`
  setActionLoading(key, true)
  try {
    await rejectLoan(row.id)
    ElMessage.success('融资申请已驳回')
    await load()
  } catch {
    // API 拦截器统一展示错误。
  } finally {
    setActionLoading(key, false)
  }
}

async function verify(row) {
  verifyDialog.value = true
  verifyLoading.value = true
  verifyData.value = null
  verifyError.value = ''
  verifyTokenId.value = row.tokenId
  try {
    const response = await verifyToken(row.tokenId)
    const data = response?.data?.data || null
    verifyData.value = data
    if (data?.verified !== true) {
      verifyError.value = data?.note || data?.chainError || '凭证真实性核验未通过，请核对链上数据与业务台账。'
    }
  } catch (error) {
    verifyData.value = error?.response?.data?.data || null
    verifyError.value = error?.response?.data?.message || error?.message || '核验请求失败，请检查网络或凭证状态后重试。'
  } finally {
    verifyLoading.value = false
  }
}

onMounted(load)
</script>

<template>
  <section class="portal-page">
    <header class="page-header">
      <div>
        <div class="page-eyebrow">FINANCIAL INSTITUTION · 融资风控</div>
        <h1>{{ user?.enterpriseName || '银行业务工作台' }}</h1>
        <p class="page-description">核验凭证贸易背景和链上权属，完成融资审批、放款及到期兑付的全生命周期管理。</p>
      </div>
      <div class="header-status"><StatusBadge :status="chainStatus" /><StatusBadge :status="chainMode" /></div>
    </header>

    <el-alert v-if="loadError" type="error" :title="loadError" :closable="false" show-icon>
      <template #default><el-button link type="primary" @click="load">重新加载</el-button></template>
    </el-alert>

    <div class="metrics-grid" v-loading="loading">
      <article class="metric-card"><span class="metric-label">待审批</span><strong class="metric-warning">{{ pendingCount }}</strong><small>等待风控核验与决策</small></article>
      <article class="metric-card"><span class="metric-label">待放款</span><strong>{{ approvedCount }}</strong><small>已审批通过的申请</small></article>
      <article class="metric-card"><span class="metric-label">存续融资</span><strong class="metric-accent">{{ formatMoney(disbursedAmount) }}</strong><small>已放款、待到期兑付</small></article>
      <article class="metric-card"><span class="metric-label">累计兑付</span><strong>{{ formatMoney(settledAmount) }}</strong><small>已完成清算的金额</small></article>
    </div>

    <div class="workflow-strip" aria-label="银行融资处理流程">
      <div class="workflow-step is-current"><span>1</span><div><b>凭证溯源核验</b><small>贸易背景与链上数据比对</small></div></div>
      <div class="workflow-arrow">→</div>
      <div class="workflow-step"><span>2</span><div><b>授信审批放款</b><small>锁定凭证并进入存续期</small></div></div>
      <div class="workflow-arrow">→</div>
      <div class="workflow-step"><span>3</span><div><b>到期兑付清算</b><small>凭证进入不可逆终态</small></div></div>
    </div>

    <article class="surface-card chain-card">
      <div class="section-heading">
        <div><span class="section-kicker">CHAIN INFRASTRUCTURE</span><h2>链节点与合约状态</h2></div>
        <el-button link type="primary" :loading="loading" @click="load">刷新状态</el-button>
      </div>
      <div class="chain-grid">
        <div class="chain-item"><span>运行模式</span><StatusBadge :status="chainMode" /></div>
        <div class="chain-item"><span>节点连接</span><StatusBadge :status="chainStatus" :label="chainMode === 'OFFCHAIN' ? '本地演示可用' : (chain.verified === true ? '连接正常' : '连接异常')" /></div>
        <div class="chain-item"><span>当前区块</span><b class="mono">{{ chain.blockNumber ?? '—' }}</b></div>
        <div class="chain-item"><span>核心合约</span><StatusBadge :status="chain.contractExists ? 'DEPLOYED' : 'UNCONFIGURED'" /></div>
      </div>
      <div v-if="chain.error" class="chain-error">{{ chain.error }}</div>
      <div class="client-version mono" :title="chain.clientVersion">{{ chain.clientVersion || '节点版本待获取' }}</div>
    </article>

    <article class="surface-card data-card">
      <div class="section-heading">
        <div><span class="section-kicker">FINANCING PIPELINE</span><h2>融资申请处理</h2></div>
        <span class="muted">{{ filteredLoans.length }} / {{ loans.length }} 笔</span>
      </div>
      <div class="toolbar">
        <el-input v-model="search" clearable placeholder="搜索申请编号、Token ID 或供应商" class="search-input" />
        <el-select v-model="statusFilter" clearable placeholder="全部状态" class="status-filter">
          <el-option label="待审批" value="APPLIED" />
          <el-option label="已审批" value="APPROVED" />
          <el-option label="已放款" value="DISBURSED" />
          <el-option label="已兑付" value="SETTLED" />
          <el-option label="已驳回" value="REJECTED" />
        </el-select>
        <el-button :loading="loading" @click="load">刷新</el-button>
      </div>
      <div class="table-wrap">
        <el-table :data="filteredLoans" row-key="id" v-loading="loading" class="business-table">
          <el-table-column label="申请" min-width="155"><template #default="{ row }"><div class="primary-cell"><span class="mono">{{ row.applicationNo || '—' }}</span><small>{{ formatDate(row.createdAt) }} 提交</small></div></template></el-table-column>
          <el-table-column label="供应商" min-width="170"><template #default="{ row }">{{ entName(row.supplierId) }}</template></el-table-column>
          <el-table-column label="质押凭证" min-width="185"><template #default="{ row }"><div class="primary-cell"><span class="mono" :title="row.tokenId">{{ shortId(row.tokenId, 12, 6) }}</span><small>{{ tokenFor(row) ? formatDate(tokenFor(row).dueTimestamp) + ' 到期' : '凭证详情待同步' }}</small></div></template></el-table-column>
          <el-table-column label="融资条件" min-width="160"><template #default="{ row }"><div class="primary-cell"><b class="money-cell">{{ formatMoney(row.amount) }}</b><small>{{ row.interestRate }}% · {{ row.termDays }} 天</small></div></template></el-table-column>
          <el-table-column label="状态" width="120"><template #default="{ row }"><StatusBadge :status="row.status" /></template></el-table-column>
          <el-table-column label="业务操作" width="330" fixed="right">
            <template #default="{ row }">
              <div class="row-actions">
                <el-button size="small" @click="verify(row)">溯源核验</el-button>
                <template v-if="row.status === 'APPLIED'">
                  <el-button type="primary" size="small" :loading="actionLoading[`approve-${row.id}`]" @click="approve(row)">通过</el-button>
                  <el-button type="danger" link :disabled="actionLoading[`approve-${row.id}`]" @click="reject(row)">驳回</el-button>
                </template>
                <el-button v-else-if="row.status === 'APPROVED'" type="primary" size="small" :loading="actionLoading[`disburse-${row.id}`]" @click="disburse(row)">确认放款</el-button>
                <el-tooltip v-else-if="row.status === 'DISBURSED'" :content="maturityHint(row)" placement="top">
                  <span><el-button type="primary" plain size="small" :disabled="!isMatured(row)" :loading="actionLoading[`settle-${row.id}`]" @click="settle(row)">到期兑付</el-button></span>
                </el-tooltip>
                <span v-else class="muted">流程已结束</span>
              </div>
            </template>
          </el-table-column>
          <template #empty><el-empty description="暂无匹配的融资申请" :image-size="72" /></template>
        </el-table>
      </div>
    </article>
  </section>

  <el-dialog v-model="verifyDialog" title="凭证真实性与溯源核验" width="720px" :close-on-click-modal="false">
    <div v-loading="verifyLoading" class="verify-panel">
      <div v-if="verifyLoading" class="verify-placeholder">正在读取凭证链路并比对业务台账，请稍候…</div>

      <template v-else-if="verifyData || verifyError">
        <el-alert
          v-if="verifyPassed"
          type="success"
          :closable="false"
          show-icon
          title="核验通过"
          :description="verifyData.note || '凭证数据与业务台账一致。'"
        />
        <el-alert
          v-else
          type="error"
          :closable="false"
          show-icon
          title="核验未通过"
          :description="verifyError || verifyData?.note || '凭证数据存在异常，请暂停审批并复核。'"
        />

        <div class="verify-summary">
          <div><span>核验对象</span><b class="mono">{{ shortId(verifyTokenId, 15, 8) }}</b></div>
          <div><span>核验模式</span><StatusBadge :status="verifyData?.mode || 'OFFCHAIN'" /></div>
          <div><span>根凭证</span><b class="mono">{{ shortId(verifyData?.rootTokenId, 12, 6) }}</b></div>
          <div><span>流转深度</span><b>{{ verifyData?.chainDepth ?? '—' }} 级</b></div>
          <div v-if="verifyData?.mode === 'ONCHAIN'"><span>链上比对</span><StatusBadge :status="verifyData?.chainVerified ? 'VERIFIED' : 'FAILED'" /></div>
        </div>

        <div v-if="verifyData?.chain?.length" class="trace-section">
          <h3>凭证流转链路</h3>
          <ol class="trace-list">
            <li v-for="(hop, index) in verifyData.chain" :key="`${hop.tokenId}-${index}`">
              <span class="trace-index">{{ index + 1 }}</span>
              <div class="trace-content">
                <div class="trace-title"><b>{{ hop.holder || '未知持有方' }}</b><StatusBadge :status="hop.status" /></div>
                <div class="trace-meta"><span class="mono">{{ shortId(hop.tokenId, 13, 7) }}</span><span>{{ formatMoney(hop.valueAmount) }}</span><span>Slot {{ shortId(hop.slotId, 10, 5) }}</span></div>
                <div class="trace-hash mono" :title="hop.txHash">{{ hop.txHash || '暂无交易哈希' }}</div>
              </div>
            </li>
          </ol>
        </div>

        <div v-if="verifyData?.invoice" class="invoice-proof">
          <h3>底层贸易背景</h3>
          <div class="proof-grid">
            <div><span>发票代码</span><b>{{ verifyData.invoice.invoiceCode || '—' }}</b></div>
            <div><span>发票号码</span><b>{{ verifyData.invoice.invoiceNumber || '—' }}</b></div>
            <div><span>发票金额</span><b>{{ formatMoney(verifyData.invoice.amount) }}</b></div>
            <div><span>到期日</span><b>{{ formatDate(verifyData.invoice.dueDate) }}</b></div>
          </div>
        </div>
      </template>

      <el-empty v-else description="暂无核验数据" :image-size="72" />
    </div>
    <template #footer>
      <el-button @click="verifyDialog = false">关闭</el-button>
      <el-button type="primary" plain :loading="verifyLoading" @click="verify({ tokenId: verifyTokenId })">重新核验</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.portal-page { display: grid; gap: 20px; }
.page-header { align-items: flex-end; margin-bottom: 0; }
.metrics-grid, .workflow-strip { margin-bottom: 0; }
.surface-card { margin-bottom: 0; padding: 22px 24px; }
.page-header h1 { margin: 5px 0 7px; color: #13233a; font-size: clamp(26px, 3vw, 34px); letter-spacing: -.5px; }
.header-status { display: flex; gap: 8px; flex-wrap: wrap; justify-content: flex-end; }
.metric-card { display: flex; flex-direction: column; min-height: 126px; }
.metric-card strong { margin: 12px 0 7px; color: #14243b; font-size: clamp(23px, 2.5vw, 31px); line-height: 1; }
.metric-card small, .metric-label { color: #6c788b; font-size: 12px; }
.metric-label { font-weight: 650; }
.metric-card .metric-accent { color: #0b8176; }
.metric-card .metric-warning { color: #a15c06; }
.workflow-step { display: flex; align-items: center; gap: 10px; min-width: 0; }
.workflow-step > span { display: grid; place-items: center; width: 30px; height: 30px; flex: 0 0 auto; border-radius: 50%; color: #0b8176; background: #e9f7f4; border: 1px solid #bfe5df; font-weight: 750; }
.workflow-step div { display: grid; gap: 2px; }
.workflow-step b { color: #23334b; font-size: 13px; }
.workflow-step small { color: #7b8798; white-space: nowrap; }
.workflow-arrow { color: #a7b1bf; }
.section-heading { display: flex; justify-content: space-between; align-items: flex-start; gap: 16px; margin-bottom: 18px; }
.section-heading h2 { margin: 3px 0 0; color: #17283f; font-size: 18px; }
.section-kicker { color: #0b8176; font-size: 10px; font-weight: 750; letter-spacing: .12em; }
.chain-grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 10px; }
.chain-item { display: flex; min-height: 68px; flex-direction: column; justify-content: space-between; align-items: flex-start; gap: 9px; padding: 11px 13px; border: 1px solid #e7ebf0; border-radius: 10px; background: #fafbfd; }
.chain-item > span { color: #768295; font-size: 11px; }
.chain-item > b { color: #26374e; font-size: 14px; }
.client-version { margin-top: 12px; overflow: hidden; color: #7a8798; font-size: 11px; text-overflow: ellipsis; white-space: nowrap; }
.chain-error { margin-top: 12px; padding: 9px 11px; border-radius: 7px; color: #ad263e; background: #fff1f3; font-size: 12px; }
.toolbar { justify-content: flex-start; margin-bottom: 14px; }
.search-input { width: min(370px, 100%); }
.status-filter { width: 150px; }
.table-wrap { width: 100%; overflow: hidden; }
.primary-cell { display: grid; gap: 4px; }
.primary-cell > span, .primary-cell > b { color: #24354d; }
.primary-cell small { color: #8792a3; }
.money-cell { color: #17283f; font-weight: 700; font-variant-numeric: tabular-nums; }
.row-actions { display: flex; align-items: center; gap: 6px; white-space: nowrap; }
.verify-panel { min-height: 170px; }
.verify-placeholder { display: grid; min-height: 150px; place-items: center; color: #7b8798; font-size: 13px; }
.verify-summary { display: grid; grid-template-columns: repeat(5, minmax(0, 1fr)); gap: 9px; margin: 16px 0; }
.verify-summary > div { display: flex; min-height: 70px; flex-direction: column; justify-content: space-between; align-items: flex-start; gap: 8px; padding: 11px; border: 1px solid #e7ebf0; border-radius: 9px; background: #fafbfd; }
.verify-summary span { color: #788597; font-size: 11px; }
.verify-summary b { max-width: 100%; overflow: hidden; color: #24364e; font-size: 13px; text-overflow: ellipsis; }
.trace-section h3, .invoice-proof h3 { margin: 20px 0 12px; color: #26374f; font-size: 14px; }
.trace-list { margin: 0; padding: 0; list-style: none; }
.trace-list li { position: relative; display: flex; gap: 12px; padding-bottom: 15px; }
.trace-list li:not(:last-child)::after { position: absolute; top: 25px; bottom: 0; left: 12px; width: 1px; background: #d8e2e7; content: ''; }
.trace-index { z-index: 1; display: grid; width: 25px; height: 25px; flex: 0 0 auto; place-items: center; border-radius: 50%; color: #fff; background: #0f8a7e; font-size: 11px; font-weight: 700; }
.trace-content { min-width: 0; flex: 1; padding: 0 0 13px; border-bottom: 1px solid #edf0f3; }
.trace-title { display: flex; justify-content: space-between; align-items: center; gap: 10px; }
.trace-title b { color: #23344c; }
.trace-meta { display: flex; flex-wrap: wrap; gap: 7px 16px; margin-top: 7px; color: #556378; font-size: 12px; }
.trace-hash { margin-top: 6px; overflow: hidden; color: #8a95a4; font-size: 10px; text-overflow: ellipsis; white-space: nowrap; }
.proof-grid { display: grid; grid-template-columns: 1fr 1fr; border: 1px solid #e6ebef; border-radius: 10px; overflow: hidden; }
.proof-grid > div { display: grid; gap: 5px; padding: 12px 14px; border-bottom: 1px solid #e9edf1; }
.proof-grid > div:nth-child(odd) { border-right: 1px solid #e9edf1; }
.proof-grid > div:nth-last-child(-n + 2) { border-bottom: 0; }
.proof-grid span { color: #7c8898; font-size: 11px; }
.proof-grid b { color: #26374e; font-size: 13px; }

:deep(.business-table) { --el-table-header-bg-color: #f7f9fb; --el-table-border-color: #edf0f4; }
:deep(.business-table th.el-table__cell) { color: #637085; font-size: 12px; font-weight: 700; }
:deep(.business-table td.el-table__cell) { padding: 13px 0; }
:deep(.el-dialog) { max-width: calc(100vw - 28px); border-radius: 14px; }

@media (max-width: 900px) {
  .chain-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .verify-summary { grid-template-columns: repeat(2, minmax(0, 1fr)); }
}

@media (max-width: 760px) {
  .page-header { align-items: flex-start; }
  .header-status { justify-content: flex-start; }
  .workflow-arrow { display: none; }
  .workflow-step { width: 100%; }
  .toolbar { align-items: stretch; }
  .search-input, .status-filter { width: 100%; }
  .chain-grid, .proof-grid { grid-template-columns: 1fr; }
  .proof-grid > div, .proof-grid > div:nth-child(odd), .proof-grid > div:nth-last-child(-n + 2) { border-right: 0; border-bottom: 1px solid #e9edf1; }
  .proof-grid > div:last-child { border-bottom: 0; }
}
</style>
