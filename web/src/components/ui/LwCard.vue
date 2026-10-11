<script setup lang="ts">
/** 卡片容器：可选标题、说明与右上操作区 */
defineProps<{
  /** 卡片标题 */
  title?: string
  /** 标题下的说明 */
  desc?: string
}>()
</script>

<template>
  <section class="lw-card p-5 flex flex-col gap-4">
    <header v-if="title || $slots.header || $slots.actions" class="flex items-start justify-between gap-3">
      <slot name="header">
        <div>
          <h3 class="font-bold text-base text-lw-ink">{{ title }}</h3>
          <p v-if="desc" class="text-xs text-lw-muted mt-1">{{ desc }}</p>
        </div>
      </slot>
      <div class="flex items-center gap-2 shrink-0">
        <slot name="actions" />
      </div>
    </header>
    <slot />
  </section>
</template>
