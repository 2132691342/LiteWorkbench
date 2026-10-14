import ElementPlus from 'element-plus'
import { createPinia } from 'pinia'
import { createApp } from 'vue'
import App from './App.vue'
import { setupPermDirective } from './directives/perm'
import { i18n } from './locales'
import router from './router'
import 'element-plus/dist/index.css'
import 'highlight.js/styles/github.css'
import 'virtual:uno.css'
import '@/styles/index.css'

/** 应用入口：Pinia → Router → i18n → Element Plus → 指令与图标注册 */
const app = createApp(App)

/** 错误统一收集到 window.__vueErrs，便于无控制台环境排障 */
function collectError(message: string): void {
  const errors = ((window as unknown as Record<string, unknown>).__vueErrs ??= []) as string[]
  errors.push(message)
  if (errors.length > 50) {
    errors.shift()
  }
}

app.config.errorHandler = (err, _instance, info) => {
  collectError(`${String((err as Error)?.message ?? err)} | ${info} | ${(err as Error)?.stack?.slice(0, 400) ?? ''}`)
  console.error(err)
}

window.addEventListener('unhandledrejection', (event) => {
  collectError(`unhandledrejection | ${String((event.reason as Error)?.message ?? event.reason)}`)
})

app.use(createPinia())
app.use(router)
app.use(i18n)
app.use(ElementPlus)
setupPermDirective(app)

// 图标一律按需具名引入（见 components/layout/icons.ts）；全量注册会把千余个图标打包进主 chunk
app.mount('#app')
