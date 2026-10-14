<script setup lang="ts">
import type { Component } from 'vue'
import { computed } from 'vue'

/** 指标卡：右上淡底图标瓷砖 + 装饰大图标，悬停上移并叠加浮层阴影 */
interface Props {
  label: string
  value: string
  /** 页脚一行文字，12px 加粗，用于补充口径 */
  hint?: string
  /** 页脚语义色：成功 / 警示 / 错误，缺省为中性 */
  hintTone?: 'muted' | 'success' | 'warning' | 'error'
  tone?: 'primary' | 'secondary' | 'ink'
  icon: Component
}

const props = withDefaults(defineProps<Props>(), {
  hint: '',
  hintTone: 'muted',
  tone: 'primary',
})

const tileClass = computed(() => {
  switch (props.tone) {
    case 'secondary':
      return 'bg-lw-secondaryTint text-lw-secondary'
    case 'ink':
      return 'bg-lw-secondaryTint text-lw-ink'
    default:
      return 'bg-lw-primaryTint text-lw-primary'
  }
})

/** 装饰大图标只继承瓷砖的主色，透明度由 opacity-10 统一控制 */
const iconClass = computed(() => (props.tone === 'secondary' ? 'text-lw-secondary' : 'text-lw-primary'))

const hintClass = computed(() => {
  switch (props.hintTone) {
    case 'success':
      return 'text-lw-successInk'
    case 'warning':
      return 'text-lw-warningInk'
    case 'error':
      return 'text-lw-errorInk'
    default:
      return 'text-lw-muted'
  }
})
</script>

<template>
  <div
    class="lw-card p-5 h-32 relative overflow-hidden group transition-all duration-200 hover:lw-shadow-md hover:-translate-y-0.5"
  >
    <div class="absolute right-0 top-0 p-4 opacity-10 transition-transform duration-200 group-hover:scale-110" :class="iconClass">
      <el-icon :size="56"><component :is="icon" /></el-icon>
    </div>
    <div class="flex justify-between items-start relative z-10">
      <div class="text-sm font-bold text-lw-muted">{{ label }}</div>
      <div class="p-1.5 rounded-md shrink-0" :class="tileClass">
        <el-icon :size="16"><component :is="icon" /></el-icon>
      </div>
    </div>
    <div class="relative z-10 mt-2">
      <div class="text-2xl font-bold tracking-tight text-lw-ink">{{ value }}</div>
      <div v-if="hint" class="text-xs font-bold mt-1 truncate" :class="hintClass">{{ hint }}</div>
    </div>
  </div>
</template>
