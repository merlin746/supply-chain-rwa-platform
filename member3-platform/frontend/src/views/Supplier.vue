<script setup>
import { onMounted, ref, computed } from 'vue'
import { getTokens, applyLoan, splitToken, getEnterprises } from '../api'
import { getUser } from '../auth'
import { ElMessage } from 'element-plus'

const user = getUser()
const tokens = ref([])
const enterprises = ref([])
const loanDialog = ref(false)
const splitDialog = ref(false)
const splitRow = ref(null)

const loanForm = ref({ tokenId: '', supplierId: user?.enterpriseId || 2, bankId: 5, amount: 0, interestRate: 4.2, termDays: 90, remark: '' })
const splitForm = ref({ fromTokenId: '', toEnterpriseId: null, value: 0 })

const entName = id => enterprises.value.find(e => e.id === id)?.name || id
const bank = computed(() => enterprises.value.find(e => e.enterpriseType === 'BANK'))
const splitTargets = computed(() =>
  enterprises.value.filter(e => e.enterpriseType === 'SUPPLIER' && e.id !== user?.enterpriseId))

const fmtMoney = v => '¥' + Number(v || 0).toLocaleString('zh-CN')
const fmtDate = ts => ts ? new Date(Number(ts) * 1000).toLocaleDateString('zh-CN') : '-'
const tokenStatusText = s => ({
  UNCIRCULATED: '未流转', CIRCULATING: '流转中', PLEDGED: '已质押', SETTLED: '已清算', OVERDUE: '已逾期'
}[s] || s)
const canOperate = s => ['UNCIRCULATED', 'CIRCULATING'].includes(s)

async function load() {
  const params = user?.role === 'SUPPLIER' ? { enterpriseId: user.enterpriseId } : {}
  const [tok, ent] = await Promise.all([getTokens(params), getEnterprises()])
  tokens.value = tok.data.data
  enterprises.value = ent.data.data
  if (bank.value) loanForm.value.bankId = bank.value.id
}

function openLoan(row) {
  loanForm.value.tokenId = row.tokenId
  loanForm.value.amount = Number(row.valueAmount || 0)
  loanDialog.value = true
}

async function submitLoan() {
  const res = await applyLoan(loanForm.value)
  if (!res.data.success) { ElMessage.error(res.data.message); return }
  ElMessage.success('融资申请已提交，等待银行审批')
  loanDialog.value = false
  await load()
}

function openSplit(row) {
  splitRow.value = row
  splitForm.value = { fromTokenId: row.tokenId, toEnterpriseId: null, value: 0 }
  splitDialog.value = true
}

async function submitSplit() {
  if (!splitForm.value.toEnterpriseId) { ElMessage.warning('请选择接收方供应商'); return }
  if (!splitForm.value.value || splitForm.value.value <= 0) { ElMessage.warning('请输入拆分金额'); return }
  const res = await splitToken(splitForm.value)
  if (!res.data.success) { ElMessage.error(res.data.message); return }
  const d = res.data.data
  ElMessage.success(d.type === 'TRANSFER'
    ? `已整体转让给 ${entName(splitForm.value.toEnterpriseId)}`
    : `拆分成功，子凭证：${d.child?.tokenId}`)
  splitDialog.value = false
  await load()
}

onMounted(load)
</script>

<template>
  <h1 class="page-title">供应商 Portal · {{ user?.enterpriseName || '' }}</h1>
  <el-alert title="支持供应商查看RWA凭证、拆分支付给下级供应商、以凭证质押申请银行融资" type="info" show-icon style="margin-bottom:18px"/>
  <el-card>
    <template #header><b>我的链上资产</b></template>
    <el-table :data="tokens" border>
      <el-table-column prop="tokenId" label="Token ID" width="220"/>
      <el-table-column prop="parentTokenId" label="来源凭证" width="220">
        <template #default="{row}">{{ row.parentTokenId || '（根凭证 · 核心企业开立）' }}</template>
      </el-table-column>
      <el-table-column prop="slotId" label="Slot" width="130"/>
      <el-table-column label="资产金额" width="130">
        <template #default="{row}">{{ fmtMoney(row.valueAmount) }}</template>
      </el-table-column>
      <el-table-column label="到期日" width="110">
        <template #default="{row}">{{ fmtDate(row.dueTimestamp) }}</template>
      </el-table-column>
      <el-table-column label="状态" width="100">
        <template #default="{row}"><el-tag>{{ tokenStatusText(row.status) }}</el-tag></template>
      </el-table-column>
      <el-table-column label="操作" width="200">
        <template #default="{row}">
          <el-button size="small" :disabled="!canOperate(row.status)" @click="openSplit(row)">拆分支付</el-button>
          <el-button size="small" type="primary" :disabled="!canOperate(row.status)" @click="openLoan(row)">申请融资</el-button>
        </template>
      </el-table-column>
    </el-table>
  </el-card>

  <el-dialog v-model="splitDialog" title="凭证拆分支付（无损拆分 · 金额守恒）" width="520px">
    <el-alert type="success" :closable="false" style="margin-bottom:14px"
      title="拆分后子凭证继承原凭证的到期日（Slot）与核心企业信用背书，拆分金额 + 剩余金额 = 原凭证金额"/>
    <el-form label-width="110px">
      <el-form-item label="原凭证">
        <el-input :model-value="splitForm.fromTokenId" disabled/>
      </el-form-item>
      <el-form-item label="可拆分余额">
        <el-input :model-value="fmtMoney(splitRow?.valueAmount)" disabled/>
      </el-form-item>
      <el-form-item label="接收方">
        <el-select v-model="splitForm.toEnterpriseId" placeholder="选择下级供应商" style="width:100%">
          <el-option v-for="t in splitTargets" :key="t.id" :label="t.name" :value="t.id"/>
        </el-select>
      </el-form-item>
      <el-form-item label="拆分金额">
        <el-input-number v-model="splitForm.value" :min="0" :max="Number(splitRow?.valueAmount || 0)" :precision="2" style="width:100%"/>
        <div style="font-size:12px;color:#909399;margin-top:4px">输入等于余额的金额即为整体转让</div>
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="splitDialog=false">取消</el-button>
      <el-button type="primary" @click="submitSplit">确认拆分</el-button>
    </template>
  </el-dialog>

  <el-dialog v-model="loanDialog" title="银行融资申请（凭证质押贴现）">
    <el-form label-width="100px">
      <el-form-item label="Token ID"><el-input v-model="loanForm.tokenId" disabled/></el-form-item>
      <el-form-item label="融资金额"><el-input-number v-model="loanForm.amount" :min="0" style="width:100%"/></el-form-item>
      <el-form-item label="年化利率"><el-input-number v-model="loanForm.interestRate" :min="0" :precision="2" style="width:100%"/></el-form-item>
      <el-form-item label="期限(天)"><el-input-number v-model="loanForm.termDays" :min="1" style="width:100%"/></el-form-item>
      <el-form-item label="备注"><el-input v-model="loanForm.remark" type="textarea"/></el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="loanDialog=false">取消</el-button>
      <el-button type="primary" @click="submitLoan">提交</el-button>
    </template>
  </el-dialog>
</template>
