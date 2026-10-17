<script setup lang="ts">
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute } from 'vue-router'
import { useUserStore } from '@/stores/user'
import type { MenuVO } from '@/types/api'
import { menuLabel } from '@/utils/menu'
import { roleLabel } from '@/utils/role'
import { menuIcon } from './icons'
import { ArrowRight } from '@element-plus/icons-vue'

/** 侧栏菜单主体：菜单树渲染 + 目录展开 + 选中态 + 底部品牌卡片，桌面侧栏与移动抽屉共用 */
const { t } = useI18n()
const route = useRoute()
const userStore = useUserStore()

const expandedDirs = ref<string[]>([])
const currentRoleLabel = computed(() => roleLabel(userStore.roleCodes, t))

const primaryMenus = computed(() =>
  userStore.menus.filter((item) => item.menuType !== 'button' && item.visible),
)

function isExpanded(menu: MenuVO): boolean {
  return expandedDirs.value.includes(menu.menuId) || isBranchActive(menu)
}

function isBranchActive(menu: MenuVO): boolean {
  if (isActive(menu)) {
    return true
  }
  return (menu.children ?? []).some((child) => isBranchActive(child))
}

function isActive(menu: MenuVO): boolean {
  return Boolean(menu.routePath) && route.path.startsWith(menu.routePath as string)
}

function toggleDir(menu: MenuVO): void {
  const index = expandedDirs.value.indexOf(menu.menuId)
  if (index >= 0) {
    expandedDirs.value.splice(index, 1)
  } else {
    expandedDirs.value.push(menu.menuId)
  }
}
</script>

<template>
  <div class="flex flex-col h-full min-h-0">
    <!-- 品牌区：与顶栏同高，让侧栏在视觉上从顶栏下方正式接管导航 -->
    <div class="lw-sidebar__brand">
      <div class="lw-sidebar__brand-mark" aria-hidden="true">
        <svg width="18" height="18" viewBox="0 0 32 32" fill="none">
          <path d="M9 22V10h3v9h6v3H9z" fill="currentColor" />
          <circle cx="22" cy="12" r="2.4" fill="currentColor" opacity="0.85" />
        </svg>
      </div>
      <span class="lw-sidebar__brand-name">{{ t('app.title') }}</span>
    </div>

    <div class="flex-1 overflow-auto p-3 flex flex-col gap-1 min-h-0">
      <div class="lw-label px-2 mb-1 mt-1">{{ t('menu.groupNav') }}</div>

      <template v-for="menu in primaryMenus" :key="menu.menuId">
        <template v-if="menu.children?.length">
          <button
            type="button"
            class="lw-menu__item lw-menu__item--dir"
            :class="{ 'is-active': isBranchActive(menu) }"
            :aria-expanded="isExpanded(menu)"
            @click="toggleDir(menu)"
          >
            <el-icon :size="16"><component :is="menuIcon(menu.icon)" /></el-icon>
            <span>{{ menuLabel(menu.menuName) }}</span>
            <el-icon :size="12" class="lw-menu__caret" :class="{ 'is-open': isExpanded(menu) }">
              <ArrowRight />
            </el-icon>
          </button>

          <div class="lw-collapse" :class="{ 'is-open': isExpanded(menu) }">
            <div class="flex flex-col gap-1 pb-1">
              <router-link
                v-for="child in menu.children.filter((item) => item.menuType !== 'button' && item.visible)"
                :key="child.menuId"
                :to="child.routePath ?? '/'"
                class="lw-menu__item lw-menu__item--leaf pl-9"
                :class="{ 'is-active': isActive(child) }"
              >
                {{ menuLabel(child.menuName) }}
              </router-link>
            </div>
          </div>
        </template>

        <!-- 叶子分支必须显式排除带子节点的目录：v-else-if 挂在折叠容器上，目录收起时仍占位但不可见，不会二次渲染同名条目 -->
        <router-link
          v-else-if="menu.routePath"
          :to="menu.routePath"
          class="lw-menu__item lw-menu__item--leaf"
          :class="{ 'is-active': isActive(menu) }"
        >
          <el-icon :size="16"><component :is="menuIcon(menu.icon)" /></el-icon>
          <span>{{ menuLabel(menu.menuName) }}</span>
        </router-link>
      </template>
    </div>

    <div class="p-3 shrink-0">
      <div
        class="rounded-card relative overflow-hidden p-4 lw-shadow-sm transition-transform duration-200 hover:scale-[1.02]"
        style="background-color: var(--lw-secondary)"
      >
        <div class="absolute inset-0" style="background-color: var(--lw-on-primary); opacity: 0.1" />
        <div class="relative z-10" style="color: var(--lw-on-secondary)">
          <div class="text-xs font-bold mb-1">{{ t('app.title') }}</div>
          <div class="text-[10px] opacity-90 leading-relaxed mb-3">{{ t('app.slogan') }}</div>
          <div class="text-[10px] opacity-90 truncate">
            {{ userStore.user?.username }} · {{ currentRoleLabel }}
          </div>
        </div>
      </div>
    </div>
  </div>
</template>
