<script setup lang="ts">
import { WarningFilled } from '@element-plus/icons-vue'
import { useI18n } from 'vue-i18n'
import LwButton from './LwButton.vue'

/** 错误条：内联展示 + 重试，不弹全屏遮罩 */
defineProps<{
  /** 错误文案 */
  message?: string
}>()

defineEmits<{ (e: 'retry'): void }>()

const { t } = useI18n()
</script>

<template>
  <div
    class="flex items-center justify-between gap-3 px-4 py-3 rounded-control border"
    style="border-color: var(--lw-error); background: rgba(212, 106, 106, 0.08)"
  >
    <div class="flex items-center gap-2 text-sm" style="color: var(--lw-error)">
      <el-icon><WarningFilled /></el-icon>
      <span>{{ message || t('common.failed') }}</span>
    </div>
    <LwButton variant="text" size="sm" @click="$emit('retry')">{{ t('common.retry') }}</LwButton>
  </div>
</template>
