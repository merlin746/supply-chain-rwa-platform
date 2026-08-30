<script setup>
import { onMounted, ref, computed } from 'vue'
import { getInvoices, createInvoice, auditInvoice, getTokens, mintToken, getEnterprises } from '../api'
import { ElMessage, ElMessageBox } from 'element-plus'

const invoices = ref([])
const tokens = ref([])
const enterprises = ref([])
const dialog = ref(false)
const form = ref({ invoiceCode: '', invoiceNumber: '', coreEnterpriseId: 1, supplierId: null, amount: 100000, issueDate: '', dueDate: '' })

const core = computed(() => enterprises.value.find(e => e.enterpriseType === 'CORE_ENTERPRISE'))
const suppliers = computed(() => enterprises.value.filter(e => e.enterpriseType === 'SUPPLIER'))
const entName = id => enterprises.value.find(e => e.id === id)?.name || id

const creditPercent = computed(() => {
  if (!core.value || !Number(core.value.creditLimit)) return 0
  return Math.min(100, Math.round(Number(core.value.creditUsed) / Number(core.value.creditLimit) * 100))
})

const fmtMoney = v => '¥' + Number(v || 0).toLocaleString('zh-CN')
const fmtDate = ts => ts ? new Date(Number(ts) * 1000).toLocaleDateString('zh-CN') : '-'

const statusTag = s => ({
  PENDING: 'warning', APPROVED: 'success', REJECTED: 'danger', MINTED: 'primary'
}[s] || 'info')
const statusText = s => ({
  PENDING: '待审核', APPROVED: '已审核', REJECTED: '已驳回', MINTED: '已开立凭证'
}[s] || s)
const tokenStatusText = s => ({
  UNCIRCULATED: '未流转', CIRCULATING: '流转中', PLEDGED: '已质押', SETTLED: '已清算', OVERDUE: '已逾期'
}[s] || s)

async function load() {
  const [inv, tok, ent] = await Promise.all([getInvoices(), getTokens(), getEnterprises()])
  invoices.value = inv.data.data
  tokens.value = tok.data.data
  enterprises.value = ent.data.data
  if (!form.value.supplierId && suppliers.value.length) {
    form.value.supplierId = suppliers.value[0].id
  }
  if (core.value) form.value.coreEnterpriseId = core.value.id
}

async function submit() {
  if (!form.value.invoiceCode || !form.value.supplierId || !form.value.dueDate) {
    ElMessage.warning('请填写发票代码、供应商与到期日')
    return
  }
  await createInvoice(form.value)
  ElMessage.success('发票已提交，待审核')
  dialog.value = false
  await load()
}

async function audit(row, action) {
  const label = action === 'APPROVE' ? '通过' : '驳回'
  await ElMessageBox.confirm(`确定${label}发票 ${row.invoiceCode} 吗？`, '发票审核', { type: 'warning' })
  const res = await auditInvoice(row.id, action)
  if (!res.data.success) { ElMessage.error(res.data.message); return }
  ElMessage.success(`已${label}`)
  await load()
}

async function mint(row) {
  await ElMessageBox.confirm(
    `将基于发票 ${row.invoiceCode}（${fmtMoney(row.amount)}）开立应收账款凭证，并占用核心企业授信额度。继续？`,
    '开立凭证', { type: 'info' })
  const res = await mintToken(row.id)
  if (!res.data.success) { ElMessage.error(res.data.message); return }
  ElMessage.success(`凭证开立成功：${res.data.data.tokenId}`)
  await load()
}

onMounted(load)
</script>

<template>
  <h1 class="page-title">核心企业 Portal · {{ core?.name || '比亚迪' }}</h1>

  <el-card style="margin-bottom:18px">
    <template #header><b>授信额度监控</b></template>
    <el-progress :percentage="creditPercent" status="success"/>
    <div style="margin-top:10px">
      授信额度：{{ fmtMoney(core?.creditLimit) }}　已使用：{{ fmtMoney(core?.creditUsed) }}（{{ creditPercent }}%）
    </div>
  </el-card>

  <el-card style="margin-bottom:18px">
    <template #header>
      <div style="display:flex;justify-content:space-between">
        <b>发票审核</b>
        <el-button type="primary" @click="dialog=true">新建发票</el-button>
      </div>
    </template>
    <el-table :data="invoices" border>
      <el-table-column prop="invoiceCode" label="发票代码" width="140"/>
      <el-table-column prop="invoiceNumber" label="发票号码" width="110"/>
      <el-table-column label="供应商" width="120">
        <template #default="{row}">{{ entName(row.supplierId) }}</template>
      </el-table-column>
      <el-table-column label="金额" width="140">
        <template #default="{row}">{{ fmtMoney(row.amount) }}</template>
      </el-table-column>
      <el-table-column prop="dueDate" label="到期日" width="120"/>
      <el-table-column label="状态" width="110">
        <template #default="{row}">
          <el-tag :type="statusTag(row.status)">{{ statusText(row.status) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="220">
        <template #default="{row}">
          <template v-if="row.status==='PENDING'">
            <el-button size="small" type="success" @click="audit(row,'APPROVE')">通过</el-button>
            <el-button size="small" type="danger" @click="audit(row,'REJECT')">驳回</el-button>
          </template>
          <el-button v-if="row.status==='APPROVED'" size="small" type="primary" @click="mint(row)">开立凭证</el-button>
        </template>
      </el-table-column>
    </el-table>
  </el-card>

  <el-card>
    <template #header><b>链上凭证</b></template>
    <el-table :data="tokens" border>
      <el-table-column prop="tokenId" label="Token ID" width="220"/>
      <el-table-column label="持有方" width="120">
        <template #default="{row}">{{ entName(row.enterpriseId) }}</template>
      </el-table-column>
      <el-table-column label="金额" width="140">
        <template #default="{row}">{{ fmtMoney(row.valueAmount) }}</template>
      </el-table-column>
      <el-table-column prop="slotId" label="Slot" width="140"/>
      <el-table-column label="到期日" width="120">
        <template #default="{row}">{{ fmtDate(row.dueTimestamp) }}</template>
      </el-table-column>
      <el-table-column label="状态" width="100">
        <template #default="{row}"><el-tag>{{ tokenStatusText(row.status) }}</el-tag></template>
      </el-table-column>
      <el-table-column prop="txHash" label="交易Hash" show-overflow-tooltip/>
    </el-table>
  </el-card>

  <el-dialog v-model="dialog" title="开立应收账款发票">
    <el-form label-width="100px">
      <el-form-item label="发票代码"><el-input v-model="form.invoiceCode"/></el-form-item>
      <el-form-item label="发票号码"><el-input v-model="form.invoiceNumber"/></el-form-item>
      <el-form-item label="供应商">
        <el-select v-model="form.supplierId" style="width:100%">
          <el-option v-for="s in suppliers" :key="s.id" :label="s.name" :value="s.id"/>
        </el-select>
      </el-form-item>
      <el-form-item label="金额"><el-input-number v-model="form.amount" :min="0" style="width:100%"/></el-form-item>
      <el-form-item label="开票日"><el-date-picker v-model="form.issueDate" type="date" value-format="YYYY-MM-DD" style="width:100%"/></el-form-item>
      <el-form-item label="到期日"><el-date-picker v-model="form.dueDate" type="date" value-format="YYYY-MM-DD" style="width:100%"/></el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="dialog=false">取消</el-button>
      <el-button type="primary" @click="submit">提交审核</el-button>
    </template>
  </el-dialog>
</template>
