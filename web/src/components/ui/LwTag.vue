<script setup lang="ts">
import { computed } from 'vue'

/** 状态标签：低饱和语义色，圆角 6px */
type Tone = 'primary' | 'secondary' | 'success' | 'warning' | 'error' | 'muted'

const props = withDefaults(defineProps<{ tone?: Tone }>(), { tone: 'primary' })

const style = computed(() => {
  const map: Record<Tone, { color: string; bg: string }> = {
    primary: { color: 'var(--lw-primary)', bg: 'var(--lw-primary-tint)' },
    secondary: { color: 'var(--lw-secondary)', bg: 'var(--lw-secondary-tint)' },
    success: { color: '#3d8f62', bg: 'rgba(90, 191, 138, 0.14)' },
    warning: { color: '#a97e33', bg: 'rgba(214, 168, 92, 0.16)' },
    error: { color: 'var(--lw-error)', bg: 'rgba(212, 106, 106, 0.14)' },
    muted: { color: 'var(--lw-ink-muted)', bg: 'rgba(100, 116, 139, 0.12)' },
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
