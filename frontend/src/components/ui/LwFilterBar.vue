/** 管理后台筛选条：一行控件统一 h-10，主操作贴右，窄屏换行 */
<script setup lang="ts">
defineProps<{
  /** 左侧说明文案，12px 加粗 muted，缺省不占位 */
  label?: string
}>()
</script>

<template>
  <div class="lw-filterbar">
    <span v-if="label" class="lw-filterbar__label">{{ label }}</span>
    <slot />
    <span class="lw-filterbar__sp" />
    <slot name="actions" />
  </div>
</template>
