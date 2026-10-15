<script setup lang="ts">
import { WarningFilled } from '@element-plus/icons-vue'
import { useI18n } from 'vue-i18n'
import LwButton from './LwButton.vue'

/** 错误条：内联展示 + 可选重试，不弹全屏遮罩 */
withDefaults(
  defineProps<{
    message?: string
    /** 表单类错误无可重放的动作，置 false 即收起重试按钮 */
    retryable?: boolean
  }>(),
  { retryable: true },
)

defineEmits<{ (e: 'retry'): void }>()

const { t } = useI18n()
</script>

<template>
  <div
    class="flex items-center justify-between gap-3 px-4 py-3 rounded-control border"
    style="border-color: var(--lw-error); background: var(--lw-error-tint)"
  >
    <div class="flex items-center gap-2 text-sm" style="color: var(--lw-error)">
      <el-icon><WarningFilled /></el-icon>
      <span>{{ message || t('common.failed') }}</span>
    </div>
    <LwButton v-if="retryable" variant="text" size="sm" @click="$emit('retry')">{{ t('common.retry') }}</LwButton>
  </div>
</template>
