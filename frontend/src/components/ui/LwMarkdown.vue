<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { renderMarkdown } from '@/utils/markdown'

/** 助手消息 Markdown 渲染：代码高亮 + 一键复制（事件委托，流式增量安全） */
const props = defineProps<{ content: string }>()

const rootEl = ref<HTMLElement | null>(null)
const html = computed(() => renderMarkdown(props.content))

/** 点击复制按钮时，取最近代码块文本写入剪贴板并短暂反馈 */
function handleClick(event: MouseEvent): void {
  const target = event.target as HTMLElement | null
  const button = target?.closest('[data-md-copy]') as HTMLButtonElement | null
  if (!button) return
  const code = button.closest('.md-code')?.querySelector('pre')?.textContent ?? ''
  void navigator.clipboard.writeText(code)
  button.classList.add('is-copied')
  window.setTimeout(() => button.classList.remove('is-copied'), 1200)
}

onMounted(() => rootEl.value?.addEventListener('click', handleClick))
onBeforeUnmount(() => rootEl.value?.removeEventListener('click', handleClick))
</script>

<template>
  <div ref="rootEl" class="lw-md" v-html="html" />
</template>
