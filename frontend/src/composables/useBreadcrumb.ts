import { computed } from 'vue'
import { useRoute } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { useUserStore } from '@/stores/user'
import type { MenuVO } from '@/types/api'
import { menuLabel } from '@/utils/menu'

/** 面包屑：沿菜单树回溯当前路由的祖先链，只有一个层级时返回空数组（不渲染半截面包屑） */
export function useBreadcrumb() {
  const route = useRoute()
  const store = useUserStore()
  const { t } = useI18n()

  /** 菜单树里按 routePath 定位，返回从根到叶的路径 */
  function findPath(menus: MenuVO[], target: string, chain: MenuVO[] = []): MenuVO[] {
    for (const menu of menus) {
      const next = [...chain, menu]
      if (menu.routePath === target) return next
      const hit = findPath(menu.children ?? [], target, next)
      if (hit.length) return hit
    }
    return []
  }

  const trail = computed(() => {
    const chain = findPath(store.menus, route.path)
    if (chain.length > 1) {
      return chain.map((menu) => ({ label: menuLabel(menu.menuName), path: menu.routePath ?? '' }))
    }
    // 菜单未命中（如 /403、自定义入口）时退回路由 meta.title 单级，交给模板决定是否渲染
    const title = route.meta.title
    return typeof title === 'string' && title ? [{ label: menuLabel(title), path: '' }] : []
  })

  return { trail, homeLabel: computed(() => t('menu.dashboard')) }
}
