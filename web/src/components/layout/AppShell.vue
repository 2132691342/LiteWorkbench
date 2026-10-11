<script setup lang="ts">
import TheSideBar from './TheSideBar.vue'
import TheTopBar from './TheTopBar.vue'

/** 应用外壳：顶栏固定 + 侧栏固定 + 主区滚动，断点以下侧栏收起 */
</script>

<template>
  <div class="flex flex-col h-full" style="background-color: var(--lw-surface)">
    <TheTopBar />
    <div class="flex flex-1 min-h-0">
      <TheSideBar />
      <main class="flex-1 min-w-0 overflow-auto">
        <router-view v-slot="{ Component }">
          <transition name="lw-fade" mode="out-in">
            <component :is="Component" />
          </transition>
        </router-view>
      </main>
    </div>
  </div>
</template>
