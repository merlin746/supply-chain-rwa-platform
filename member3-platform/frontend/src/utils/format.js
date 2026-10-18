const moneyFormatter = new Intl.NumberFormat('zh-CN', {
  style: 'currency',
  currency: 'CNY',
  minimumFractionDigits: 0,
  maximumFractionDigits: 2
})

const numberFormatter = new Intl.NumberFormat('zh-CN', {
  maximumFractionDigits: 2
})

export function formatMoney(value) {
  const amount = Number(value)
  return moneyFormatter.format(Number.isFinite(amount) ? amount : 0)
}

export function formatNumber(value) {
  const number = Number(value)
  return numberFormatter.format(Number.isFinite(number) ? number : 0)
}

export function formatDate(value, fallback = '—') {
  if (value === null || value === undefined || value === '') return fallback

  let date
  if (typeof value === 'number' || /^\d+$/.test(String(value))) {
    const timestamp = Number(value)
    date = new Date(timestamp < 10_000_000_000 ? timestamp * 1000 : timestamp)
  } else {
    date = new Date(value)
  }

  if (Number.isNaN(date.getTime())) return fallback
  return new Intl.DateTimeFormat('zh-CN', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit'
  }).format(date)
}

export function shortId(value, head = 8, tail = 6) {
  const text = String(value || '')
  if (!text) return '—'
  if (text.length <= head + tail + 3) return text
  return `${text.slice(0, head)}…${text.slice(-tail)}`
}

export function daysUntil(timestamp) {
  if (timestamp === null || timestamp === undefined || timestamp === '') return null
  const raw = Number(timestamp)
  const milliseconds = raw < 10_000_000_000 ? raw * 1000 : raw
  if (!Number.isFinite(milliseconds)) return null
  return Math.ceil((milliseconds - Date.now()) / 86_400_000)
}

export function unwrapList(response) {
  const data = response?.data?.data
  return Array.isArray(data) ? data : []
}
