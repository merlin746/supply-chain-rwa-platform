<script setup>
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { auditInvoice, createInvoice, getEnterprises, getInvoices, getTokens, mintToken } from '../api'
import { getUser } from '../auth'
import StatusBadge from '../components/StatusBadge.vue'
import { formatDate, formatMoney, shortId, unwrapList } from '../utils/format'

const user = getUser()
const invoices = ref([])
const tokens = ref([])
const enterprises = ref([])
const loading = ref(false)
const loadError = ref('')
const dialog = ref(false)
const submitting = ref(false)
const actionLoading = ref({})
const invoiceSearch = ref('')
const invoiceStatus = ref('')
const tokenSearch = ref('')
const tokenStatus = ref('')

function localDate(offsetDays = 0) {
  const date = new Date()
  date.setDate(date.getDate() + offsetDays)
  const year = date.getFullYear()
  const month = String(date.getMonth() + 1).padStart(2, '0')
  const day = String(date.getDate()).padStart(2, '0')
  return `${year}-${month}-${day}`
}

function emptyForm() {
  return {
    invoiceCode: '',
    invoiceNumber: '',
    coreEnterpriseId: user?.enterpriseId || null,
    supplierId: null,
    amount: 100000,
    issueDate: localDate(),
    dueDate: localDate(90)
  }
}

const form = ref(emptyForm())
const core = computed(() => enterprises.value.find(item => item.id === user?.enterpriseId)
  || enterprises.value.find(item => item.enterpriseType === 'CORE_ENTERPRISE'))
const suppliers = computed(() => enterprises.value.filter(item => item.enterpriseType === 'SUPPLIER'))
const entName = id => enterprises.value.find(item => item.id === id)?.name || `企业 #${id ?? '—'}`

const creditLimit = computed(() => Number(core.value?.creditLimit || 0))
const creditUsed = computed(() => Number(core.value?.creditUsed || 0))
const creditAvailable = computed(() => Math.max(creditLimit.value - creditUsed.value, 0))
const creditPercent = computed(() => creditLimit.value
  ? Math.min(100, Number((creditUsed.value / creditLimit.value * 100).toFixed(2)))
  : 0)
const pendingCount = computed(() => invoices.value.filter(item => item.status === 'PENDING').length)
const approvedCount = computed(() => invoices.value.filter(item => item.status === 'APPROVED').length)
const tokenValue = computed(() => tokens.value.filter(item => !['SETTLED', 'REVOKED'].includes(item.status)).reduce((sum, item) => sum + Number(item.valueAmount || 0), 0))

const filteredInvoices = computed(() => {
  const keyword = invoiceSearch.value.trim().toLowerCase()
  return invoices.value.filter(item => {
    const matchesStatus = !invoiceStatus.value || item.status === invoiceStatus.value
    const haystack = [item.invoiceCode, item.invoiceNumber, entName(item.supplierId)].join(' ').toLowerCase()
    return matchesStatus && (!keyword || haystack.includes(keyword))
  })
})

const filteredTokens = computed(() => {
  const keyword = tokenSearch.value.trim().toLowerCase()
  return tokens.value.filter(item => {
    const matchesStatus = !tokenStatus.value || item.status === tokenStatus.value
    const haystack = [item.tokenId, item.slotId, item.txHash, entName(item.enterpriseId)].join(' ').toLowerCase()
    return matchesStatus && (!keyword || haystack.includes(keyword))
  })
})

function setActionLoading(key, value) {
  actionLoading.value = { ...actionLoading.value, [key]: value }
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
    const [invoiceResponse, tokenResponse, enterpriseResponse] = await Promise.all([
      getInvoices(),
      getTokens(),
      getEnterprises()
    ])
    invoices.value = unwrapList(invoiceResponse)
    tokens.value = unwrapList(tokenResponse)
    enterprises.value = unwrapList(enterpriseResponse)
    if (core.value) form.value.coreEnterpriseId = core.value.id
    if (!form.value.supplierId && suppliers.value.length) form.value.supplierId = suppliers.value[0].id
  } catch (error) {
    loadError.value = error?.response?.data?.message || error?.message || '业务数据加载失败，请稍后重试。'
  } finally {
    loading.value = false
  }
}

function openCreate() {
  form.value = emptyForm()
  if (core.value) form.value.coreEnterpriseId = core.value.id
  if (suppliers.value.length) form.value.supplierId = suppliers.value[0].id
  dialog.value = true
}

async function submit() {
  if (!form.value.invoiceCode.trim() || !form.value.invoiceNumber.trim()) {
    ElMessage.warning('请填写完整的发票代码与号码')
    return
  }
  if (!form.value.supplierId || !form.value.issueDate || !form.value.dueDate) {
    ElMessage.warning('请选择供应商、开票日与到期日')
    return
  }
  if (Number(form.value.amount) <= 0) {
    ElMessage.warning('发票金额必须大于 0')
    return
  }
  if (new Date(form.value.dueDate) <= new Date(form.value.issueDate)) {
    ElMessage.warning('到期日必须晚于开票日')
    return
  }

  submitting.value = true
  try {
    await createInvoice(form.value)
    ElMessage.success('发票已提交，已进入待审核队列')
    dialog.value = false
    await load()
  } catch {
    // API 拦截器统一展示服务端错误，页面保留当前表单方便重试。
  } finally {
    submitting.value = false
  }
}

async function audit(row, action) {
  const approving = action === 'APPROVE'
  const label = approving ? '通过' : '驳回'
  const confirmed = await confirmAction(
    `${label}发票 ${row.invoiceCode}（${formatMoney(row.amount)}）？${approving ? '通过后可据此开立 RWA 凭证。' : '驳回后该发票将不能用于确权。'}`,
    `${label}发票`,
    approving ? 'info' : 'warning'
  )
  if (!confirmed) return

  const key = `audit-${row.id}`
  setActionLoading(key, true)
  try {
    await auditInvoice(row.id, action)
    ElMessage.success(`发票已${label}`)
    await load()
  } catch {
    // 统一错误提示由 API 拦截器负责。
  } finally {
    setActionLoading(key, false)
  }
}

async function mint(row) {
  const confirmed = await confirmAction(
    `将基于发票 ${row.invoiceCode} 开立 ${formatMoney(row.amount)} 的应收账款凭证，并占用对应授信额度。该操作会形成确权记录，是否继续？`,
    '确认开立凭证',
    'info'
  )
  if (!confirmed) return

  const key = `mint-${row.id}`
  setActionLoading(key, true)
  try {
    const response = await mintToken(row.id)
    ElMessage.success(`凭证开立成功：${response.data.data?.tokenId || '已生成'}`)
    await load()
  } catch {
    // 统一错误提示由 API 拦截器负责。
  } finally {
    setActionLoading(key, false)
  }
}

onMounted(load)
</script>

<template>
  <section class="portal-page">
    <header class="page-header">
      <div>
        <div class="page-eyebrow">CORE ENTERPRISE · 资产确权</div>
        <h1>{{ core?.name || user?.enterpriseName || '核心企业工作台' }}</h1>
        <p class="page-description">审核真实贸易背景，管理授信占用，并将合格应收账款转化为可流转的 RWA 凭证。</p>
      </div>
      <el-button type="primary" size="large" @click="openCreate">新建应收发票</el-button>
    </header>

    <el-alert
      v-if="loadError"
      class="load-alert"
      type="error"
      :title="loadError"
      :closable="false"
      show-icon
    >
      <template #default><el-button link type="primary" @click="load">重新加载</el-button></template>
    </el-alert>

    <div class="metrics-grid" v-loading="loading">
      <article class="metric-card">
        <span class="metric-label">授信总额</span>
        <strong>{{ formatMoney(creditLimit) }}</strong>
        <small>核心企业可用信用额度</small>
      </article>
      <article class="metric-card">
        <span class="metric-label">可用额度</span>
        <strong class="metric-accent">{{ formatMoney(creditAvailable) }}</strong>
        <small>已使用 {{ creditPercent }}%</small>
      </article>
      <article class="metric-card">
        <span class="metric-label">待办审核</span>
        <strong>{{ pendingCount }}</strong>
        <small>另有 {{ approvedCount }} 张待确权</small>
      </article>
      <article class="metric-card">
        <span class="metric-label">凭证存量</span>
        <strong>{{ formatMoney(tokenValue) }}</strong>
        <small>共 {{ tokens.length }} 张数字凭证</small>
      </article>
    </div>

    <div class="workflow-strip" aria-label="资产确权流程">
      <div class="workflow-step is-current"><span>1</span><div><b>贸易背景登记</b><small>录入发票与供应商</small></div></div>
      <div class="workflow-arrow">→</div>
      <div class="workflow-step"><span>2</span><div><b>发票审核</b><small>确认金额和账期</small></div></div>
      <div class="workflow-arrow">→</div>
      <div class="workflow-step"><span>3</span><div><b>凭证确权</b><small>占用授信并生成凭证</small></div></div>
    </div>

    <article class="surface-card credit-card">
      <div class="section-heading">
        <div>
          <span class="section-kicker">CREDIT FACILITY</span>
          <h2>授信额度监控</h2>
        </div>
        <StatusBadge :status="creditPercent >= 90 ? 'OVERDUE' : 'ONLINE'" :label="creditPercent >= 90 ? '额度紧张' : '额度健康'" />
      </div>
      <el-progress
        :percentage="creditPercent"
        :stroke-width="10"
        :color="creditPercent >= 90 ? '#c24155' : '#0f8a7e'"
        :show-text="false"
      />
      <div class="credit-legend">
        <span>已使用 <b>{{ formatMoney(creditUsed) }}</b></span>
        <span>可用 <b>{{ formatMoney(creditAvailable) }}</b></span>
        <span>使用率 <b>{{ creditPercent }}%</b></span>
      </div>
    </article>

    <article class="surface-card data-card">
      <div class="section-heading">
        <div><span class="section-kicker">INVOICE REVIEW</span><h2>发票审核队列</h2></div>
        <span class="muted">{{ filteredInvoices.length }} / {{ invoices.length }} 条</span>
      </div>
      <div class="toolbar">
        <el-input v-model="invoiceSearch" clearable placeholder="搜索发票代码、号码或供应商" class="search-input" />
        <el-select v-model="invoiceStatus" clearable placeholder="全部状态" class="status-filter">
          <el-option label="待审核" value="PENDING" />
          <el-option label="已审批" value="APPROVED" />
          <el-option label="已驳回" value="REJECTED" />
          <el-option label="已确权" value="MINTED" />
        </el-select>
        <el-button @click="load" :loading="loading">刷新</el-button>
      </div>
      <div class="table-wrap">
        <el-table :data="filteredInvoices" row-key="id" v-loading="loading" class="business-table">
          <el-table-column label="发票" min-width="190">
            <template #default="{ row }">
              <div class="primary-cell"><b>{{ row.invoiceCode }}</b><small>{{ row.invoiceNumber || '未填写号码' }}</small></div>
            </template>
          </el-table-column>
          <el-table-column label="供应商" min-width="180">
            <template #default="{ row }">{{ entName(row.supplierId) }}</template>
          </el-table-column>
          <el-table-column label="发票金额" min-width="140">
            <template #default="{ row }"><span class="money-cell">{{ formatMoney(row.amount) }}</span></template>
          </el-table-column>
          <el-table-column label="账期" min-width="180">
            <template #default="{ row }"><div class="primary-cell"><span>{{ formatDate(row.dueDate) }}</span><small>开票 {{ formatDate(row.issueDate) }}</small></div></template>
          </el-table-column>
          <el-table-column label="状态" width="110">
            <template #default="{ row }"><StatusBadge :status="row.status" /></template>
          </el-table-column>
          <el-table-column label="操作" width="210" fixed="right">
            <template #default="{ row }">
              <div class="row-actions" v-if="row.status === 'PENDING'">
                <el-button type="primary" link :loading="actionLoading[`audit-${row.id}`]" @click="audit(row, 'APPROVE')">通过</el-button>
                <el-button type="danger" link :disabled="actionLoading[`audit-${row.id}`]" @click="audit(row, 'REJECT')">驳回</el-button>
              </div>
              <el-button v-else-if="row.status === 'APPROVED'" type="primary" size="small" :loading="actionLoading[`mint-${row.id}`]" @click="mint(row)">开立凭证</el-button>
              <span v-else class="muted">无需操作</span>
            </template>
          </el-table-column>
          <template #empty><el-empty description="暂无匹配的发票记录" :image-size="72" /></template>
        </el-table>
      </div>
    </article>

    <article class="surface-card data-card">
      <div class="section-heading">
        <div><span class="section-kicker">DIGITAL ASSETS</span><h2>凭证确权台账</h2></div>
        <span class="muted">持有方、金额与流转状态</span>
      </div>
      <div class="toolbar">
        <el-input v-model="tokenSearch" clearable placeholder="搜索 Token ID、Slot、交易哈希" class="search-input" />
        <el-select v-model="tokenStatus" clearable placeholder="全部状态" class="status-filter">
          <el-option label="待流转" value="UNCIRCULATED" />
          <el-option label="流转中" value="CIRCULATING" />
          <el-option label="已质押" value="PLEDGED" />
          <el-option label="已兑付" value="SETTLED" />
          <el-option label="已逾期" value="OVERDUE" />
        </el-select>
      </div>
      <div class="table-wrap">
        <el-table :data="filteredTokens" row-key="id" v-loading="loading" class="business-table">
          <el-table-column label="Token ID" min-width="190">
            <template #default="{ row }"><span class="mono" :title="row.tokenId">{{ shortId(row.tokenId, 12, 7) }}</span></template>
          </el-table-column>
          <el-table-column label="当前持有方" min-width="170">
            <template #default="{ row }">{{ entName(row.enterpriseId) }}</template>
          </el-table-column>
          <el-table-column label="凭证余额" min-width="140">
            <template #default="{ row }"><span class="money-cell">{{ formatMoney(row.valueAmount) }}</span></template>
          </el-table-column>
          <el-table-column label="到期日" min-width="120">
            <template #default="{ row }">{{ formatDate(row.dueTimestamp) }}</template>
          </el-table-column>
          <el-table-column label="状态" width="110">
            <template #default="{ row }"><StatusBadge :status="row.status" /></template>
          </el-table-column>
          <el-table-column label="交易哈希" min-width="190">
            <template #default="{ row }"><span class="mono muted" :title="row.txHash">{{ shortId(row.txHash, 12, 7) }}</span></template>
          </el-table-column>
          <template #empty><el-empty description="暂无数字凭证" :image-size="72" /></template>
        </el-table>
      </div>
    </article>
  </section>

  <el-dialog v-model="dialog" title="登记应收账款发票" width="600px" :close-on-click-modal="false" destroy-on-close>
    <div class="dialog-intro">发票审核通过后，核心企业可将其确权为 RWA 凭证。请确保金额、供应商和到期日与贸易合同一致。</div>
    <el-form label-position="top" class="business-form">
      <div class="form-grid">
        <el-form-item label="发票代码" required><el-input v-model="form.invoiceCode" maxlength="40" placeholder="例如：INV-2026-001" /></el-form-item>
        <el-form-item label="发票号码" required><el-input v-model="form.invoiceNumber" maxlength="40" placeholder="请输入发票号码" /></el-form-item>
      </div>
      <el-form-item label="供应商" required>
        <el-select v-model="form.supplierId" style="width:100%" placeholder="选择应收账款供应商">
          <el-option v-for="supplier in suppliers" :key="supplier.id" :label="supplier.name" :value="supplier.id" />
        </el-select>
      </el-form-item>
      <el-form-item label="发票金额（元）" required>
        <el-input-number v-model="form.amount" :min="0.01" :precision="2" :step="10000" controls-position="right" style="width:100%" />
      </el-form-item>
      <div class="form-grid">
        <el-form-item label="开票日" required><el-date-picker v-model="form.issueDate" type="date" value-format="YYYY-MM-DD" style="width:100%" /></el-form-item>
        <el-form-item label="到期日" required><el-date-picker v-model="form.dueDate" type="date" value-format="YYYY-MM-DD" style="width:100%" /></el-form-item>
      </div>
    </el-form>
    <template #footer>
      <el-button :disabled="submitting" @click="dialog = false">取消</el-button>
      <el-button type="primary" :loading="submitting" @click="submit">提交审核</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.portal-page { display: grid; gap: 20px; }
.page-header { align-items: flex-end; margin-bottom: 0; }
.metrics-grid, .workflow-strip { margin-bottom: 0; }
.surface-card { margin-bottom: 0; padding: 22px 24px; }
.page-header h1 { margin: 5px 0 7px; color: #13233a; font-size: clamp(26px, 3vw, 34px); letter-spacing: -.5px; }
.load-alert { margin-bottom: -4px; }
.metric-card { display: flex; flex-direction: column; min-height: 126px; }
.metric-card strong { margin: 12px 0 7px; color: #14243b; font-size: clamp(23px, 2.5vw, 31px); line-height: 1; }
.metric-card small, .metric-label { color: #6c788b; font-size: 12px; }
.metric-label { font-weight: 650; letter-spacing: .02em; }
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
.credit-card { padding-bottom: 18px; }
.credit-legend { display: flex; justify-content: space-between; gap: 18px; margin-top: 12px; color: #687589; font-size: 13px; }
.credit-legend b { color: #28384f; }
.toolbar { justify-content: flex-start; margin-bottom: 14px; }
.search-input { width: min(360px, 100%); }
.status-filter { width: 150px; }
.table-wrap { width: 100%; overflow: hidden; }
.primary-cell { display: grid; gap: 4px; }
.primary-cell b, .primary-cell span { color: #24354d; }
.primary-cell small { color: #8792a3; }
.money-cell { color: #17283f; font-weight: 700; font-variant-numeric: tabular-nums; }
.row-actions { display: flex; align-items: center; }
.dialog-intro { margin: -4px 0 18px; padding: 12px 14px; border-radius: 9px; color: #546277; background: #f5f8fa; font-size: 13px; line-height: 1.55; }
.form-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 14px; }

:deep(.business-table) { --el-table-header-bg-color: #f7f9fb; --el-table-border-color: #edf0f4; }
:deep(.business-table th.el-table__cell) { color: #637085; font-size: 12px; font-weight: 700; }
:deep(.business-table td.el-table__cell) { padding: 13px 0; }
:deep(.el-dialog) { max-width: calc(100vw - 28px); border-radius: 14px; }

@media (max-width: 760px) {
  .page-header { align-items: stretch; }
  .page-header .el-button { width: 100%; }
  .workflow-arrow { display: none; }
  .workflow-step { width: 100%; }
  .credit-legend { align-items: flex-start; flex-direction: column; gap: 6px; }
  .toolbar { align-items: stretch; }
  .search-input, .status-filter { width: 100%; }
  .form-grid { grid-template-columns: 1fr; gap: 0; }
}
</style>
