<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import type { PlanStep } from '@/types/api'

/** 计划清单：规划器写法的一览，完成度带进度条，状态变化有过渡 */
const props = defineProps<{
  /** 计划步骤，按 index 正序 */
  steps: PlanStep[]
}>()

const { t } = useI18n()

const doneCount = computed(() => props.steps.filter((step) => step.status === 'done').length)

const progress = computed(() =>
  props.steps.length === 0 ? 0 : Math.round((doneCount.value / props.steps.length) * 100))

function mark(step: PlanStep): string {
  if (step.status === 'done') return '✓'
  if (step.status === 'doing') return '◐'
  return '○'
}

function stepStyle(step: PlanStep): Record<string, string> {
  if (step.status === 'done') {
    return { color: 'var(--lw-ink-muted)', textDecoration: 'line-through' }
  }
  if (step.status === 'doing') {
    return { color: 'var(--lw-primary-ink)', fontWeight: '600' }
  }
  return { color: 'var(--lw-ink)' }
}
</script>

<template>
  <div class="plan-card">
    <div class="flex items-center justify-between gap-2">
      <span class="text-xs font-bold" style="color: var(--lw-primary-ink)">{{ t('console.planTitle') }}</span>
      <span class="text-[10px] font-mono" style="color: var(--lw-ink-muted)">
        {{ doneCount }}/{{ steps.length }}
      </span>
    </div>

    <div class="h-1 rounded-full overflow-hidden" style="background-color: var(--lw-border)">
      <div
        class="h-full rounded-full transition-all duration-500"
        :style="{ width: `${progress}%`, backgroundColor: 'var(--lw-primary)' }"
      />
    </div>

    <ol class="flex flex-col gap-1 m-0 p-0 list-none">
      <li
        v-for="step in steps"
        :key="step.index"
        class="flex items-start gap-2 text-xs leading-relaxed transition-colors duration-200"
        :style="stepStyle(step)"
      >
        <span class="font-mono shrink-0 w-3">{{ mark(step) }}</span>
        <span>{{ step.content }}</span>
      </li>
    </ol>
  </div>
</template>

<style scoped>
.plan-card {
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
  padding: 0.75rem;
  border-radius: var(--lw-radius-control);
  border: 1px solid var(--lw-secondary);
  background-color: var(--lw-secondary-tint);
}
</style>
