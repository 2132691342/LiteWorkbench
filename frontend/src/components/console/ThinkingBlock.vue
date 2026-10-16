<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'

/** 思考块：默认折叠，流式期间自动展开，结束后回归折叠，不抢占正文的注意力 */
const props = defineProps<{
  content: string
  streaming?: boolean
}>()

const { t } = useI18n()

const open = ref(false)

const hasContent = computed(() => props.content.trim().length > 0)

// 流式期间自动展开让用户看得见 Agent 在想什么；结束后不强制收起，尊重用户最后一次选择
watch(
  () => props.streaming,
  (streaming) => {
    if (streaming) open.value = true
  },
  { immediate: true },
)

function toggle(): void {
  open.value = !open.value
}
</script>

<template>
  <div v-if="hasContent" class="tl-thinking">
    <button type="button" class="tl-thinking__head" :aria-expanded="open" @click="toggle">
      <span class="tl-thinking__arrow" :class="{ 'is-open': open }">
        <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5"
             stroke-linecap="round" stroke-linejoin="round">
          <path d="m9 18 6-6-6-6" />
        </svg>
      </span>
      <span class="tl-thinking__title">{{ t('console.thinking') }}</span>
      <span v-if="props.streaming" class="tl-thinking__pulse" aria-hidden="true" />
    </button>
    <div class="tl-collapse" :class="{ 'is-open': open }">
      <div>
        <pre class="tl-thinking__body">{{ props.content }}</pre>
      </div>
    </div>
  </div>
</template>

<style scoped>
.tl-thinking {
  background: var(--lw-surface-subtle);
  border-left: 3px solid var(--lw-secondary);
  border-radius: var(--lw-radius-control);
  padding: 6px 10px;
}
.tl-thinking__head {
  display: flex;
  align-items: center;
  gap: 6px;
  width: 100%;
  padding: 0;
  border: none;
  background: transparent;
  cursor: pointer;
  text-align: left;
  color: var(--lw-primary-ink);
}
.tl-thinking__head:focus-visible {
  outline: 2px solid var(--lw-primary);
  outline-offset: 2px;
  border-radius: 4px;
}
.tl-thinking__arrow {
  display: inline-flex;
  transition: transform var(--lw-dur) var(--lw-ease);
}
.tl-thinking__arrow.is-open {
  transform: rotate(90deg);
}
.tl-thinking__title {
  font-size: 12px;
  font-weight: 700;
}
.tl-thinking__pulse {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--lw-primary);
  animation: tl-pulse 1s var(--lw-ease) infinite;
}
@keyframes tl-pulse {
  0%,
  100% {
    opacity: 0.25;
    transform: scale(0.85);
  }
  50% {
    opacity: 1;
    transform: scale(1);
  }
}
/* grid 行高过渡：比 max-height 平滑，且不依赖固定高度猜测 */
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
.tl-thinking__body {
  margin: 6px 0 0;
  font-size: 12px;
  line-height: 1.65;
  color: var(--lw-ink-muted);
  white-space: pre-wrap;
  word-break: break-word;
  max-height: 240px;
  overflow: auto;
}
@media (prefers-reduced-motion: reduce) {
  .tl-thinking__pulse {
    animation: none;
  }
}
</style>
