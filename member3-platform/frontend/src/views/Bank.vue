<script setup>
import { onMounted, ref } from 'vue'
import { getLoans, approveLoan, disburseLoan, settleLoan, rejectLoan, getChainStatus, verifyToken } from '../api'
import { ElMessage, ElMessageBox } from 'element-plus'

const loans = ref([])
const chain = ref({})
const verifyDialog = ref(false)
const verifyData = ref(null)
const verifyLoading = ref(false)

const fmtMoney = v => '¥' + Number(v || 0).toLocaleString('zh-CN')
const loanStatusTag = s => ({
  APPLIED: 'warning', APPROVED: 'primary', DISBURSED: 'success', SETTLED: 'info', REJECTED: 'danger'
}[s] || 'info')
const loanStatusText = s => ({
  APPLIED: '待审批', APPROVED: '已审批', DISBURSED: '已放款', SETTLED: '已兑付', REJECTED: '已驳回'
}[s] || s)

async function load() {
  loans.value = (await getLoans()).data.data
  chain.value = (await getChainStatus()).data.data
}

async function approve(id) {
  const res = await approveLoan(id)
  if (!res.data.success) { ElMessage.error(res.data.message); return }
  ElMessage.success('已审批，凭证进入质押状态')
  await load()
}

async function disburse(id) {
  await ElMessageBox.confirm('确认向供应商放款？放款后凭证保持质押状态，到期后执行兑付清算。', '一键放款', { type: 'warning' })
  const res = await disburseLoan(id)
  if (!res.data.success) { ElMessage.error(res.data.message); return }
  ElMessage.success('放款成功')
  await load()
}

async function settle(id) {
  await ElMessageBox.confirm('确认凭证已到期，执行到期兑付？兑付后凭证清算（SETTLED），该操作不可撤销。', '到期兑付', { type: 'warning' })
  const res = await settleLoan(id)
  if (!res.data.success) { ElMessage.error(res.data.message); return }
  ElMessage.success('到期兑付完成，凭证已清算')
  await load()
}

async function reject(id) {
  await ElMessageBox.confirm('确定驳回该融资申请？', '驳回', { type: 'warning' })
  const res = await rejectLoan(id)
  if (!res.data.success) { ElMessage.error(res.data.message); return }
  ElMessage.success('已驳回')
  await load()
}

async function verify(row) {
  verifyDialog.value = true
  verifyLoading.value = true
  verifyData.value = null
  try {
    const res = await verifyToken(row.tokenId)
    if (!res.data.success) { ElMessage.error(res.data.message); verifyDialog.value = false; return }
    verifyData.value = res.data.data
  } finally {
    verifyLoading.value = false
  }
}

onMounted(load)
</script>

<template>
  <h1 class="page-title">银行 Portal · 建设银行</h1>

  <el-card style="margin-bottom:18px">
    <template #header><b>链节点与合约状态</b></template>
    <el-descriptions :column="2" border>
      <el-descriptions-item label="节点">{{ chain.clientVersion || '未连接' }}</el-descriptions-item>
      <el-descriptions-item label="当前区块">{{ chain.blockNumber || '-' }}</el-descriptions-item>
      <el-descriptions-item label="运行模式">
        <el-tag :type="chain.mode === 'ONCHAIN' ? 'success' : 'info'">{{ chain.mode === 'ONCHAIN' ? '链上模式' : '链下存证模式' }}</el-tag>
      </el-descriptions-item>
      <el-descriptions-item label="合约状态">
        <el-tag :type="chain.contractExists ? 'success' : 'warning'">{{ chain.contractExists ? '已部署' : '待配置' }}</el-tag>
      </el-descriptions-item>
    </el-descriptions>
  </el-card>

  <el-card>
    <template #header><b>融资申请列表</b></template>
    <el-table :data="loans" border>
      <el-table-column prop="applicationNo" label="申请编号" width="120"/>
      <el-table-column prop="tokenId" label="质押凭证" width="220"/>
      <el-table-column label="融资金额" width="130">
        <template #default="{row}">{{ fmtMoney(row.amount) }}</template>
      </el-table-column>
      <el-table-column prop="interestRate" label="年化利率(%)" width="100"/>
      <el-table-column prop="termDays" label="期限(天)" width="90"/>
      <el-table-column label="状态" width="100">
        <template #default="{row}">
          <el-tag :type="loanStatusTag(row.status)">{{ loanStatusText(row.status) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="380">
        <template #default="{row}">
          <el-button size="small" @click="verify(row)">溯源核验</el-button>
          <el-button type="primary" size="small" :disabled="row.status!=='APPLIED'" @click="approve(row.id)">审批</el-button>
          <el-button type="success" size="small" :disabled="row.status!=='APPROVED'" @click="disburse(row.id)">一键放款</el-button>
          <el-button type="warning" size="small" :disabled="row.status!=='DISBURSED'" @click="settle(row.id)">到期兑付</el-button>
          <el-button type="danger" size="small" :disabled="row.status!=='APPLIED'" @click="reject(row.id)">驳回</el-button>
        </template>
      </el-table-column>
    </el-table>
  </el-card>

  <el-dialog v-model="verifyDialog" title="链上凭证溯源真实性核验" width="640px">
    <div v-loading="verifyLoading">
      <template v-if="verifyData">
        <el-alert type="success" :closable="false" style="margin-bottom:14px"
          :title="`核验通过：凭证可回溯至根凭证 ${verifyData.rootTokenId}，流转深度 ${verifyData.chainDepth} 级`"
          :description="verifyData.note"/>
        <el-steps direction="vertical" :active="verifyData.chain.length" style="margin-bottom:14px">
          <el-step v-for="hop in verifyData.chain" :key="hop.tokenId"
            :title="`${hop.holder || '未知持有方'} · ${hop.tokenId}`"
            :description="`金额 ${fmtMoney(hop.valueAmount)} ｜ Slot ${hop.slotId || '-'} ｜ 状态 ${hop.status} ｜ Hash ${hop.txHash || '-'}`"/>
        </el-steps>
        <el-descriptions v-if="verifyData.invoice" :column="2" border title="底层贸易背景（发票）">
          <el-descriptions-item label="发票代码">{{ verifyData.invoice.invoiceCode }}</el-descriptions-item>
          <el-descriptions-item label="发票号码">{{ verifyData.invoice.invoiceNumber }}</el-descriptions-item>
          <el-descriptions-item label="金额">{{ fmtMoney(verifyData.invoice.amount) }}</el-descriptions-item>
          <el-descriptions-item label="到期日">{{ verifyData.invoice.dueDate }}</el-descriptions-item>
        </el-descriptions>
      </template>
    </div>
  </el-dialog>
</template>
