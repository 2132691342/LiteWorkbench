import { defineStore } from 'pinia'
import { ref } from 'vue'

/** 应用外壳状态：桌面侧栏折叠、移动端抽屉开关、语言 */
export const useAppStore = defineStore('app', () => {
  const sidebarCollapsed = ref(false)
  const sidebarOpen = ref(false)
  const locale = ref(localStorage.getItem('lw-locale') ?? 'zh-CN')

  /** 切换桌面侧栏折叠 */
  function toggleSidebar(): void {
    sidebarCollapsed.value = !sidebarCollapsed.value
  }

  /** 打开移动端抽屉 */
  function openSidebar(): void {
    sidebarOpen.value = true
  }

  /** 收起移动端抽屉 */
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
