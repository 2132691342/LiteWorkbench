<script setup lang="ts">
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute } from 'vue-router'
import { useAppStore } from '@/stores/app'
import { useUserStore } from '@/stores/user'
import type { MenuVO } from '@/types/api'
import { menuIcon } from './icons'

/** 侧栏：菜单树渲染 + 目录展开 + 选中态，底部固定品牌卡片 */
const { t } = useI18n()
const route = useRoute()
const appStore = useAppStore()
const userStore = useUserStore()

const expandedDirs = ref<string[]>([])

/** 一级可见菜单 */
const primaryMenus = computed(() =>
  userStore.menus.filter((item) => item.menuType !== 'button' && item.visible),
)

/** 目录是否展开 */
function isExpanded(menu: MenuVO): boolean {
  return expandedDirs.value.includes(menu.menuId) || isBranchActive(menu)
}

/** 分支内是否有命中路由 */
function isBranchActive(menu: MenuVO): boolean {
  if (isActive(menu)) {
    return true
  }
  return (menu.children ?? []).some((child) => isBranchActive(child))
}

/** 菜单是否命中当前路由 */
function isActive(menu: MenuVO): boolean {
  return Boolean(menu.routePath) && route.path.startsWith(menu.routePath as string)
}

/** 目录点击：展开或收起 */
function handleClick(menu: MenuVO): void {
  if (menu.children?.length) {
    const index = expandedDirs.value.indexOf(menu.menuId)
    if (index >= 0) {
      expandedDirs.value.splice(index, 1)
    } else {
      expandedDirs.value.push(menu.menuId)
    }
  }
}
</script>

<template>
  <aside
    v-show="!appStore.sidebarCollapsed"
    class="border-r hidden lg:flex flex-col shrink-0 overflow-hidden"
    style="width: var(--lw-sidebar-w); background-color: var(--lw-surface-raised); border-color: var(--lw-border)"
  >
    <div class="flex-1 overflow-auto p-4 flex flex-col gap-1">
      <div class="text-[10px] font-bold uppercase tracking-wider mb-2 px-3 mt-1" style="color: var(--lw-ink-muted)">
        {{ t('common.all') }}
      </div>

      <template v-for="menu in primaryMenus" :key="menu.menuId">
        <div
          v-if="menu.children?.length"
          class="h-10 rounded-lg flex items-center px-3 gap-3 cursor-pointer transition-all duration-200 hover:bg-black/5"
          :style="isBranchActive(menu) ? 'color: var(--lw-primary); font-weight: 600' : 'color: var(--lw-ink)'"
          @click="handleClick(menu)"
        >
          <el-icon :size="16"><component :is="menuIcon(menu.icon)" /></el-icon>
          <span class="text-sm">{{ t(menu.menuName) }}</span>
          <el-icon :size="12" class="ml-auto transition-transform duration-200" :class="{ 'rotate-90': isExpanded(menu) }">
            <component :is="menuIcon('list')" />
          </el-icon>
        </div>

        <template v-if="menu.children?.length && isExpanded(menu)">
          <router-link
            v-for="child in menu.children.filter((item) => item.menuType !== 'button' && item.visible)"
            :key="child.menuId"
            :to="child.routePath ?? '/'"
            class="h-9 rounded-lg flex items-center pl-9 pr-3 gap-2 text-sm transition-all duration-200 hover:bg-black/5"
            :style="
              isActive(child)
                ? 'color: var(--lw-primary); font-weight: 600; background: var(--lw-primary-tint)'
                : 'color: var(--lw-ink)'
            "
          >
            {{ t(child.menuName) }}
          </router-link>
        </template>

        <router-link
          v-else-if="menu.routePath"
          :to="menu.routePath"
          class="h-10 rounded-lg flex items-center px-3 gap-3 text-sm transition-all duration-200 hover:bg-black/5"
          :style="
            isActive(menu)
              ? 'color: var(--lw-primary); font-weight: 600; background: var(--lw-primary-tint)'
              : 'color: var(--lw-ink)'
          "
        >
          <el-icon :size="16"><component :is="menuIcon(menu.icon)" /></el-icon>
          <span>{{ t(menu.menuName) }}</span>
        </router-link>
      </template>
    </div>

    <div class="p-4">
      <div
        class="rounded-xl relative overflow-hidden p-4 shadow-sm transition-transform duration-200 hover:scale-[1.02]"
        style="background-color: var(--lw-primary)"
      >
        <div class="absolute inset-0 bg-white opacity-10" />
        <div class="relative z-10" style="color: var(--lw-on-primary)">
          <div class="text-xs font-bold mb-1">{{ t('app.title') }}</div>
          <div class="text-[10px] opacity-90 leading-relaxed mb-3">{{ t('app.slogan') }}</div>
          <div class="text-[10px] font-mono opacity-80">
            {{ userStore.user?.username }} · {{ userStore.roleCodes[0] ?? '-' }}
          </div>
        </div>
      </div>
    </div>
  </aside>
</template>
