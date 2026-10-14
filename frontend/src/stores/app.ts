import { defineStore } from 'pinia'
import { ref } from 'vue'

/** 应用外壳状态：桌面侧栏折叠、移动端抽屉开关、语言 */
export const useAppStore = defineStore('app', () => {
  const sidebarCollapsed = ref(false)
  const sidebarOpen = ref(false)
  const locale = ref(localStorage.getItem('lw-locale') ?? 'zh-CN')

  function toggleSidebar(): void {
    sidebarCollapsed.value = !sidebarCollapsed.value
  }

  function openSidebar(): void {
    sidebarOpen.value = true
  }

  function closeSidebar(): void {
    sidebarOpen.value = false
  }

  /** 切换语言并持久化 */
  function setLocale(next: string): void {
    locale.value = next
    localStorage.setItem('lw-locale', next)
  }

  return { sidebarCollapsed, sidebarOpen, locale, toggleSidebar, openSidebar, closeSidebar, setLocale }
})
