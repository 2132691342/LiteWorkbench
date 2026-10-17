import { defineStore } from 'pinia'
import { ref } from 'vue'

/** 应用外壳状态：桌面侧栏折叠（持久化）、移动端抽屉开关、语言 */
export const useAppStore = defineStore('app', () => {
  const sidebarCollapsed = ref(localStorage.getItem('lw-sidebar-collapsed') === '1')
  const sidebarOpen = ref(false)
  const locale = ref(localStorage.getItem('lw-locale') ?? 'zh-CN')

  /** 折叠状态持久化：刷新后保持，避免每次进站都重新收一遍 */
  function toggleSidebar(): void {
    sidebarCollapsed.value = !sidebarCollapsed.value
    localStorage.setItem('lw-sidebar-collapsed', sidebarCollapsed.value ? '1' : '0')
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
