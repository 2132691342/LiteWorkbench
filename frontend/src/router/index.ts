import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'
import type { Component } from 'vue'
import { useUserStore } from '@/stores/user'
import type { MenuVO } from '@/types/api'

/** 路由：静态页 + 后端菜单驱动的动态页 + 登录与权限守卫 */

const viewModules = import.meta.glob<{ default: Component }>('../views/**/*.vue')

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
    redirect: '/dashboard',
    children: [],
  },
  {
    path: '/:pathMatch(.*)*',
    name: 'not-found',
    component: () => import('@/views/NotFound.vue'),
  },
]

const router = createRouter({
  history: createWebHistory(),
  routes: staticRoutes,
})

/** 动态菜单注册状态：登录后首次进入必须注册，否则目标地址命中兜底 404 */
const dynamicRouteNames = new Set<string>()
let routesRegistered = false

/** 把后端菜单树注册为 shell 的子路由，component 字段映射到 views 目录 */
function registerRoutes(menus: MenuVO[]): void {
  resetDynamicRoutes()
  addMenus(menus)
  routesRegistered = true
}

function addMenus(menus: MenuVO[]): void {
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
        dynamicRouteNames.add(menu.menuId)
      }
    }
    if (menu.children?.length) {
      addMenus(menu.children)
    }
  }
}

/** 回收动态路由，登出与切换账号时使用 */
function resetDynamicRoutes(): void {
  for (const name of dynamicRouteNames) {
    if (router.hasRoute(name)) {
      router.removeRoute(name)
    }
  }
  dynamicRouteNames.clear()
  routesRegistered = false
}

router.beforeEach(async (to) => {
  const store = useUserStore()
  if (!store.isLogin) {
    resetDynamicRoutes()
    if (to.meta.public) {
      return true
    }
    return { path: '/login', query: { redirect: to.fullPath } }
  }
  if (to.meta.public) {
    return true
  }
  if (!store.loaded) {
    try {
      await store.loadCurrent()
    } catch {
      store.reset()
      return { path: '/login', query: { redirect: to.fullPath } }
    }
  }
  if (!routesRegistered) {
    registerRoutes(store.menus)
    return { path: to.fullPath, replace: true }
  }
  const perm = to.meta.perm
  if (typeof perm === 'string' && perm && !store.perms.includes(perm)) {
    return { path: '/not-found', replace: true }
  }
  return true
})

export default router
