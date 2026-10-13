<script setup lang="ts">
/** 错误页共用骨架：大号数字 + 说明 + 动作区，403 / 404 / 500 复用 */
withDefaults(defineProps<{
  code: string
  title: string
  description?: string
}>(), { description: '' })
</script>

<template>
  <div class="lw-page items-center justify-center gap-4 text-center" style="min-height: 60vh">
    <div class="text-5xl font-extrabold tracking-tight" style="color: var(--lw-secondary)">{{ code }}</div>
    <h2 class="text-base font-bold" style="color: var(--lw-ink)">{{ title }}</h2>
    <p v-if="description" class="text-sm max-w-md leading-relaxed" style="color: var(--lw-ink-muted)">
      {{ description }}
    </p>
    <div class="flex items-center gap-3">
      <slot />
    </div>
  </div>
</template>
