<script setup lang="ts">
import { watch } from 'vue'
import { useRoute } from 'vue-router'
import { useAppStore } from '@/stores/app'
import TheSideMenu from './TheSideMenu.vue'

/** 侧栏容器：桌面固定可折叠，断点以下为抽屉（遮罩 + 滑入），两态共用菜单主体 */
const route = useRoute()
const appStore = useAppStore()

watch(
  () => route.path,
  () => appStore.closeSidebar(),
)
</script>

<template>
  <aside
    v-show="!appStore.sidebarCollapsed"
    class="border-r hidden lg:flex flex-col shrink-0 overflow-hidden"
    style="width: var(--lw-sidebar-w); background-color: var(--lw-surface-raised); border-color: var(--lw-border)"
  >
    <TheSideMenu />
  </aside>

  <!-- 移动端抽屉：Teleport 到 body，避免受主区布局裁剪 -->
  <Teleport to="body">
    <transition name="lw-fade">
      <div
        v-if="appStore.sidebarOpen"
        class="fixed inset-0 z-30 lg:hidden"
        style="background: var(--lw-overlay)"
        @click="appStore.closeSidebar()"
      />
    </transition>
    <transition name="lw-slide">
      <aside
        v-if="appStore.sidebarOpen"
        class="fixed inset-y-0 left-0 z-40 lg:hidden flex flex-col shadow-lg"
        style="width: var(--lw-sidebar-w); background-color: var(--lw-surface-raised)"
      >
        <TheSideMenu />
      </aside>
    </transition>
  </Teleport>
</template>
