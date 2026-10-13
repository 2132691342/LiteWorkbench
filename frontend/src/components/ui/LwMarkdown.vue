<script setup lang="ts">
import { ElMessage } from 'element-plus'
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { renderMarkdown } from '@/utils/markdown'

/** 助手消息 Markdown 渲染：代码高亮 + 一键复制（事件委托，流式增量安全） */
const props = defineProps<{ content: string }>()
const { t } = useI18n()

const rootEl = ref<HTMLElement | null>(null)
const html = computed(() => renderMarkdown(props.content))

/** 点击复制按钮时，取最近代码块文本写入剪贴板，对勾反馈 400ms 并 toast 提示 */
function handleClick(event: MouseEvent): void {
  const target = event.target as HTMLElement | null
  const button = target?.closest('[data-md-copy]') as HTMLButtonElement | null
  if (!button) return
  const code = button.closest('.md-code')?.querySelector('pre')?.textContent ?? ''
  void navigator.clipboard
    .writeText(code)
    .then(() => {
      button.classList.add('is-copied')
      window.setTimeout(() => button.classList.remove('is-copied'), 400)
      ElMessage.success(t('common.copied'))
    })
    .catch(() => {
      // 剪贴板不可用（非安全上下文）时静默失败，用户仍可手动选择复制
    })
}

onMounted(() => rootEl.value?.addEventListener('click', handleClick))
onBeforeUnmount(() => rootEl.value?.removeEventListener('click', handleClick))
</script>

<template>
  <div ref="rootEl" class="lw-md" v-html="html" />
</template>
