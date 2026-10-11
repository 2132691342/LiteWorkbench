import { defineStore } from 'pinia'
import { ref } from 'vue'

/** 应用外壳状态：侧栏折叠、语言 */
export const useAppStore = defineStore('app', () => {
  const sidebarCollapsed = ref(false)
  const locale = ref(localStorage.getItem('lw-locale') ?? 'zh-CN')

  /** 切换侧栏折叠 */
  function toggleSidebar(): void {
    sidebarCollapsed.value = !sidebarCollapsed.value
  }

  /** 切换语言并持久化 */
  function setLocale(next: string): void {
    locale.value = next
    localStorage.setItem('lw-locale', next)
  }

  return { sidebarCollapsed, locale, toggleSidebar, setLocale }
})
