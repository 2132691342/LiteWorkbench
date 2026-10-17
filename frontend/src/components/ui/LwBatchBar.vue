/** 批量操作条：占用筛选条位置，选中为空时不渲染；破坏性动作靠右并取错误色 */
<script setup lang="ts">
import { Close } from '@element-plus/icons-vue'
import { useI18n } from 'vue-i18n'

defineProps<{ count: number }>()
defineEmits<{ (e: 'clear'): void }>()

const { t } = useI18n()
</script>

<template>
  <div v-if="count > 0" class="lw-batchbar">
    <span class="lw-batchbar__count">{{ t('table.selected', { n: count }) }}</span>
    <slot />
    <span class="lw-batchbar__sp" />
    <slot name="actions" />
    <button type="button" class="lw-batchbar__clear" @click="$emit('clear')">
      <el-icon :size="12"><Close /></el-icon>
      <span>{{ t('table.clearSelection') }}</span>
    </button>
  </div>
</template>
