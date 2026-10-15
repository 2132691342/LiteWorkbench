<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import type { TraceSpanVO } from '@/types/api'
import LwEmpty from '@/components/ui/LwEmpty.vue'
import LwTag from '@/components/ui/LwTag.vue'

/** Trace 瀑布图：按偏移与宽度绘制片段，模型段与工具段分色，一眼看出时间花在哪 */
const props = defineProps<{
  /** 链路片段，已按 offsetMs 正序 */
  spans: TraceSpanVO[]
}>()

const { t } = useI18n()

/** 时间轴总长：取所有片段右边界最大值，至少 1ms 避免除零 */
const totalMs = computed(() => {
  const end = props.spans.reduce((max, span) => Math.max(max, span.offsetMs + (span.durationMs ?? 0)), 0)
  return Math.max(end, 1)
})

function barStyle(span: TraceSpanVO): Record<string, string> {
  const left = (span.offsetMs / totalMs.value) * 100
  const width = Math.max(((span.durationMs ?? 0) / totalMs.value) * 100, 0.8)
  return { left: `${left}%`, width: `${width}%`, backgroundColor: barColor(span) }
}

/** 模型段主紫、工具段辅助紫、计划点深色前景；错误与未收尾用语义色覆盖前两者 */
function barColor(span: TraceSpanVO): string {
  if (span.status === 'error') return 'var(--lw-error-ink)'
  if (span.status === 'pending') return 'var(--lw-warning-ink)'
  if (span.kind === 'plan') return 'var(--lw-ink)'
  return span.kind === 'model' ? 'var(--lw-primary)' : 'var(--lw-secondary)'
}

function formatMs(ms?: number): string {
  if (!ms || ms <= 0) return '0ms'
  return ms < 1000 ? `${ms}ms` : `${(ms / 1000).toFixed(2)}s`
}

function tone(span: TraceSpanVO): 'success' | 'error' | 'warning' {
  if (span.status === 'error') return 'error'
  if (span.status === 'pending') return 'warning'
  return 'success'
}

function statusLabel(span: TraceSpanVO): string {
  if (span.status === 'error') return t('trace.statusError')
  if (span.status === 'pending') return t('trace.statusPending')
  return t('trace.statusOk')
}
</script>

<template>
  <LwEmpty v-if="spans.length === 0" :description="t('trace.empty')" />
  <div v-else class="flex flex-col gap-2">
    <div class="flex items-center gap-3 text-xs" style="color: var(--lw-ink-muted)">
      <span>{{ t('trace.total') }} {{ formatMs(totalMs) }}</span>
      <span class="flex items-center gap-1">
        <span class="w-3 h-2 rounded-sm" style="background-color: var(--lw-primary)" />

        {{ t('trace.kindModel') }}
      </span>
      <span class="flex items-center gap-1">
        <span class="w-3 h-2 rounded-sm" style="background-color: var(--lw-secondary)" />
        {{ t('trace.kindTool') }}
      </span>
    </div>

    <div
      v-for="span in spans"
      :key="span.spanId"
      class="tw-row"
      :class="{ 'is-open': span.status === 'pending' }"
    >
      <div class="tw-row__label">
        <span class="text-xs font-bold truncate" style="color: var(--lw-ink)">{{ span.name }}</span>
        <span v-if="span.iteration" class="text-[10px]" style="color: var(--lw-ink-muted)">
          #{{ span.iteration }}
        </span>
      </div>

      <div class="tw-row__track">
        <div class="tw-bar" :style="barStyle(span)" />
      </div>

      <div class="tw-row__meta">
        <LwTag :tone="tone(span)">{{ statusLabel(span) }}</LwTag>
        <span class="text-xs font-mono" style="color: var(--lw-ink-muted)">{{ formatMs(span.durationMs) }}</span>
        <span v-if="span.inputTokens || span.outputTokens" class="text-xs font-mono" style="color: var(--lw-ink-muted)">
          {{ (span.inputTokens ?? 0) + (span.outputTokens ?? 0) }}
        </span>
        <span v-else-if="span.outputChars" class="text-xs font-mono" style="color: var(--lw-ink-muted)">
          {{ span.outputChars }} {{ t('runs.chars') }}
        </span>
      </div>
    </div>
  </div>
</template>

<style scoped>
.tw-row {
  display: grid;
  grid-template-columns: 12rem 1fr auto;
  align-items: center;
  gap: 0.75rem;
  padding: 0.375rem 0.5rem;
  border-radius: var(--lw-radius-control);
  transition: background-color 0.2s ease;
}

.tw-row:hover {
  background-color: var(--lw-primary-tint);
}

.tw-row.is-open {
  background-color: var(--lw-warning-tint);
}

.tw-row__label {
  display: flex;
  align-items: baseline;
  gap: 0.375rem;
  min-width: 0;
}

.tw-row__track {
  position: relative;
  height: 0.75rem;
  border-radius: var(--lw-radius-pill);
  background-color: var(--lw-border);
  overflow: hidden;
}

.tw-bar {
  position: absolute;
  top: 0;
  height: 100%;
  border-radius: var(--lw-radius-pill);
  transition: width 0.3s ease, left 0.3s ease;
}

.tw-row__meta {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  justify-content: flex-end;
}

@media (max-width: 768px) {
  .tw-row {
    grid-template-columns: 1fr;
  }
}
</style>
