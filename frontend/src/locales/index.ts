import { createI18n } from 'vue-i18n'
import { useAppStore } from '@/stores/app'
import enUS from './en-US'
import zhCN from './zh-CN'

/** 国际化入口：中英双语，默认跟随本地存储 */
export const i18n = createI18n({
  legacy: false,
  locale: localStorage.getItem('lw-locale') ?? 'zh-CN',
  fallbackLocale: 'zh-CN',
  messages: {
    'zh-CN': zhCN,
    'en-US': enUS,
  },
})

export function switchLocale(locale: string): void {
  const app = useAppStore()
  app.setLocale(locale)
  i18n.global.locale.value = locale as 'zh-CN' | 'en-US'
}
