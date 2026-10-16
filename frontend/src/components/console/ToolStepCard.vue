<script setup lang="ts">
import { computed, ref } from 'vue'
import LwTag from '@/components/ui/LwTag.vue'

/** 工具调用卡：状态随 phase 在执行中 / 完成 / 失败之间过渡，输出可折叠且超长截断 */
const props = defineProps<{
  /** 工具中文名，由宿主按工具码映射 */
  label: string
  phase: 'start' | 'end'
  output?: string
  isError?: boolean
}>()

/** 输出超过该字节数即截断，避免一次工具返回把时间线顶飞 */
const MAX_OUTPUT = 4096

const open = ref(false)

const failed = computed(() => Boolean(props.isError))
const running = computed(() => props.phase === 'start' && !failed.value)

const hasOutput = computed(() => (props.output ?? '').length > 0)

const shownOutput = computed(() => {
  const raw = props.output ?? ''
  return raw.length > MAX_OUTPUT ? `${raw.slice(0, MAX_OUTPUT)}\n… ${raw.length - MAX_OUTPUT} chars truncated` : raw
})

const tone = computed<'primary' | 'success' | 'error'>(() => {
  if (failed.value) return 'error'
  return props.phase === 'end' ? 'success' : 'primary'
})

const statusText = computed(() => {
  if (failed.value) return 'console.toolFailed'
  return props.phase === 'end' ? 'console.toolResult' : 'console.toolCall'
})
</script>

<template>
  <div class="tl-tool" :class="{ 'is-error': failed }">
    <button type="button" class="tl-tool__head" :aria-expanded="open" @click="open = !open">
      <span class="tl-tool__icon" :class="{ 'is-running': running, 'is-error': failed }">
        <span v-if="running" class="lw-spinner" />
        <svg v-else-if="failed" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor"
             stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round">
          <circle cx="12" cy="12" r="10" />
          <path d="m15 9-6 6M9 9l6 6" />
        </svg>
        <svg v-else width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5"
             stroke-linecap="round" stroke-linejoin="round">
          <path d="M20 6 9 17l-5-5" />
        </svg>
      </span>
      <span class="tl-tool__name">{{ props.label }}</span>
      <LwTag :tone="tone">{{ $t(statusText) }}</LwTag>
      <span v-if="hasOutput" class="tl-tool__arrow" :class="{ 'is-open': open }">
        <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5"
             stroke-linecap="round" stroke-linejoin="round">
          <path d="m6 9 6 6 6-6" />
        </svg>
      </span>
    </button>
    <div v-if="hasOutput" class="tl-collapse" :class="{ 'is-open': open }">
      <div>
        <pre class="tl-tool__body">{{ shownOutput }}</pre>
      </div>
    </div>
  </div>
</template>

<style scoped>
.tl-tool {
  padding: 8px 10px;
  border: 1px solid var(--lw-border);
  border-radius: var(--lw-radius-control);
  background: var(--lw-surface-raised);
  transition: background var(--lw-dur) var(--lw-ease), border-color var(--lw-dur) var(--lw-ease);
}
.tl-tool.is-error {
  background: var(--lw-error-tint);
  border-color: var(--lw-error);
}
.tl-tool__head {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  padding: 0;
  border: none;
  background: transparent;
  cursor: pointer;
  text-align: left;
}
.tl-tool__head:focus-visible {
  outline: 2px solid var(--lw-primary);
  outline-offset: 2px;
  border-radius: 4px;
}
.tl-tool__icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 20px;
  height: 20px;
  flex: none;
  border-radius: 50%;
  color: var(--lw-success-ink);
  background: var(--lw-success-tint);
  transition: color var(--lw-dur) var(--lw-ease), background var(--lw-dur) var(--lw-ease);
}
.tl-tool__icon.is-running {
  color: var(--lw-primary-ink);
  background: var(--lw-primary-tint);
}
.tl-tool__icon.is-error {
  color: var(--lw-error-ink);
  background: var(--lw-error-tint);
}
.tl-tool__name {
  font-size: 12px;
  font-weight: 700;
  color: var(--lw-ink);
}
.tl-tool__arrow {
  display: inline-flex;
  margin-left: auto;
  color: var(--lw-ink-muted);
  transition: transform var(--lw-dur) var(--lw-ease);
}
.tl-tool__arrow.is-open {
  transform: rotate(180deg);
}
.tl-collapse {
  display: grid;
  grid-template-rows: 0fr;
  transition: grid-template-rows var(--lw-dur) var(--lw-ease-out);
}
.tl-collapse.is-open {
  grid-template-rows: 1fr;
}
.tl-collapse > div {
  overflow: hidden;
}
.tl-tool__body {
  margin: 8px 0 0;
  padding: 8px 10px;
  background: var(--lw-surface-subtle);
  border-radius: 6px;
  font-family: ui-monospace, 'Cascadia Code', Consolas, 'Courier New', monospace;
  font-size: 12px;
  line-height: 1.6;
  color: var(--lw-ink);
  white-space: pre-wrap;
  word-break: break-word;
  max-height: 260px;
  overflow: auto;
}
</style>
