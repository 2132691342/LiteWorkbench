<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'

/** 工作台空态引导：三个创作入口 + 工具能力说明，避免首屏只留一句占位文案 */
const { t } = useI18n()
const router = useRouter()

const entries = [
  {
    route: '/writer',
    titleKey: 'console.goWriter',
    descKey: 'console.goWriterDesc',
    icon: 'M12 20h9M16.5 3.5a2.121 2.121 0 0 1 3 3L7 19l-4 1 1-4L16.5 3.5z',
  },
  {
    route: '/skills',
    titleKey: 'console.goSkill',
    descKey: 'console.goSkillDesc',
    icon: 'M4 7V4h16v3M9 20h6M12 4v16',
  },
  {
    route: '/kb',
    titleKey: 'console.goKb',
    descKey: 'console.goKbDesc',
    icon: 'M4 19.5A2.5 2.5 0 0 1 6.5 17H20M6.5 2H20v20H6.5A2.5 2.5 0 0 1 4 19.5v-15A2.5 2.5 0 0 1 6.5 2z',
  },
]
</script>

<template>
  <div class="max-w-lg w-full flex flex-col gap-5">
    <div class="text-center">
      <div class="w-12 h-12 rounded-card mx-auto flex items-center justify-center lw-shadow-sm transition-transform duration-200 hover:scale-105"
           style="background: var(--lw-secondary)">
        <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"
             stroke-linecap="round" stroke-linejoin="round" style="color: var(--lw-on-primary)">
          <path d="m12 3-1.912 5.813a2 2 0 0 1-1.275 1.275L3 12l5.813 1.912a2 2 0 0 1 1.275 1.275L12 21l1.912-5.813a2 2 0 0 1 1.275-1.275L21 12l-5.813-1.912a2 2 0 0 1-1.275-1.275L12 3Z" />
        </svg>
      </div>
      <h3 class="text-base font-bold mt-3" style="color: var(--lw-primary-ink)">{{ t('console.quickTitle') }}</h3>
      <p class="text-xs mt-1" style="color: var(--lw-ink-muted)">{{ t('console.quickDesc') }}</p>
    </div>
    <div class="grid grid-cols-1 sm:grid-cols-3 gap-3">
      <button
        v-for="entry in entries"
        :key="entry.route"
        class="p-4 rounded-card border text-left flex flex-col gap-2 transition-all duration-200 hover:-translate-y-0.5 hover:lw-shadow-md active:scale-[0.98]"
        style="background: var(--lw-surface-raised); border-color: var(--lw-border)"
        @click="router.push(entry.route)"
      >
        <span class="w-9 h-9 rounded-lg flex items-center justify-center"
              style="background: var(--lw-primary-tint); color: var(--lw-primary-ink)">
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"
               stroke-linecap="round" stroke-linejoin="round">
            <path :d="entry.icon" />
          </svg>
        </span>
        <span class="text-sm font-bold" style="color: var(--lw-primary-ink)">{{ t(entry.titleKey) }}</span>
        <span class="text-xs leading-relaxed" style="color: var(--lw-ink-muted)">{{ t(entry.descKey) }}</span>
      </button>
    </div>
    <p class="text-xs text-center" style="color: var(--lw-ink-muted)">{{ t('console.toolsHint') }}</p>
  </div>
</template>
