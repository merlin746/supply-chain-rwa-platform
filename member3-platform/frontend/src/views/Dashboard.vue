<script setup>
import { onMounted, ref, nextTick } from 'vue'
import * as echarts from 'echarts'
import { getOverview, getChainStatus, getTopology } from '../api'

const data = ref({})
const chain = ref({})
const chartRef = ref()

const TYPE_COLOR = {
  CORE_ENTERPRISE: '#e6a23c',
  SUPPLIER: '#409eff',
  BANK: '#67c23a'
}
const TYPE_SIZE = { CORE_ENTERPRISE: 72, SUPPLIER: 52, BANK: 64 }

onMounted(async () => {
  const [a, b, t] = await Promise.all([getOverview(), getChainStatus(), getTopology()])
  data.value = a.data.data
  chain.value = b.data.data
  await nextTick()
  render(t.data.data)
})

function render(topo) {
  let nodes = topo?.nodes || []
  let links = topo?.links || []

  // 尚无业务数据时展示演示链路，避免大屏空白
  if (!nodes.length) {
    nodes = [
      { name: '比亚迪', type: 'CORE_ENTERPRISE' },
      { name: '科达利', type: 'SUPPLIER' },
      { name: '聚能永拓', type: 'SUPPLIER' },
      { name: '长园特发', type: 'SUPPLIER' },
      { name: '建设银行', type: 'BANK' }
    ]
    links = [
      { source: '比亚迪', target: '科达利', value: 0 },
      { source: '科达利', target: '聚能永拓', value: 0 },
      { source: '聚能永拓', target: '长园特发', value: 0 }
    ]
  }

  const fmtWan = v => {
    const n = Number(v || 0)
    if (!n) return ''
    return n >= 10000 ? (n / 10000).toLocaleString('zh-CN', { maximumFractionDigits: 1 }) + '万' : n + '元'
  }

  const chart = echarts.init(chartRef.value)
  chart.setOption({
    tooltip: {
      formatter: p => p.dataType === 'edge'
        ? `${p.data.source} → ${p.data.target}<br/>累计金额：${fmtWan(p.data.value) || '演示链路'}`
        : `${p.name}<br/>角色：${({ CORE_ENTERPRISE: '核心企业', SUPPLIER: '供应商', BANK: '资金方' })[p.data.type] || p.data.type}`
    },
    title: { text: '供应链信用穿透拓扑', subtext: '数据来源于凭证拆分/转让与融资记录', left: 'center' },
    legend: { bottom: 0, data: ['核心企业', '供应商', '资金方'] },
    series: [{
      type: 'graph',
      layout: 'force',
      roam: true,
      label: { show: true, fontSize: 13, fontWeight: 600 },
      edgeLabel: { show: true, fontSize: 11, color: '#606266', formatter: p => fmtWan(p.data.value) },
      force: { repulsion: 320, edgeLength: 170 },
      lineStyle: { width: 2, curveness: 0.15, color: '#94a3b8' },
      edgeSymbol: ['none', 'arrow'],
      edgeSymbolSize: 10,
      categories: [{ name: '核心企业' }, { name: '供应商' }, { name: '资金方' }],
      data: nodes.map(n => ({
        name: n.name,
        type: n.type,
        symbolSize: TYPE_SIZE[n.type] || 50,
        category: n.type === 'CORE_ENTERPRISE' ? 0 : n.type === 'BANK' ? 2 : 1,
        itemStyle: { color: TYPE_COLOR[n.type] || '#909399' },
        label: { color: '#fff' }
      })),
      links
    }]
  })
  window.addEventListener('resize', () => chart.resize())
}
</script>

<template>
  <h1 class="page-title">平台总览</h1>
  <div class="card-grid">
    <el-card class="stat-card"><div>企业数量</div><div class="stat-number">{{ data.enterpriseCount || 0 }}</div></el-card>
    <el-card class="stat-card"><div>RWA凭证</div><div class="stat-number">{{ data.tokenCount || 0 }}</div></el-card>
    <el-card class="stat-card"><div>发票数量</div><div class="stat-number">{{ data.invoiceCount || 0 }}</div></el-card>
    <el-card class="stat-card"><div>融资申请</div><div class="stat-number">{{ data.loanCount || 0 }}</div></el-card>
  </div>

  <div class="two-col">
    <div class="panel">
      <div style="margin-bottom:12px;font-weight:700">链节点状态</div>
      <el-descriptions :column="1" border>
        <el-descriptions-item label="运行模式">
          <el-tag :type="chain.mode === 'ONCHAIN' ? 'success' : 'info'" size="small">
            {{ chain.mode === 'ONCHAIN' ? '链上模式' : '链下存证模式' }}
          </el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="Client">{{ chain.clientVersion || '未连接' }}</el-descriptions-item>
        <el-descriptions-item label="区块高度">{{ chain.blockNumber || '-' }}</el-descriptions-item>
        <el-descriptions-item label="合约">{{ chain.contractExists ? '已部署' : '待配置（等成员1交付）' }}</el-descriptions-item>
      </el-descriptions>
      <el-alert style="margin-top:14px" type="info" :closable="false"
        title="凭证开立/拆分当前以链下模式落库，接入成员1节点与合约 ABI 后自动切换链上同步"/>
    </div>
    <div class="panel">
      <div ref="chartRef" class="chart"></div>
    </div>
  </div>
</template>
