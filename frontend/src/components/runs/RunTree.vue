<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import type { TraceRunVO } from '@/types/api'
import LwEmpty from '@/components/ui/LwEmpty.vue'
import LwTag from '@/components/ui/LwTag.vue'
import { formatTime } from '@/utils/const'

/** Run 树：父运行下挂子运行，点击节点切换详情；父子关系来自 wb_run.parent_run_id */
const props = defineProps<{
  tree: TraceRunVO[]
  activeRunId?: string
}>()

const emit = defineEmits<{ (event: 'select', runId: string): void }>()

const { t } = useI18n()

/** 拍平为带层级列表，供模板平铺渲染 */
interface TreeRow {
  node: TraceRunVO
  depth: number
}

const rows = computed<TreeRow[]>(() => {
  const list: TreeRow[] = []
  const walk = (nodes: TraceRunVO[], depth: number): void => {
    for (const node of nodes) {
      list.push({ node, depth })
      if (node.children?.length) {
        walk(node.children, depth + 1)
      }
    }
  }
  walk(props.tree, 0)
  return list
})

function statusTone(status?: string): 'primary' | 'success' | 'error' | 'muted' | 'warning' {
  switch (status) {
    case 'success':
      return 'success'
    case 'failed':
      return 'error'
    case 'aborted':
      return 'muted'
    case 'running':
    case 'queued':
      return 'primary'
    default:
      return 'warning'
  }
}

function formatDuration(ms?: number): string {
  if (!ms || ms <= 0) return '-'
  return ms < 1000 ? `${ms}ms` : `${(ms / 1000).toFixed(1)}s`
}

function statusLabel(status?: string): string {
  const map: Record<string, string> = {
    success: t('runs.statusSuccess'),
    failed: t('runs.statusFailed'),
    aborted: t('runs.statusAborted'),
    running: t('runs.statusRunning'),
    queued: t('runs.statusQueued'),
  }
  return map[status ?? ''] ?? (status || '-')
}
</script>

<template>
  <LwEmpty v-if="rows.length === 0" :description="t('trace.treeEmpty')" />
  <div v-else class="flex flex-col gap-1">
    <button
      v-for="row in rows"
      :key="row.node.runId"
      type="button"
      class="rt-row"
      :class="{ 'is-active': row.node.runId === activeRunId }"
      :style="{ paddingLeft: `${0.75 + row.depth * 1.25}rem` }"
      @click="emit('select', row.node.runId)"
    >
      <span v-if="row.depth > 0" class="rt-branch" />
      <LwTag :tone="statusTone(row.node.status)">{{ statusLabel(row.node.status) }}</LwTag>
      <LwTag v-if="row.node.relation === 'child'" tone="secondary">{{ t('trace.nodeChild') }}</LwTag>
      <span class="text-xs font-bold truncate flex-1" style="color: var(--lw-ink)">
        {{ row.node.prompt || row.node.runId }}
      </span>
      <span class="text-xs font-mono shrink-0" style="color: var(--lw-ink-muted)">
        {{ formatDuration(row.node.durationMs) }}
      </span>
      <span class="text-xs font-mono shrink-0" style="color: var(--lw-ink-muted)">
        {{ (row.node.inputTokens ?? 0) + (row.node.outputTokens ?? 0) }}
      </span>
      <span class="text-xs shrink-0" style="color: var(--lw-ink-muted)">
        {{ formatTime(row.node.startedAt) }}
      </span>
    </button>
  </div>
</template>

<style scoped>
.rt-row {
  position: relative;
  display: flex;
  align-items: center;
  gap: 0.5rem;
  min-height: 2.25rem;
  padding-right: 0.75rem;
  border-radius: var(--lw-radius-control);
  border: 1px solid transparent;
  transition: background-color 0.2s ease, border-color 0.2s ease;
}

.rt-row:hover {
  background-color: var(--lw-primary-tint);
}

.rt-row.is-active {
  border-color: var(--lw-secondary);
  background-color: var(--lw-secondary-tint);
}

.rt-branch {
  position: absolute;
  left: 0.75rem;
  top: 0;
  bottom: 50%;
  width: 1px;
  background-color: var(--lw-border);
}
</style>
