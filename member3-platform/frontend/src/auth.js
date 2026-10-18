import { shallowRef, readonly } from 'vue'

const KEY = 'rwa-user'

function readSession() {
  try {
    const value = JSON.parse(sessionStorage.getItem(KEY))
    return value?.token ? value : null
  } catch {
    return null
  }
}

const currentUser = shallowRef(readSession())
export const authUser = readonly(currentUser)
export const getUser = () => currentUser.value

export function setUser(user) {
  currentUser.value = user
  sessionStorage.setItem(KEY, JSON.stringify(user))
  localStorage.removeItem(KEY)
}

export function clearUser() {
  currentUser.value = null
  sessionStorage.removeItem(KEY)
  localStorage.removeItem(KEY)
}

export function homeOf(role) {
  return { CORE_ENTERPRISE: '/core', SUPPLIER: '/supplier', BANK: '/bank' }[role] || '/dashboard'
}
