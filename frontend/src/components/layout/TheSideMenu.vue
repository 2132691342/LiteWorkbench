<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute } from 'vue-router'
import { useUserStore } from '@/stores/user'
import type { MenuVO } from '@/types/api'
import { menuLabel } from '@/utils/menu'
import { roleLabel } from '@/utils/role'
import { menuIcon } from './icons'
import { ArrowRight } from '@element-plus/icons-vue'

/** 侧栏菜单主体：菜单树渲染 + 目录展开 + 选中态 + 底部品牌卡片，展开态与折叠态共用 */
const props = withDefaults(defineProps<{ collapsed?: boolean }>(), { collapsed: false })
const emit = defineEmits<{ (e: 'expand'): void }>()

const { t } = useI18n()
const route = useRoute()
const userStore = useUserStore()

const expandedDirs = defineModel<string[]>('expanded', { default: () => [] })
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

/** 折叠态点目录：先还原侧栏再展开，否则用户看不到刚展开的子项 */
function toggleDir(menu: MenuVO): void {
  if (props.collapsed) {
    emit('expand')
  }
  const index = expandedDirs.value.indexOf(menu.menuId)
  if (index >= 0) {
    expandedDirs.value.splice(index, 1)
  } else {
    expandedDirs.value.push(menu.menuId)
  }
}

function visibleChildren(menu: MenuVO): MenuVO[] {
  return (menu.children ?? []).filter((item) => item.menuType !== 'button' && item.visible)
}
</script>

<template>
  <div class="flex flex-col h-full min-h-0" :class="{ 'is-collapsed': collapsed }">
    <!-- 品牌区：与顶栏同高，折叠时只留方块 -->
    <div class="lw-sidebar__brand">
      <div class="lw-sidebar__brand-mark" aria-hidden="true">
        <svg width="18" height="18" viewBox="0 0 32 32" fill="none">
          <path d="M9 22V10h3v9h6v3H9z" fill="currentColor" />
          <circle cx="22" cy="12" r="2.4" fill="currentColor" opacity="0.85" />
        </svg>
      </div>
      <span v-if="!collapsed" class="lw-sidebar__brand-name">{{ t('app.title') }}</span>
    </div>

    <div class="flex-1 overflow-auto p-3 flex flex-col gap-1 min-h-0">
      <div v-if="!collapsed" class="lw-label px-2 mb-1 mt-1">{{ t('menu.groupNav') }}</div>

      <template v-for="menu in primaryMenus" :key="menu.menuId">
        <template v-if="visibleChildren(menu).length">
          <button
            type="button"
            class="lw-menu__item lw-menu__item--dir"
            :class="{ 'is-active': isBranchActive(menu) }"
            :aria-expanded="isExpanded(menu)"
            :title="collapsed ? menuLabel(menu.menuName) : undefined"
            @click="toggleDir(menu)"
          >
            <el-icon :size="16"><component :is="menuIcon(menu.icon)" /></el-icon>
            <template v-if="!collapsed">
              <span>{{ menuLabel(menu.menuName) }}</span>
              <el-icon :size="12" class="lw-menu__caret" :class="{ 'is-open': isExpanded(menu) }">
                <ArrowRight />
              </el-icon>
            </template>
          </button>

          <div v-if="!collapsed" class="lw-collapse" :class="{ 'is-open': isExpanded(menu) }">
            <div class="flex flex-col gap-1 pb-1">
              <router-link
                v-for="child in visibleChildren(menu)"
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
        <el-tooltip
          v-else-if="menu.routePath"
          :content="menuLabel(menu.menuName)"
          placement="right"
          :disabled="!collapsed"
          :show-after="300"
        >
          <router-link
            :to="menu.routePath"
            class="lw-menu__item lw-menu__item--leaf"
            :class="{ 'is-active': isActive(menu) }"
          >
            <el-icon :size="16"><component :is="menuIcon(menu.icon)" /></el-icon>
            <span v-if="!collapsed">{{ menuLabel(menu.menuName) }}</span>
          </router-link>
        </el-tooltip>
      </template>
    </div>

    <!-- 底部品牌卡折叠时收起：64px 宽度里塞三段文字只会变成三行省略号 -->
    <div v-if="!collapsed" class="p-3 shrink-0">
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
