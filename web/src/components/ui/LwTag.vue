<script setup lang="ts">
import { computed } from 'vue'

/** 状态标签：低饱和语义色，圆角 6px */
type Tone = 'primary' | 'secondary' | 'success' | 'warning' | 'error' | 'muted'

const props = withDefaults(defineProps<{ tone?: Tone }>(), { tone: 'primary' })

const style = computed(() => {
  const map: Record<Tone, { color: string; bg: string }> = {
    primary: { color: 'var(--lw-primary)', bg: 'var(--lw-primary-tint)' },
    secondary: { color: 'var(--lw-secondary)', bg: 'var(--lw-secondary-tint)' },
    success: { color: 'var(--lw-success-ink)', bg: 'var(--lw-success-tint)' },
    warning: { color: 'var(--lw-warning-ink)', bg: 'var(--lw-warning-tint)' },
    error: { color: 'var(--lw-error-ink)', bg: 'var(--lw-error-tint)' },
    muted: { color: 'var(--lw-ink-muted)', bg: 'var(--lw-muted-tint)' },
  }
  const tone = map[props.tone]
  return { color: tone.color, backgroundColor: tone.bg }
})
</script>

<template>
  <span class="inline-flex items-center gap-1 px-2 py-0.5 rounded-tag text-xs font-bold" :style="style">
    <slot />
  </span>
</template>
