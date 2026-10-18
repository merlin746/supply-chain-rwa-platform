<script setup>
import { onMounted, onUnmounted, ref, computed, nextTick } from 'vue'
import { useRouter } from 'vue-router'
import { init, use } from 'echarts/core'
import { GraphChart } from 'echarts/charts'
import { TooltipComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import { Refresh, ArrowRight, Wallet, DocumentChecked, Files, Money } from '@element-plus/icons-vue'
import { getOverview, getChainStatus, getTopology } from '../api'
import { authUser as user, homeOf } from '../auth'

use([GraphChart, TooltipComponent, CanvasRenderer])
const router = useRouter()
const data = ref({})
const chain = ref({})
const topology = ref({ nodes: [], links: [] })
const chartRef = ref()
const loading = ref(true)
const failed = ref(false)
let chart, observer
const colors = { CORE_ENTERPRISE: '#0b7b69', SUPPLIER: '#729ab1', BANK: '#b8955e' }
const roleNames = { CORE_ENTERPRISE: '核心企业', SUPPLIER: '供应商', BANK: '金融机构' }
const compact = n => Number(n || 0).toLocaleString('zh-CN', { maximumFractionDigits: 2 })
const amount = n => compact(Number(n || 0) / 10000)
const metrics = computed(() => [
  { label: '在持资产总额', value: amount(data.value.assetAmount), unit: '万元', caption: '有效凭证余额，不含已清算与已撤销', icon: Wallet },
  { label: '数字资产凭证', value: data.value.tokenCount || 0, unit: '张', caption: '当前账号可见的资产凭证', icon: Files },
  { label: '累计融资放款', value: amount(data.value.financedAmount), unit: '万元', caption: '已放款与已兑付融资合计', icon: Money },
  { label: '贸易发票', value: data.value.invoiceCount || 0, unit: '笔', caption: `${data.value.pendingInvoices || 0} 笔待审核 · ${data.value.approvedInvoices || 0} 笔待确权`, icon: DocumentChecked }
])
const todos = computed(() => user.value?.role === 'CORE_ENTERPRISE' ? [
  { label: '贸易背景审核', count: data.value.pendingInvoices || 0, text: '确认真实应收账款' },
  { label: '资产确权开立', count: data.value.approvedInvoices || 0, text: '为审核通过的发票签发凭证' }
] : user.value?.role === 'BANK' ? [
  { label: '融资申请待审批', count: data.value.pendingLoans || 0, text: '溯源核验后完成审批' },
  { label: '融资业务待放款', count: data.value.approvedLoans || 0, text: '凭证已锁定，可确认放款' }
] : [
  { label: '可流转资产', count: (data.value.tokenStatuses?.UNCIRCULATED || 0) + (data.value.tokenStatuses?.CIRCULATING || 0), text: '拆分支付或向银行申请融资' },
  { label: '融资处理中', count: (data.value.loanStatuses?.APPLIED || 0) + (data.value.loanStatuses?.APPROVED || 0), text: '跟进审批及放款进度' }
])
const flow = computed(() => [
  { label: '贸易审核', value: data.value.pendingInvoices || 0, unit: '笔待审核' },
  { label: '资产确权', value: data.value.approvedInvoices || 0, unit: '笔待开立' },
  { label: '融资审批', value: data.value.pendingLoans || 0, unit: '笔待审批' },
  { label: '资金放款', value: data.value.approvedLoans || 0, unit: '笔待放款' },
  { label: '到期清算', value: data.value.loanStatuses?.DISBURSED || 0, unit: '笔已放款' }
])
async function load() {
  if (loading.value && data.value.updatedAt) return
  loading.value = true
  failed.value = false
  try {
    const [overview, status, topo] = await Promise.all([getOverview(), getChainStatus(), getTopology()])
    data.value = overview.data.data
    chain.value = status.data.data
    topology.value = topo.data.data
    await nextTick()
    render()
  } catch { failed.value = true } finally { loading.value = false }
}
function render() {
  if (!chartRef.value || !topology.value.nodes?.length) { chart?.clear(); return }
  chart ||= init(chartRef.value)
  const nodes = topology.value.nodes
  chart.setOption({
    tooltip: { renderMode: 'richText', formatter: p => p.dataType === 'edge' ? `${p.data.source} → ${p.data.target}\n${compact(p.data.value)} 元` : `${p.name}\n${roleNames[p.data.type] || '企业'}` },
    series: [{ type: 'graph', layout: 'circular', circular: { rotateLabel: false }, roam: false,
      top: 42, bottom: 62, left: 68, right: 68,
      symbolSize: 62, label: { show: true, position: 'bottom', distance: 15, fontSize: 11, color: '#607480' },
      edgeLabel: { show: true, fontSize: 9, color: '#99a7af', formatter: p => Number(p.data.value) >= 10000 ? `${amount(p.data.value)}万` : `${compact(p.data.value)}元` },
      edgeSymbol: ['none', 'arrow'], edgeSymbolSize: 7,
      lineStyle: { width: 1.6, color: '#b9cfc7', curveness: .13, opacity: .8 },
      data: nodes.map(n => ({ ...n, symbolSize: n.type === 'CORE_ENTERPRISE' ? 72 : 54, itemStyle: { color: colors[n.type] || '#8298a2', borderColor: '#fff', borderWidth: 5, shadowBlur: 10, shadowColor: '#1634280c' }, label: { formatter: n.name } })),
      links: topology.value.links || []
    }]
  }, true)
}
onMounted(async () => { await load(); if (chartRef.value) { observer = new ResizeObserver(() => chart?.resize()); observer.observe(chartRef.value) } })
onUnmounted(() => { observer?.disconnect(); chart?.dispose() })
</script>

<template>
  <div class="page-header"><div><p class="page-eyebrow">BUSINESS OVERVIEW</p><h1>每一份信用，都清晰可见。</h1><p class="page-description">{{ user?.enterpriseName }}，欢迎回来。这里汇集了您当前的资产与业务动态。</p></div><el-button :icon="Refresh" :loading="loading" @click="load">刷新数据</el-button></div>
  <el-alert v-if="failed" type="error" title="数据暂未加载成功，请刷新重试" :closable="false" style="margin-bottom:20px" />
  <div v-loading="loading" class="metrics-grid"><div v-for="metric in metrics" :key="metric.label" class="metric-card"><div class="metric-label">{{ metric.label }}<el-icon><component :is="metric.icon" /></el-icon></div><div class="metric-value">{{ metric.value }}<small>{{ metric.unit }}</small></div><div class="metric-caption">{{ metric.caption }}</div></div></div>
  <div class="overview-grid">
    <section class="surface-card topology-card"><div class="section-head"><div><h2>供应链信用图谱</h2><p>数字凭证与融资关系，连接真实业务</p></div><span class="subtle-badge">{{ topology.nodes?.length || 0 }} 家关联企业</span></div><div class="graph-area"><div ref="chartRef" class="credit-chart" role="img" aria-label="供应链企业信用流转关系图"></div><el-empty v-if="!loading && !topology.nodes?.length" class="graph-empty" description="业务发生后，关联企业将在这里连接" :image-size="70" /></div><div class="graph-legend"><span v-for="(name,key) in roleNames" :key="key"><i :style="{ background: colors[key] }"></i>{{ name }}</span><small>拖动鼠标悬停，查看关系金额</small></div></section>
    <section class="surface-card todo-card"><div class="section-head"><div><h2>待办工作</h2><p>下一步，从这里开始</p></div><span class="todo-total">{{ todos.reduce((sum,t)=>sum+t.count,0) }}</span></div><button v-for="(todo,index) in todos" :key="todo.label" class="todo-row" @click="router.push(homeOf(user?.role))"><span class="todo-step">0{{ index+1 }}</span><span><strong>{{ todo.label }}</strong><small>{{ todo.text }}</small></span><b>{{ todo.count }}</b><el-icon><ArrowRight /></el-icon></button><div class="workspace-insight"><span>WORKFLOW NOTE</span><h3>让资产的每一步<br>都有明确的去向。</h3><p>融资申请后凭证锁定，放款后保持质押，到期兑付完成后清算。</p></div></section>
  </div>
  <section class="surface-card"><div class="section-head"><div><h2>业务全流程</h2><p>从贸易确权到资金清算，持续追踪每个节点</p></div><span class="muted" style="font-size:10px">当前账号业务范围</span></div><div class="lifecycle"><div v-for="(step,index) in flow" :key="step.label" class="lifecycle-item"><span class="lifecycle-number">0{{ index+1 }}</span><div><strong>{{ step.label }}</strong><p><b>{{ step.value }}</b> {{ step.unit }}</p></div><el-icon v-if="index<flow.length-1"><ArrowRight /></el-icon></div></div></section>
  <div class="data-footnote"><span><i :class="chain.mode==='OFFCHAIN' || chain.verified ? 'healthy' : ''"></i>{{ chain.mode==='ONCHAIN' ? chain.verified ? '链节点连接正常' : '链节点暂不可用' : '本地演示模式' }}<span class="footnote-divider">|</span>{{ chain.mode==='OFFCHAIN' ? '展示业务存证信息，未连接真实链节点' : `当前区块 ${chain.blockNumber ?? '—'}` }}</span><span>更新于 {{ data.updatedAt ? new Date(data.updatedAt).toLocaleTimeString('zh-CN', {hour:'2-digit',minute:'2-digit'}) : '—' }}</span></div>
</template>

<style scoped>
.overview-grid{display:grid;grid-template-columns:minmax(0,1.85fr) minmax(260px,1fr);gap:22px}.section-head{display:flex;align-items:center;justify-content:space-between;padding:22px 24px;gap:12px}.section-head h2{font-size:14px;font-weight:600;margin:0}.section-head p{font-size:10px;color:#98a5ad;margin:7px 0 0}.subtle-badge{background:#f3f7f6;color:#6c9084;font-size:9px;padding:6px 9px;border-radius:5px;white-space:nowrap}.graph-area{position:relative;background-image:radial-gradient(#dce5e5 .65px,transparent .65px);background-size:16px 16px;margin:0 20px;border-radius:8px;background-color:#fcfdfd}.credit-chart{height:292px;width:100%}.graph-empty{position:absolute;inset:0}.graph-legend{display:flex;gap:19px;align-items:center;padding:20px 24px;font-size:9px;color:#7f909c}.graph-legend span{display:flex;align-items:center;gap:6px}.graph-legend i{width:6px;height:6px;border-radius:2px}.graph-legend small{font-size:8px;margin-left:auto;color:#a1acb2}.todo-total{color:#2d8c72;background:#eaf5ee;font-size:11px;padding:5px 8px;border-radius:5px}.todo-row{display:flex;gap:12px;align-items:center;text-align:left;border:0;border-bottom:1px solid #f0f3f4;background:white;width:calc(100% - 48px);margin:0 24px;padding:19px 0;color:#617680}.todo-step{font-family:Consolas,monospace;color:#a9b6bb;font-size:10px}.todo-row strong{font-size:11px;font-weight:500;display:block;color:#405762}.todo-row small{font-size:9px;color:#9ca8b0;display:block;margin-top:7px}.todo-row>b{margin-left:auto;color:#2a6b55;font-size:19px;font-weight:500}.todo-row>.el-icon{font-size:10px;color:#a5b7bb}.workspace-insight{background:#f3f7f3;margin:23px 24px;padding:20px 21px;border-radius:8px}.workspace-insight>span{font-size:8px;letter-spacing:1.8px;color:#90a88f}.workspace-insight h3{font-size:18px;line-height:1.65;color:#3a6352;font-weight:450;margin:11px 0 8px}.workspace-insight p{font-size:9px;line-height:1.9;color:#8b9b8c;margin:0}.lifecycle{padding:3px 24px 26px;display:grid;grid-template-columns:repeat(5,1fr)}.lifecycle-item{display:flex;align-items:center;gap:12px}.lifecycle-number{background:#edf6f2;color:#4c8a75;display:grid;place-items:center;width:31px;height:31px;flex-shrink:0;border-radius:50%;font-family:Consolas,monospace;font-size:10px}.lifecycle-item strong{font-size:11px;font-weight:500;color:#526b75}.lifecycle-item p{font-size:9px;color:#a4afb6;margin:7px 0 0}.lifecycle-item p b{font-weight:500;color:#5f7a76;margin-right:3px}.lifecycle-item>.el-icon{font-size:10px;color:#c6d4d6;margin:auto}.data-footnote{display:flex;align-items:center;justify-content:space-between;color:#98a5ad;font-size:9px;line-height:1.7;gap:12px}.data-footnote>span:first-child{display:flex;gap:6px;align-items:center;flex-wrap:wrap}.data-footnote i{width:5px;height:5px;display:inline-block;border-radius:50%;background:#bb8d51}.data-footnote i.healthy{background:#6a9a7a}.footnote-divider{color:#d0d9dd;margin:0 7px}
@media(max-width:1150px){.overview-grid{grid-template-columns:1.5fr 1fr;gap:15px}.section-head{padding:20px}.todo-row{margin:0 20px;width:calc(100% - 40px)}.workspace-insight{margin:20px}.graph-legend{flex-wrap:wrap}.graph-legend small{display:none}.lifecycle{gap:12px}.lifecycle-item{gap:7px}.lifecycle-item>.el-icon{display:none}}
@media(max-width:960px){.overview-grid{grid-template-columns:1fr}.todo-card .workspace-insight{display:none}.todo-card .todo-row:last-of-type{margin-bottom:20px}.lifecycle{grid-template-columns:repeat(3,1fr);row-gap:20px}.credit-chart{height:280px}}
@media(max-width:600px){.page-header h1{font-size:22px}.graph-area{margin:0 10px}.graph-legend{padding:18px;gap:16px}.section-head{padding:19px 17px}.lifecycle{padding:0 16px 22px;grid-template-columns:repeat(2,1fr)}.data-footnote>span:last-child{display:none}}
</style>
