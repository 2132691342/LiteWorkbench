<script setup lang="ts">
import { Box } from '@element-plus/icons-vue'
import { useI18n } from 'vue-i18n'

/** 空态：插画 + 说明 + 可选主操作 */
defineProps<{
  /** 自定义说明文案 */
  description?: string
}>()

const { t } = useI18n()
</script>

<template>
  <div class="flex flex-col items-center justify-center gap-3 py-10 text-center">
    <div class="w-14 h-14 rounded-2xl flex items-center justify-center" style="background: var(--lw-primary-tint)">
      <el-icon :size="26" color="var(--lw-primary)"><Box /></el-icon>
    </div>
    <p class="text-sm text-lw-muted">{{ description || t('common.empty') }}</p>
    <slot />
  </div>
</template>
