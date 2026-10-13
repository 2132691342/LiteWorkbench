<script setup lang="ts">
import { computed, onBeforeUnmount, ref, shallowRef } from 'vue'
import { useRoute } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { runApi } from '@/api'
import type { RunEvent, RunVO } from '@/types/api'
import LwButton from '@/components/ui/LwButton.vue'
import LwMarkdown from '@/components/ui/LwMarkdown.vue'
import LwPageState from '@/components/ui/LwPageState.vue'
import LwSegmented from '@/components/ui/LwSegmented.vue'
import LwTag from '@/components/ui/LwTag.vue'
import { formatTime } from '@/utils/const'

/** 运行记录：左侧运行列表（状态/时间过滤 + 分页），右侧详情（时间线 / 工具链 / 用量 + 回放） */

interface ToolBlock {
  name: string
  phase: 'start' | 'end'
  args?: string
  output?: string
  isError?: boolean
}

interface TimelineBlock {
  kind: 'thinking' | 'text' | 'tool' | 'model'
  text?: string
  tool?: ToolBlock
  round?: number
  durationMs?: number
  tokens?: number
}

const { t } = useI18n()
const route = useRoute()

const runs = shallowRef<RunVO[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const loadingList = ref(false)
const listError = ref('')
const stateFilter = ref('all')

const detail = ref<RunVO | null>(null)
const events = shallowRef<RunEvent[]>([])
const loadingDetail = ref(false)
const detailError = ref('')
const aborting = ref(false)
const view = ref<'timeline' | 'tools' | 'usage'>('timeline')

const replaying = ref(false)
const replayCursor = ref(0)
const replaySpeed = ref('1')
let replayTimer: number | null = null

const stateOptions = computed(() => [
  { value: 'all', label: t('runs.filterAll') },
  { value: 'success', label: t('runs.statusSuccess') },
  { value: 'failed', label: t('runs.statusFailed') },
  { value: 'aborted', label: t('runs.statusAborted') },
  { value: 'running', label: t('runs.statusRunning') },
])

const viewOptions = computed(() => [
  { value: 'timeline', label: t('runs.viewTimeline') },
  { value: 'tools', label: t('runs.viewTools') },
  { value: 'usage', label: t('runs.viewUsage') },
])

const speedOptions = [
  { value: '0.5', label: '0.5x' },
  { value: '1', label: '1x' },
  { value: '2', label: '2x' },
  { value: '4', label: '4x' },
]

/** 事件折叠为时间线块：思考 / 正文连续合并，工具起止配对，模型调用成行 */
const blocks = computed<TimelineBlock[]>(() => {
  const result: TimelineBlock[] = []
  const toolStack: ToolBlock[] = []
  let currentText: TimelineBlock | null = null
  let currentThinking: TimelineBlock | null = null
  let round = 0
  for (const event of events.value) {
    const payload = event.payload ?? {}
    switch (event.eventType) {
      case 'thinking.delta':
        if (!currentThinking) {
          currentThinking = { kind: 'thinking', text: '' }
          result.push(currentThinking)
        }
        currentThinking.text += String(payload.delta ?? event.textDelta ?? '')
        break
      case 'text.delta':
        currentThinking = null
        if (!currentText) {
          currentText = { kind: 'text', text: '' }
          result.push(currentText)
        }
        currentText.text += String(payload.delta ?? event.textDelta ?? '')
        break
      case 'tool.call': {
        currentText = null
        currentThinking = null
        if (payload.phase === 'start') {
          const tool: ToolBlock = { name: String(payload.toolName ?? ''), phase: 'start', args: stringify(payload.arguments) }
          toolStack.push(tool)
          result.push({ kind: 'tool', tool })
        } else {
          const tool = toolStack.pop()
          if (tool) {
            tool.phase = 'end'
          }
        }
        break
      }
      case 'tool.result': {
        const tool = [...toolStack].reverse().find((item) => item.name === String(payload.toolName ?? '') && item.phase === 'start')
        const target = tool ?? { name: String(payload.toolName ?? ''), phase: 'end' as const }
        if (!tool) {
          result.push({ kind: 'tool', tool: target })
        }
        target.phase = 'end'
        target.output = String(payload.output ?? '')
        target.isError = Boolean(payload.isError)
        break
      }
      case 'model.call':
        if (payload.phase === 'end') {
          round += 1
          const usage = (payload.tokens ?? {}) as { inputTokens?: number; outputTokens?: number }
          result.push({
            kind: 'model',
            round,
            durationMs: Number(payload.durationMs ?? 0),
            tokens: Number(usage.inputTokens ?? 0) + Number(usage.outputTokens ?? 0),
          })
        }
        break
      default:
        break
    }
  }
  return result
})

/** 回放中按游标截断，实时态全量 */
const visibleBlocks = computed(() =>
  replaying.value ? blocks.value.slice(0, replayCursor.value) : blocks.value)

/** 工具链视图：全部工具块按序 */
const toolBlocks = computed(() =>
  blocks.value.filter((block) => block.kind === 'tool').map((block) => block.tool as ToolBlock))

function stringify(value: unknown): string {
  if (value === undefined || value === null) return ''
  if (typeof value === 'string') return value
  try {
    return JSON.stringify(value, null, 2)
  } catch {
    return String(value)
  }
}

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

function statusLabel(status?: string): string {
  const map: Record<string, string> = {
    success: t('runs.statusSuccess'),
    failed: t('runs.statusFailed'),
    aborted: t('runs.statusAborted'),
    running: t('runs.statusRunning'),
    queued: t('runs.statusQueued'),
  }
  return map[status ?? ''] ?? (status ?? '-')
}

function isTerminal(status?: string): boolean {
  return status === 'success' || status === 'failed' || status === 'aborted'
}

function formatDuration(ms?: number): string {
  if (!ms || ms <= 0) return '-'
  return ms < 1000 ? `${ms}ms` : `${(ms / 1000).toFixed(1)}s`
}

function tokenSummary(run: RunVO): string {
  const tokens = (run.inputTokens ?? 0) + (run.outputTokens ?? 0)
  return tokens > 0 ? `${tokens} tok` : '-'
}

function truncate(text?: string, limit = 4000): string {
  if (!text) return ''
  return text.length > limit ? `${text.slice(0, limit)}\n…（${t('runs.truncated')}）` : text
}

async function loadRuns(): Promise<void> {
  loadingList.value = true
  listError.value = ''
  try {
    const result = await runApi.page({
      page: page.value,
      size: size.value,
      state: stateFilter.value === 'all' ? undefined : stateFilter.value,
    })
    runs.value = result.list
    total.value = result.total
  } catch (error) {
    listError.value = (error as Error).message
  } finally {
    loadingList.value = false
  }
}

function changeFilter(value: string): void {
  stateFilter.value = value
  page.value = 1
  void loadRuns()
}

function changePage(next: number): void {
  page.value = next
  void loadRuns()
}

function changeSize(next: number): void {
  size.value = next
  page.value = 1
  void loadRuns()
}

async function selectRun(runId: string): Promise<void> {
  stopReplay()
  loadingDetail.value = true
  detailError.value = ''
  detail.value = null
  events.value = []
  try {
    const [detailVO, eventList] = await Promise.all([runApi.detail(runId), runApi.events(runId)])
    detail.value = detailVO
    events.value = eventList
    view.value = 'timeline'
  } catch (error) {
    detailError.value = (error as Error).message
  } finally {
    loadingDetail.value = false
  }
}

function reloadDetail(): void {
  if (detail.value) {
    void selectRun(detail.value.runId)
  }
}

async function abort(): Promise<void> {
  if (!detail.value) return
  aborting.value = true
  try {
    await runApi.abort(detail.value.runId)
    await selectRun(detail.value.runId)
    void loadRuns()
  } finally {
    aborting.value = false
  }
}

/** 回放：本地游标遍历已加载事件块，不发起任何执行 */
function toggleReplay(): void {
  if (replaying.value) {
    stopReplay()
    return
  }
  if (blocks.value.length === 0) return
  replaying.value = true
  replayCursor.value = 0
  scheduleReplay()
}

function scheduleReplay(): void {
  const interval = 700 / Number(replaySpeed.value)
  replayTimer = window.setInterval(() => {
    replayCursor.value += 1
    if (replayCursor.value >= blocks.value.length) {
      stopReplay()
    }
  }, interval)
}

function stopReplay(): void {
  replaying.value = false
  if (replayTimer !== null) {
    window.clearInterval(replayTimer)
    replayTimer = null
  }
}

function setSpeed(value: string): void {
  replaySpeed.value = value
  if (replaying.value) {
    stopReplay()
    toggleReplay()
  }
}

onBeforeUnmount(stopReplay)

const initialRunId = route.query.runId
if (typeof initialRunId === 'string' && initialRunId) {
  void selectRun(initialRunId)
}
void loadRuns()
</script>

<template>
  <div class="lw-page">
    <div class="lw-page-head">
      <div>
        <h1 class="lw-title">{{ t('runs.title') }}</h1>
        <p class="lw-subtitle">{{ t('runs.desc') }}</p>
      </div>
      <LwButton variant="ghost" size="sm" :loading="loadingList" @click="loadRuns">
        {{ t('common.refresh') }}
      </LwButton>
    </div>

    <div class="flex flex-col lg:flex-row gap-4 items-start">
      <!-- 左列：过滤 + 运行列表 -->
      <div class="lw-card w-full lg:w-80 shrink-0 flex flex-col gap-3">
        <LwSegmented :model-value="stateFilter" :options="stateOptions" equal @update:model-value="changeFilter" />

        <LwPageState
          :loading="loadingList"
          :error="listError"
          :empty="runs.length === 0"
          :empty-text="t('runs.empty')"
          :skeleton-rows="5"
          @retry="loadRuns"
        >
          <div class="flex flex-col gap-2 max-h-[560px] overflow-y-auto pr-1">
            <button
              v-for="run in runs"
              :key="run.runId"
              type="button"
              class="run-row"
              :class="{ 'is-active': run.runId === detail?.runId }"
              @click="selectRun(run.runId)"
            >
              <div class="flex items-center justify-between gap-2">
                <LwTag :tone="statusTone(run.status)">{{ statusLabel(run.status) }}</LwTag>
                <span class="text-xs font-mono" style="color: var(--lw-ink-muted)">{{ formatTime(run.createdAt) }}</span>
              </div>
              <p class="run-row__prompt">{{ run.prompt || '-' }}</p>
              <div class="run-row__meta">
                <span>{{ run.modelName || '-' }}</span>
                <span>{{ formatDuration(run.durationMs) }}</span>
                <span>{{ tokenSummary(run) }}</span>
              </div>
            </button>
          </div>
        </LwPageState>

        <div v-if="total > size" class="flex justify-end pt-1">
          <el-pagination
            v-model:current-page="page"
            v-model:page-size="size"
            :total="total"
            :page-sizes="[10, 20, 50]"
            layout="total, sizes, prev, pager, next"
            @current-change="changePage"
            @size-change="changeSize"
          />
        </div>
      </div>

      <!-- 右列：运行详情 -->
      <div class="flex-1 flex flex-col gap-4 min-w-0 w-full">
        <LwPageState
          :loading="loadingDetail"
          :error="detailError"
          :empty="!detail"
          :empty-text="t('runs.selectHint')"
          :skeleton-rows="6"
          @retry="reloadDetail"
        >
          <template v-if="detail">
            <!-- 头部 -->
            <div class="lw-card flex flex-wrap items-center gap-3">
              <LwTag :tone="statusTone(detail.status)">{{ statusLabel(detail.status) }}</LwTag>
              <span class="text-sm font-bold" style="color: var(--lw-ink)">{{ detail.modelName || '-' }}</span>
              <span class="text-xs" style="color: var(--lw-ink-muted)">{{ formatTime(detail.createdAt) }}</span>
              <span class="text-xs font-mono" style="color: var(--lw-ink-muted)">{{ detail.runId }}</span>
              <div class="ml-auto flex gap-2">
                <LwButton
                  v-if="!isTerminal(detail.status)"
                  variant="danger"
                  size="sm"
                  :loading="aborting"
                  @click="abort"
                >
                  {{ t('runs.abort') }}
                </LwButton>
              </div>
            </div>

            <!-- 指标条 -->
            <div class="grid grid-cols-2 md:grid-cols-4 gap-3">
              <div class="lw-card flex flex-col gap-1 py-3">
                <span class="text-xs" style="color: var(--lw-ink-muted)">{{ t('runs.metricDuration') }}</span>
                <span class="text-lg font-bold" style="color: var(--lw-ink)">{{ formatDuration(detail.durationMs) }}</span>
              </div>
              <div class="lw-card flex flex-col gap-1 py-3">
                <span class="text-xs" style="color: var(--lw-ink-muted)">{{ t('runs.metricTokens') }}</span>
                <span class="text-lg font-bold" style="color: var(--lw-ink)">{{ tokenSummary(detail) }}</span>
              </div>
              <div class="lw-card flex flex-col gap-1 py-3">
                <span class="text-xs" style="color: var(--lw-ink-muted)">{{ t('runs.metricIters') }}</span>
                <span class="text-lg font-bold" style="color: var(--lw-ink)">{{ detail.iterCount ?? 0 }}</span>
              </div>
              <div class="lw-card flex flex-col gap-1 py-3">
                <span class="text-xs" style="color: var(--lw-ink-muted)">{{ t('runs.metricStop') }}</span>
                <span class="text-lg font-bold" style="color: var(--lw-ink)">{{ detail.stopReason || '-' }}</span>
              </div>
            </div>

            <!-- 详情内容 -->
            <div class="lw-card flex flex-col gap-4">
              <div class="flex flex-wrap items-center justify-between gap-3">
                <LwSegmented v-model="view" :options="viewOptions" />
                <div v-if="view === 'timeline'" class="flex items-center gap-2">
                  <LwButton variant="ghost" @click="toggleReplay">
                    {{ replaying ? t('runs.replayPause') : t('runs.replayPlay') }}
                  </LwButton>
                  <LwSegmented
                    v-if="replaying"
                    :model-value="replaySpeed"
                    :options="speedOptions"
                    @update:model-value="setSpeed"
                  />
                  <span v-if="replaying" class="text-xs" style="color: var(--lw-ink-muted)">
                    {{ t('runs.replayProgress') }} {{ replayCursor }}/{{ blocks.length }}
                  </span>
                </div>
              </div>

              <!-- 时间线 -->
              <template v-if="view === 'timeline'">
                <div v-if="replaying" class="replay-banner">{{ t('runs.replayMode') }}</div>
                <div v-if="visibleBlocks.length === 0" class="text-sm" style="color: var(--lw-ink-muted)">
                  {{ t('runs.noEvents') }}
                </div>
                <div v-for="(block, index) in visibleBlocks" :key="index" class="flex flex-col gap-3">
                  <details v-if="block.kind === 'thinking'" class="tl-thinking">
                    <summary>{{ t('runs.thinking') }} · {{ (block.text ?? '').length }} {{ t('runs.chars') }}</summary>
                    <pre>{{ block.text }}</pre>
                  </details>
                  <LwMarkdown v-else-if="block.kind === 'text'" :content="block.text ?? ''" />
                  <div v-else-if="block.kind === 'tool'" class="tl-tool">
                    <div class="flex items-center gap-2">
                      <span class="text-sm font-bold" style="color: var(--lw-ink)">{{ block.tool?.name }}</span>
                      <LwTag :tone="block.tool?.isError ? 'error' : block.tool?.phase === 'end' ? 'success' : 'primary'">
                        {{ block.tool?.phase === 'end' ? (block.tool?.isError ? t('runs.toolFailed') : t('runs.toolDone')) : t('runs.toolRunning') }}
                      </LwTag>
                    </div>
                    <details v-if="block.tool?.args">
                      <summary>{{ t('runs.toolArgs') }}</summary>
                      <pre>{{ block.tool.args }}</pre>
                    </details>
                    <details v-if="block.tool?.output">
                      <summary>{{ t('runs.toolOutput') }}</summary>
                      <pre>{{ truncate(block.tool.output) }}</pre>
                    </details>
                  </div>
                  <div v-else class="tl-model">
                    {{ t('runs.modelRound') }} {{ block.round }} · {{ formatDuration(block.durationMs) }} ·
                    {{ block.tokens }} tok
                  </div>
                </div>
              </template>

              <!-- 工具链 -->
              <template v-else-if="view === 'tools'">
                <div v-if="toolBlocks.length === 0" class="text-sm" style="color: var(--lw-ink-muted)">
                  {{ t('runs.noTools') }}
                </div>
                <div v-for="(tool, index) in toolBlocks" :key="index" class="tl-tool">
                  <div class="flex items-center gap-2">
                    <span class="text-sm font-bold" style="color: var(--lw-ink)">{{ index + 1 }}. {{ tool.name }}</span>
                    <LwTag :tone="tool.isError ? 'error' : 'success'">
                      {{ tool.isError ? t('runs.toolFailed') : t('runs.toolDone') }}
                    </LwTag>
                  </div>
                  <details v-if="tool.args">
                    <summary>{{ t('runs.toolArgs') }}</summary>
                    <pre>{{ tool.args }}</pre>
                  </details>
                  <details v-if="tool.output">
                    <summary>{{ t('runs.toolOutput') }}</summary>
                    <pre>{{ truncate(tool.output) }}</pre>
                  </details>
                </div>
              </template>

              <!-- 用量 -->
              <template v-else>
                <div class="grid grid-cols-1 md:grid-cols-3 gap-3">
                  <div class="lw-card flex flex-col gap-1">
                    <span class="text-xs" style="color: var(--lw-ink-muted)">{{ t('runs.inputTokens') }}</span>
                    <span class="text-xl font-bold" style="color: var(--lw-primary)">{{ detail.inputTokens ?? 0 }}</span>
                  </div>
                  <div class="lw-card flex flex-col gap-1">
                    <span class="text-xs" style="color: var(--lw-ink-muted)">{{ t('runs.outputTokens') }}</span>
                    <span class="text-xl font-bold" style="color: var(--lw-secondary)">{{ detail.outputTokens ?? 0 }}</span>
                  </div>
                  <div class="lw-card flex flex-col gap-1">
                    <span class="text-xs" style="color: var(--lw-ink-muted)">{{ t('runs.totalTokens') }}</span>
                    <span class="text-xl font-bold" style="color: var(--lw-ink)">
                      {{ (detail.inputTokens ?? 0) + (detail.outputTokens ?? 0) }}
                    </span>
                  </div>
                </div>
                <p class="text-xs leading-relaxed" style="color: var(--lw-ink-muted)">{{ detail.prompt }}</p>
              </template>
            </div>
          </template>
        </LwPageState>
      </div>
    </div>
  </div>
</template>
