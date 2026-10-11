import * as ElementPlusIcons from '@element-plus/icons-vue'
import ElementPlus from 'element-plus'
import { createPinia } from 'pinia'
import { createApp } from 'vue'
import App from './App.vue'
import { i18n } from './locales'
import router from './router'
import 'element-plus/dist/index.css'
import 'highlight.js/styles/github.css'
import 'virtual:uno.css'
import '@/styles/index.css'

/** 应用入口：Pinia → Router → i18n → Element Plus → 图标全量注册 */
const app = createApp(App)

// 组件错误统一上抛到 window.__vueErrs，便于无控制台环境排障
app.config.errorHandler = (err, _instance, info) => {
  const errors = ((window as unknown as Record<string, unknown>).__vueErrs ??= []) as string[]
  errors.push(`${String((err as Error)?.message ?? err)} | ${info} | ${(err as Error)?.stack?.slice(0, 400) ?? ''}`)
  console.error(err)
}

app.use(createPinia())
app.use(router)
app.use(i18n)
app.use(ElementPlus)

for (const [name, component] of Object.entries(ElementPlusIcons)) {
  app.component(name, component)
}

app.mount('#app')
