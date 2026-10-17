<script setup lang="ts">
import { ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { useAppStore } from '@/stores/app'
import TheSideMenu from './TheSideMenu.vue'

/** 侧栏容器：桌面固定在 208px 与 64px 图标栏之间切换，断点以下为抽屉（遮罩 + 滑入） */
const route = useRoute()
const appStore = useAppStore()
const expandedDirs = ref<string[]>([])

watch(
  () => route.path,
  () => appStore.closeSidebar(),
)
</script>

<template>
  <aside
    class="border-r hidden lg:flex flex-col shrink-0 overflow-hidden lw-sidebar"
    :class="{ 'is-collapsed': appStore.sidebarCollapsed }"
    style="background-color: var(--lw-surface-raised); border-color: var(--lw-border)"
  >
    <TheSideMenu v-model:expanded="expandedDirs" :collapsed="appStore.sidebarCollapsed" @expand="appStore.toggleSidebar()" />
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
        class="fixed inset-y-0 left-0 z-40 lg:hidden flex flex-col lw-shadow-md"
        style="width: var(--lw-sidebar-w); background-color: var(--lw-surface-raised)"
      >
        <TheSideMenu v-model:expanded="expandedDirs" />
      </aside>
    </transition>
  </Teleport>
</template>
