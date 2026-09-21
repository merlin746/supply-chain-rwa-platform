const KEY = 'rwa-user'

export function getUser() {
  try {
    return JSON.parse(localStorage.getItem(KEY))
  } catch {
    return null
  }
}

export function setUser(user) {
  localStorage.setItem(KEY, JSON.stringify(user))
}

export function clearUser() {
  localStorage.removeItem(KEY)
}

export function homeOf(role) {
  switch (role) {
    case 'CORE_ENTERPRISE': return '/core'
    case 'SUPPLIER': return '/supplier'
    case 'BANK': return '/bank'
    default: return '/dashboard'
  }
}
