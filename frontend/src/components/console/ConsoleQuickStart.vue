<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'

/** 工作台空态引导：三个创作入口 + 工具能力说明，避免首屏只留一句占位文案 */
const { t } = useI18n()
const router = useRouter()

/** 创作闭环的三个入口 */
const entries = [
  { route: '/writer', titleKey: 'console.goWriter', descKey: 'console.goWriterDesc' },
  { route: '/prompt', titleKey: 'console.goPrompt', descKey: 'console.goPromptDesc' },
  { route: '/kb', titleKey: 'console.goKb', descKey: 'console.goKbDesc' },
]
</script>

<template>
  <div class="max-w-lg w-full flex flex-col gap-4">
    <div class="text-center">
      <h3 class="text-base font-bold" style="color: var(--lw-primary)">{{ t('console.quickTitle') }}</h3>
      <p class="text-xs mt-1" style="color: var(--lw-ink-muted)">{{ t('console.quickDesc') }}</p>
    </div>
    <div class="grid grid-cols-1 sm:grid-cols-3 gap-3">
      <button
        v-for="entry in entries"
        :key="entry.route"
        class="p-4 rounded-xl border text-left flex flex-col gap-2 transition-all duration-200 hover:-translate-y-0.5 hover:lw-shadow-md"
        style="background: var(--lw-surface-raised); border-color: var(--lw-border)"
        @click="router.push(entry.route)"
      >
        <span class="text-sm font-bold" style="color: var(--lw-primary)">{{ t(entry.titleKey) }}</span>
        <span class="text-xs" style="color: var(--lw-ink-muted)">{{ t(entry.descKey) }}</span>
      </button>
    </div>
    <p class="text-xs text-center" style="color: var(--lw-ink-muted)">{{ t('console.toolsHint') }}</p>
  </div>
</template>
