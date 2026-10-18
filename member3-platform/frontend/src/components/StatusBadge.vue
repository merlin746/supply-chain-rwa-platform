<script setup>
import { computed } from 'vue'

const props = defineProps({
  status: { type: [String, Boolean], default: '' },
  label: { type: String, default: '' },
  subtle: { type: Boolean, default: false }
})

const STATUS_META = {
  PENDING: ['待审核', 'warning'],
  APPLIED: ['待审批', 'warning'],
  APPROVED: ['已审批', 'info'],
  MINTED: ['已确权', 'success'],
  UNCIRCULATED: ['待流转', 'neutral'],
  CIRCULATING: ['流转中', 'success'],
  PLEDGED: ['已质押', 'purple'],
  DISBURSED: ['已放款', 'success'],
  SETTLED: ['已兑付', 'neutral'],
  FROZEN: ['已冻结', 'warning'],
  REVOKED: ['已撤销', 'danger'],
  OVERDUE: ['已逾期', 'danger'],
  REJECTED: ['已驳回', 'danger'],
  ONCHAIN: ['链上模式', 'success'],
  OFFCHAIN: ['链下存证', 'neutral'],
  OFFCHAIN_DB: ['链下存证', 'neutral'],
  LOCAL_DEMO: ['本地演示可用', 'info'],
  ONLINE: ['运行正常', 'success'],
  OFFLINE: ['连接异常', 'danger'],
  DEPLOYED: ['合约已部署', 'success'],
  UNCONFIGURED: ['合约待配置', 'warning'],
  VERIFIED: ['核验通过', 'success'],
  FAILED: ['核验未通过', 'danger'],
  true: ['正常', 'success'],
  false: ['异常', 'danger']
}

const normalized = computed(() => String(props.status))
const meta = computed(() => STATUS_META[normalized.value] || [normalized.value || '未知', 'neutral'])
const text = computed(() => props.label || meta.value[0])
const tone = computed(() => meta.value[1])
</script>

<template>
  <span class="status-badge" :class="[`is-${tone}`, { 'is-subtle': subtle }]">
    <span class="status-dot" aria-hidden="true"></span>
    {{ text }}
  </span>
</template>

<style scoped>
.status-badge {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  min-height: 26px;
  padding: 3px 9px;
  border: 1px solid transparent;
  border-radius: 999px;
  font-size: 12px;
  font-weight: 650;
  line-height: 1;
  white-space: nowrap;
}

.status-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: currentColor;
}

.is-success { color: #087d72; background: #eaf8f5; border-color: #c7ebe5; }
.is-warning { color: #a15c06; background: #fff7e6; border-color: #f5dfb0; }
.is-danger { color: #b4233c; background: #fff0f2; border-color: #f3c8cf; }
.is-info { color: #245b8f; background: #edf5ff; border-color: #cfe2f7; }
.is-purple { color: #6941a5; background: #f4f0ff; border-color: #ddd2f5; }
.is-neutral { color: #596579; background: #f3f5f8; border-color: #dfe4eb; }
.is-subtle { background: transparent; border-color: transparent; padding-inline: 0; }
</style>
