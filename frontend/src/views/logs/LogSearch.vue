<script setup lang="ts">
import { ArrowDown, RefreshLeft, Search } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import LwButton from '@/components/ui/LwButton.vue'
import LwCard from '@/components/ui/LwCard.vue'
import LwPageState from '@/components/ui/LwPageState.vue'
import LwSegmented from '@/components/ui/LwSegmented.vue'
import LwTag from '@/components/ui/LwTag.vue'
import { logApi } from '@/api'
import type { LogDocVO } from '@/types/api'
import { formatTime } from '@/utils/const'

/** 日志中心：一屏看分布，两行完成检索，行内两行预览 + 展开看全文 */
const { t, te } = useI18n()

const loading = ref(false)
const statsLoading = ref(false)
const error = ref('')
const docs = ref<LogDocVO[]>([])
const total = ref(0)
const expanded = ref('')
const copiedId = ref('')
const levels = ref<{ level: string; count: number }[]>([])
const histogram = ref<{ hour: number; count: number }[]>([])
/** 直方图桶宽毫秒：服务端按区间在小时 / 天之间切换 */
const bucketMs = ref(3_600_000)

/** 时间字段同时接受 number（预设/钻取写入）与 el-date-picker 的 value-format="x" 字符串，请求前统一归一 */
type TimeInput = number | string | undefined

/** 检索视角：由服务端按角色判定，前端只做展示与过滤区显隐 */
const scope = ref<'global' | 'self'>('self')

const query = reactive({
  keyword: '',
  field: 'message',
  level: '',
  logger: '',
  traceId: '',
  userId: '',
  runId: '',
  from: undefined as TimeInput,
  to: undefined as TimeInput,
  page: 1,
  size: 20,
})

/** 高级筛选展开态：默认收起，主行只留关键字、级别与快捷时间 */
const advancedOpen = ref(false)

/** 时间归一：空值与非法值一律 undefined，数字字符串转毫秒 */
function toMillis(value: TimeInput): number | undefined {
  if (value === undefined || value === '') return undefined
  const parsed = Number(value)
  return Number.isFinite(parsed) && parsed > 0 ? parsed : undefined
}

function onTimeChange(): void {
  query.from = toMillis(query.from)
  query.to = toMillis(query.to)
  activePreset.value = ''
  handleSearch()
}

const PRESETS = [
  { key: '15m', ms: 15 * 60 * 1000 },
  { key: '1h', ms: 60 * 60 * 1000 },
  { key: '24h', ms: 24 * 60 * 60 * 1000 },
  { key: '7d', ms: 7 * 24 * 60 * 60 * 1000 },
] as const

const activePreset = ref<string>('24h')

/** 字段选项：message 走全文分词，logger / traceId 走等值 */
const FIELD_OPTIONS = [
  { value: 'message', labelKey: 'logs.fieldMessage' },
  { value: 'logger', labelKey: 'logs.fieldLogger' },
  { value: 'traceId', labelKey: 'logs.fieldTraceId' },
]

/** 链路钻取态：按 traceId 过滤时把结果翻成时间升序，还原一次请求的先后顺序 */
const traceMode = computed(() => query.field === 'traceId' && query.keyword.trim().length > 0)

/** 展示用列表：链路钻取升序，其余保持服务端的时间倒序 */
const displayDocs = computed(() => (traceMode.value ? [...docs.value].reverse() : docs.value))

/** 高级筛选已启用条数：用于折叠态上的角标提示；快捷时间预设写入的 from/to 不计入，只有用户自选时间范围才算 */
const advancedCount = computed(
  () =>
    [query.logger, query.traceId, query.userId, query.runId].filter((item) => item.trim().length > 0).length +
    (activePreset.value ? 0 : (toMillis(query.from) ? 1 : 0) + (toMillis(query.to) ? 1 : 0)),
)

const levelCount = computed(() => {
  const map = new Map<string, number>()
  levels.value.forEach((item) => map.set(item.level, item.count))
  return map
})

function levelTotal(level: string): string {
  return (levelCount.value.get(level) ?? 0).toLocaleString()
}

/** 迷你直方图：单行内嵌，柱子高度按占比，悬停看时间与条数，点击钻取 */
const histogramMax = computed(() => histogram.value.reduce((max, item) => Math.max(max, item.count), 0))

function barHeight(count: number): string {
  if (histogramMax.value <= 0) return '8%'
  return `${Math.max(8, Math.round((count / histogramMax.value) * 100))}%`
}

function formatHour(value: number): string {
  const date = new Date(value)
  const pad = (n: number) => String(n).padStart(2, '0')
  if (bucketMs.value >= 86_400_000) {
    return `${pad(date.getMonth() + 1)}-${pad(date.getDate())}`
  }
  return `${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:00`
}

interface Segment {
  text: string
  hit: boolean
}

function highlight(text: string | undefined, keyword: string): Segment[] {
  const value = text ?? ''
  const terms = keyword
    .split(/[\s,;]+/)
    .map((item) => item.trim())
    .filter((item) => item.length > 0)
  if (terms.length === 0) return [{ text: value, hit: false }]
  const segments: Segment[] = []
  let rest = value
  while (rest.length > 0) {
    let bestIndex = -1
    let bestTerm = ''
    for (const term of terms) {
      const index = rest.toLowerCase().indexOf(term.toLowerCase())
      if (index >= 0 && (bestIndex < 0 || index < bestIndex)) {
        bestIndex = index
        bestTerm = term
      }
    }
    if (bestIndex < 0) {
      segments.push({ text: rest, hit: false })
      break
    }
    if (bestIndex > 0) segments.push({ text: rest.slice(0, bestIndex), hit: false })
    segments.push({ text: rest.slice(bestIndex, bestIndex + bestTerm.length), hit: true })
    rest = rest.slice(bestIndex + bestTerm.length)
  }
  return segments
}

/** 时间范围变动时两个请求并行刷新，概览不单独触发 */
let debounceTimer: number | undefined

function scheduleRefresh(): void {
  window.clearTimeout(debounceTimer)
  debounceTimer = window.setTimeout(() => {
    void Promise.all([search(), loadStats()])
  }, 300)
}

function handleSearch(): void {
  query.page = 1
  scheduleRefresh()
}

async function search(): Promise<void> {
  loading.value = true
  error.value = ''
  try {
    const result = await logApi.search({
      keyword: query.keyword || undefined,
      field: query.field || undefined,
      level: query.level || undefined,
      logger: query.logger || undefined,
      traceId: query.traceId || undefined,
      userId: query.userId || undefined,
      runId: query.runId || undefined,
      from: toMillis(query.from),
      to: toMillis(query.to),
      page: query.page,
      size: query.size,
    })
    docs.value = result.list
    total.value = result.total
    // 视角以检索响应为准，与结果同源
    if (result.scope) scope.value = result.scope === 'global' ? 'global' : 'self'
  } catch (e) {
    error.value = (e as Error).message
  } finally {
    loading.value = false
  }
}

async function loadStats(): Promise<void> {
  statsLoading.value = true
  try {
    const stats = await logApi.stats({
      keyword: query.keyword || undefined,
      field: query.field || undefined,
      level: query.level || undefined,
      logger: query.logger || undefined,
      traceId: query.traceId || undefined,
      userId: query.userId || undefined,
      runId: query.runId || undefined,
      from: toMillis(query.from),
      to: toMillis(query.to),
    })
    levels.value = stats.levels ?? []
    histogram.value = stats.histogram ?? []
    if (stats.bucketMs) bucketMs.value = stats.bucketMs
    if (stats.scope) scope.value = stats.scope === 'global' ? 'global' : 'self'
  } catch {
    // 概览降级：不影响主检索，卡片显示占位
    levels.value = []
    histogram.value = []
  } finally {
    statsLoading.value = false
  }
}

function applyPreset(key: string): void {
  const preset = PRESETS.find((item) => item.key === key)
  if (!preset) return
  activePreset.value = key
  query.to = Date.now()
  query.from = query.to - preset.ms
  handleSearch()
}

/** 重置：清空全部筛选条件并回到默认 24 小时窗口，用户不必逐项清空 */
function handleReset(): void {
  query.keyword = ''
  query.field = 'message'
  query.level = ''
  query.logger = ''
  query.traceId = ''
  query.userId = ''
  query.runId = ''
  query.from = undefined
  query.to = undefined
  query.page = 1
  applyPreset('24h')
}

function drillHour(index: number): void {
  const bucket = histogram.value[index]
  if (!bucket) return
  activePreset.value = ''
  query.from = bucket.hour
  query.to = bucket.hour + bucketMs.value
  handleSearch()
}

async function copyTraceId(traceId: string): Promise<void> {
  if (!traceId) return
  try {
    await navigator.clipboard.writeText(traceId)
    copiedId.value = traceId
    ElMessage.success(t('logs.copied'))
    window.setTimeout(() => {
      if (copiedId.value === traceId) copiedId.value = ''
    }, 400)
  } catch {
    // 剪贴板不可用（非安全上下文）时静默失败，用户仍可手动选择复制
  }
}

function filterByTrace(traceId: string): void {
  if (!traceId) return
  query.field = 'traceId'
  query.keyword = traceId
  query.page = 1
  handleSearch()
}

function filterByUser(userId: string): void {
  if (!userId) return
  query.userId = userId
  query.page = 1
  handleSearch()
}

function filterByRun(runId: string): void {
  if (!runId) return
  query.runId = runId
  query.page = 1
  handleSearch()
}

function levelTone(level?: string): 'error' | 'warning' | 'primary' {
  if (level === 'ERROR') return 'error'
  if (level === 'WARN') return 'warning'
  return 'primary'
}

/** 翻译缺失时降级为 key 末段，绝不裸显 i18n key */
function fieldLabel(key: string): string {
  const i18nKey = FIELD_OPTIONS.find((item) => item.value === key)?.labelKey ?? ''
  return te(i18nKey) ? t(i18nKey) : key
}

onMounted(() => {
  applyPreset('24h')
})

onBeforeUnmount(() => {
  window.clearTimeout(debounceTimer)
})
</script>

<template>
  <div class="lw-page flex flex-col gap-4">
    <div class="lw-page-head">
      <div>
        <h2 class="lw-title">{{ t('logs.title') }}</h2>
        <p class="lw-subtitle mt-1">{{ t('logs.desc') }}</p>
      </div>
      <el-tooltip :content="scope === 'global' ? t('logs.scopeTip_global') : t('logs.scopeTip_self')" placement="left">
        <LwTag :tone="scope === 'global' ? 'secondary' : 'muted'">
          {{ scope === 'global' ? t('logs.scope_global') : t('logs.scope_self') }}
        </LwTag>
      </el-tooltip>
    </div>

    <!-- 分布概览：级别计数与时间直方图并作一行紧凑条，随过滤条件联动 -->
    <LwCard>
      <div class="flex flex-col sm:flex-row sm:items-center gap-4">
        <div class="flex items-center gap-5 shrink-0">
          <div v-for="level in ['ERROR', 'WARN', 'INFO']" :key="level" class="flex items-center gap-2">
            <span
              class="w-2 h-2 rounded-full shrink-0"
              :style="{
                backgroundColor:
                  level === 'ERROR'
                    ? 'var(--lw-error)'
                    : level === 'WARN'
                      ? 'var(--lw-warning)'
                      : 'var(--lw-primary)',
              }"
            />
            <span class="text-xs font-bold" style="color: var(--lw-ink-muted)">{{ t(`logs.level_${level.toLowerCase()}`) }}</span>
            <span class="text-lg font-bold tracking-tight tabular-nums" style="color: var(--lw-ink)">
              {{ statsLoading ? '·' : levelTotal(level) }}
            </span>
          </div>
        </div>
        <div class="hidden sm:block w-px self-stretch" style="background: var(--lw-border)" />
        <div class="flex-1 min-w-0 flex flex-col gap-1">
          <div class="flex items-center justify-between">
            <span class="text-xs font-bold" style="color: var(--lw-ink-muted)">{{ t('logs.histogram') }}</span>
            <span class="text-xs" style="color: var(--lw-ink-muted)">{{ t('logs.histogramTip') }}</span>
          </div>
          <div v-if="histogram.length" class="flex items-end gap-[2px] h-8">
            <button
              v-for="(item, index) in histogram"
              :key="index"
              type="button"
              class="flex-1 min-w-[2px] rounded-t-[3px] transition-all duration-200 hover:opacity-70"
              style="background-color: var(--lw-secondary)"
              :style="{ height: barHeight(item.count) }"
              :title="`${formatHour(item.hour)} · ${item.count}`"
              :aria-label="`${formatHour(item.hour)} ${item.count}`"
              @click="drillHour(index)"
            />
          </div>
          <div v-else class="h-8 flex items-center text-xs" style="color: var(--lw-ink-muted)">
            {{ statsLoading ? t('common.loading') : t('logs.noStats') }}
          </div>
        </div>
      </div>
    </LwCard>

    <LwCard>
      <!-- 主筛选行：字段 + 关键字 + 级别 + 快捷时间，够用不绕 -->
      <div class="flex flex-wrap items-center gap-2">
        <el-select v-model="query.field" style="width: 116px" @change="handleSearch">
          <el-option v-for="item in FIELD_OPTIONS" :key="item.value" :label="fieldLabel(item.value)" :value="item.value" />
        </el-select>
        <el-input
          v-model="query.keyword"
          :placeholder="t('logs.keywordPlaceholder')"
          clearable
          class="flex-1 min-w-[200px]"
          @keyup.enter="handleSearch"
          @clear="handleSearch"
        />
        <el-select v-model="query.level" :placeholder="t('logs.level')" clearable style="width: 116px" @change="handleSearch">
          <el-option v-for="item in ['ERROR', 'WARN', 'INFO']" :key="item" :label="t(`logs.level_${item.toLowerCase()}`)" :value="item" />
        </el-select>
        <LwSegmented
          :model-value="activePreset"
          :options="PRESETS.map((item) => ({ value: item.key, label: t(`logs.preset_${item.key}`) }))"
          equal
          size="sm"
          @update:model-value="applyPreset"
        />
        <LwButton variant="ghost" @click="handleSearch">
          <template #icon><el-icon><Search /></el-icon></template>
          {{ t('common.search') }}
        </LwButton>
        <LwButton variant="text" @click="handleReset">
          <template #icon><el-icon><RefreshLeft /></el-icon></template>
          {{ t('common.reset') }}
        </LwButton>
      </div>

      <!-- 高级筛选：按需展开，避免首屏被一排输入框淹没 -->
      <div class="flex flex-wrap items-center gap-3 mt-3">
        <LwButton variant="text" size="sm" :aria-expanded="advancedOpen" @click="advancedOpen = !advancedOpen">
          <template #icon>
            <el-icon class="transition-transform duration-200" :class="{ 'rotate-180': advancedOpen }"><ArrowDown /></el-icon>
          </template>
          {{ t('logs.moreFilters') }}
          <span v-if="advancedCount > 0 && !advancedOpen" class="lw-filter-badge">{{ advancedCount }}</span>
        </LwButton>
        <p class="text-xs" style="color: var(--lw-ink-muted)">{{ t('logs.tipShort') }}</p>
      </div>
      <transition name="lw-fade">
        <div v-if="advancedOpen" class="flex flex-wrap items-center gap-2 mt-3">
          <el-input
            v-model="query.logger"
            :placeholder="t('logs.loggerPlaceholder')"
            clearable
            style="width: 240px"
            @keyup.enter="handleSearch"
            @clear="handleSearch"
          />
          <el-input
            v-model="query.traceId"
            :placeholder="t('logs.traceIdPlaceholder')"
            clearable
            style="width: 220px"
            @keyup.enter="handleSearch"
            @clear="handleSearch"
          />
          <el-input
            v-if="scope === 'global'"
            v-model="query.userId"
            :placeholder="t('logs.userIdPlaceholder')"
            clearable
            style="width: 220px"
            @keyup.enter="handleSearch"
            @clear="handleSearch"
          />
          <el-input
            v-model="query.runId"
            :placeholder="t('logs.runIdPlaceholder')"
            clearable
            style="width: 200px"
            @keyup.enter="handleSearch"
            @clear="handleSearch"
          />
          <el-date-picker
            v-model="query.from"
            type="datetime"
            value-format="x"
            :placeholder="t('logs.from')"
            style="width: 190px"
            @change="onTimeChange"
          />
          <el-date-picker
            v-model="query.to"
            type="datetime"
            value-format="x"
            :placeholder="t('logs.to')"
            style="width: 190px"
            @change="onTimeChange"
          />
        </div>
      </transition>
    </LwCard>

    <LwCard>
      <LwPageState :loading="loading" :error="error" :empty="!docs.length" :skeleton-rows="8" @retry="search">
        <div v-if="traceMode" class="text-xs mb-3 px-3 py-2 rounded-lg" style="background: var(--lw-secondary-tint); color: var(--lw-primary-ink)">
          {{ t('logs.traceModeTip', { total }) }}
        </div>
        <div class="flex flex-col gap-2">
          <div
            v-for="doc in displayDocs"
            :key="doc.logId"
            class="rounded-lg border px-4 py-3 cursor-pointer transition-all duration-200 hover:lw-shadow-sm"
            style="border-color: var(--lw-border)"
            role="button"
            tabindex="0"
            :aria-expanded="expanded === doc.logId"
            @click="expanded = expanded === doc.logId ? '' : doc.logId"
            @keydown.enter="expanded = expanded === doc.logId ? '' : doc.logId"
          >
            <div class="flex items-center gap-3">
              <LwTag :tone="levelTone(doc.level)" class="w-16 justify-center shrink-0">{{ doc.level }}</LwTag>
              <span class="text-xs font-mono opacity-70 shrink-0">{{ formatTime(doc.ts) }}</span>
              <span class="text-sm flex-1 min-w-0 lw-log-msg" style="color: var(--lw-ink)">
                <template v-for="(seg, index) in highlight(doc.message, query.field === 'message' ? query.keyword : '')" :key="index">
                  <mark v-if="seg.hit" class="lw-mark">{{ seg.text }}</mark>
                  <span v-else>{{ seg.text }}</span>
                </template>
              </span>
              <LwTag v-if="doc.exception" tone="error" class="shrink-0">{{ t('logs.hasException') }}</LwTag>
              <span class="text-xs opacity-50 shrink-0 hidden md:block max-w-[180px] truncate">{{ doc.logger }}</span>
              <el-icon class="shrink-0 transition-transform duration-200" :class="{ 'rotate-180': expanded === doc.logId }" style="color: var(--lw-ink-muted)">
                <ArrowDown />
              </el-icon>
            </div>
            <transition name="lw-fade">
              <div v-if="expanded === doc.logId" class="mt-3 text-xs flex flex-col gap-2">
                <div class="text-xs font-bold" style="color: var(--lw-ink-muted)">{{ t('logs.fullMessage') }}</div>
                <pre class="whitespace-pre-wrap break-all p-3 rounded-lg max-h-64 overflow-auto" style="background: var(--lw-surface); color: var(--lw-ink); border: 1px solid var(--lw-border)">{{ doc.message || t('logs.noMessage') }}</pre>
                <div class="flex flex-wrap items-center gap-3 opacity-60">
                  <span>{{ t('logs.traceId') }}: {{ doc.traceId || '-' }}</span>
                  <span>{{ t('logs.logger') }}: {{ doc.logger }}</span>
                  <span>{{ t('logs.thread') }}: {{ doc.thread || '-' }}</span>
                  <span v-if="doc.userId">{{ t('logs.userId') }}: {{ doc.userId }}</span>
                  <span v-if="doc.runId">{{ t('logs.runId') }}: {{ doc.runId }}</span>
                </div>
                <div class="flex flex-wrap items-center gap-2">
                  <LwButton variant="secondary" size="sm" @click.stop="copyTraceId(doc.traceId ?? '')">
                    {{ copiedId === doc.traceId ? t('logs.copied') : t('logs.copyTraceId') }}
                  </LwButton>
                  <LwButton variant="ghost" size="sm" @click.stop="filterByTrace(doc.traceId ?? '')">
                    {{ t('logs.filterByTrace') }}
                  </LwButton>
                  <LwButton
                    v-if="doc.userId && scope === 'global'"
                    variant="ghost"
                    size="sm"
                    @click.stop="filterByUser(doc.userId)"
                  >
                    {{ t('logs.filterByUser') }}
                  </LwButton>
                  <LwButton v-if="doc.runId" variant="ghost" size="sm" @click.stop="filterByRun(doc.runId)">
                    {{ t('logs.filterByRun') }}
                  </LwButton>
                </div>
                <pre
                  v-if="doc.exception"
                  class="whitespace-pre-wrap break-all max-h-64 overflow-auto p-3 rounded-lg"
                  style="background: var(--lw-error-tint); color: var(--lw-error-ink)"
                >{{ doc.exception }}</pre>
              </div>
            </transition>
          </div>
        </div>
        <div class="flex justify-end mt-4">
          <el-pagination
            v-model:current-page="query.page"
            v-model:page-size="query.size"
            :total="total"
            :page-sizes="[20, 50, 100]"
            layout="total, sizes, prev, pager, next"
            @current-change="search"
            @size-change="handleSearch"
          />
        </div>
      </LwPageState>
    </LwCard>
  </div>
</template>

<style scoped>
.lw-mark {
  background-color: var(--lw-secondary-tint);
  color: var(--lw-primary-ink);
  border-radius: 2px;
  padding: 0 1px;
}

/* 折叠态两行预览：短消息不截断，长消息不撑高列表，全文进展开区 */
.lw-log-msg {
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
  word-break: break-all;
}

/* 高级筛选计数角标 */
.lw-filter-badge {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 16px;
  height: 16px;
  padding: 0 4px;
  border-radius: var(--lw-radius-pill);
  background-color: var(--lw-primary);
  color: var(--lw-on-primary);
  font-size: 10px;
  font-weight: 700;
}
</style>
