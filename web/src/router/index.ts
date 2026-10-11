import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'
import { useUserStore } from '@/stores/user'
import type { MenuVO } from '@/types/api'

/** 路由：静态页 + 后端菜单驱动的动态页 + 登录与权限守卫 */

const viewModules = import.meta.glob('../views/**/*.vue')

const staticRoutes: RouteRecordRaw[] = [
  {
    path: '/login',
    name: 'login',
    component: () => import('@/views/Login.vue'),
    meta: { public: true },
  },
  {
    path: '/',
    name: 'shell',
    component: () => import('@/components/layout/AppShell.vue'),
    children: [],
  },
]

const router = createRouter({
  history: createWebHistory(),
  routes: staticRoutes,
})

let fallbackRegistered = false

/** 把后端菜单树注册为 shell 的子路由，component 字段映射到 views 目录 */
function registerRoutes(menus: MenuVO[]): void {
  for (const menu of menus) {
    if (menu.menuType === 'menu' && menu.routePath && menu.component) {
      const loader = viewModules[`../views/${menu.component}.vue`]
      if (loader && !router.hasRoute(menu.menuId)) {
        router.addRoute('shell', {
          path: menu.routePath,
          name: menu.menuId,
          component: loader,
          meta: { title: menu.menuName, perm: menu.permCode },
        })
      }
    }
    if (menu.children?.length) {
      registerRoutes(menu.children)
    }
  }
  if (!fallbackRegistered) {
    router.addRoute({
      path: '/:pathMatch(.*)*',
      name: 'not-found',
      component: () => import('@/views/NotFound.vue'),
    })
    fallbackRegistered = true
  }
}

router.beforeEach(async (to) => {
  const store = useUserStore()
  if (to.meta.public) {
    return true
  }
  if (!store.isLogin) {
    return { path: '/login', query: { redirect: to.fullPath } }
  }
  if (!store.loaded) {
    try {
      await store.loadCurrent()
    } catch {
      store.reset()
      return { path: '/login' }
    }
    registerRoutes(store.menus)
    return { path: to.fullPath, replace: true }
  }
  return true
})

export default router
