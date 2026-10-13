import type { App, Directive } from 'vue'
import { useUserStore } from '@/stores/user'

/** v-perm 指令：无权限即从 DOM 移除元素，权限码与后端 PermConst 保持一致 */
const perm: Directive<HTMLElement, string | string[]> = {
  mounted(el, binding) {
    const codes = Array.isArray(binding.value) ? binding.value : [binding.value]
    const store = useUserStore()
    if (!codes.some((code) => store.perms.includes(code))) {
      el.parentNode?.removeChild(el)
    }
  },
}

/** 注册全站权限指令 */
export function setupPermDirective(app: App): void {
  app.directive('perm', perm)
}
