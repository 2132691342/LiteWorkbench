<script setup lang="ts">
import { Search } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import LwBarChart from '@/components/charts/LwBarChart.vue'
import LwButton from '@/components/ui/LwButton.vue'
import LwCard from '@/components/ui/LwCard.vue'
import LwPageState from '@/components/ui/LwPageState.vue'
import LwSegmented from '@/components/ui/LwSegmented.vue'
import LwTag from '@/components/ui/LwTag.vue'
import { logApi } from '@/api'
import type { LogDocVO } from '@/types/api'
import { formatTime } from '@/utils/const'

/** 日志中心：分布概览 + 多维过滤 + 全文检索，命中行内高亮，支持 traceId 钻取 */
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

/** 时间归一：空值与非法值一律 undefined，数字字符串转毫秒 */
function toMillis(value: TimeInput): number | undefined {
  if (value === undefined || value === '') return undefined
  const parsed = Number(value)
  return Number.isFinite(parsed) && parsed > 0 ? parsed : undefined
}

/** 时间选择器变更：归一后记录为数字并清掉预设高亮 */
function onTimeChange(): void {
  query.from = toMillis(query.from)
  query.to = toMillis(query.to)
  activePreset.value = ''
  handleSearch()
}

/** 快捷时间预设：15 分钟 / 1 小时 / 24 小时 / 7 天 */
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

/** 级别计数 Map，概览卡直接查 */
const levelCount = computed(() => {
  const map = new Map<string, number>()
  levels.value.forEach((item) => map.set(item.level, item.count))
  return map
})

function levelTotal(level: string): string {
  return (levelCount.value.get(level) ?? 0).toLocaleString()
}

/** 直方图类目：按桶数量自适应格式，超过 48 桶只显示首末；天桶只显示日期 */
const histogramCategories = computed(() =>
  histogram.value.map((item, index) => {
    if (histogram.value.length <= 48 || index === 0 || index === histogram.value.length - 1) {
      return formatHour(item.hour)
    }
    return ''
  }),
)

function formatHour(value: number): string {
  const date = new Date(value)
  const pad = (n: number) => String(n).padStart(2, '0')
  if (bucketMs.value >= 86_400_000) {
    return `${pad(date.getMonth() + 1)}-${pad(date.getDate())}`
  }
  return `${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:00`
}

/** 关键字命中高亮：按词切分，命中段包 <mark>，未命中原样返回 */
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

/** 条件变更后回到第一页并防抖刷新 */
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

/** 快捷时间预设：点击即写入时间范围并刷新 */
function applyPreset(key: string): void {
  const preset = PRESETS.find((item) => item.key === key)
  if (!preset) return
  activePreset.value = key
  query.to = Date.now()
  query.from = query.to - preset.ms
  handleSearch()
}

/** 直方图钻取：点某桶把时间范围收敛到该桶 */
function drillHour(index: number): void {
  const bucket = histogram.value[index]
  if (!bucket) return
  activePreset.value = ''
  query.from = bucket.hour
  query.to = bucket.hour + bucketMs.value
  handleSearch()
}

/** 复制 traceId：按钮文案 400ms 切对勾并 toast 提示 */
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

/** 按 traceId 钻取：写入过滤区并刷新，把一次请求的日志串成时间线 */
function filterByTrace(traceId: string): void {
  if (!traceId) return
  query.field = 'traceId'
  query.keyword = traceId
  query.page = 1
  handleSearch()
}

/** 按 userId 钻取：全局视角下查看某用户的全部日志 */
function filterByUser(userId: string): void {
  if (!userId) return
  query.userId = userId
  query.page = 1
  handleSearch()
}

/** 按 runId 钻取：把一次运行的全部日志串成时间线 */
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

    <!-- 分布概览：级别计数卡 + 时间直方图，随过滤条件联动 -->
    <div class="grid grid-cols-1 lg:grid-cols-4 gap-4">
      <LwCard v-for="level in ['ERROR', 'WARN', 'INFO']" :key="level">
        <div class="flex items-center justify-between">
          <span class="text-xs font-bold" style="color: var(--lw-ink-muted)">{{ t(`logs.level_${level.toLowerCase()}`) }}</span>
          <LwTag :tone="level === 'ERROR' ? 'error' : level === 'WARN' ? 'warning' : 'primary'">
            {{ level }}
          </LwTag>
        </div>
        <div class="text-2xl font-bold tracking-tight" style="color: var(--lw-ink)">
          {{ statsLoading ? '·' : levelTotal(level) }}
        </div>
        <div class="text-xs -mt-2" style="color: var(--lw-ink-muted)">{{ t('logs.inRange') }}</div>
      </LwCard>
      <LwCard class="lg:col-span-1">
        <div class="flex items-center justify-between">
          <span class="text-xs font-bold" style="color: var(--lw-ink-muted)">{{ t('logs.histogram') }}</span>
          <span class="text-xs" style="color: var(--lw-ink-muted)">{{ t('logs.histogramTip') }}</span>
        </div>
        <div class="flex-1 min-h-0">
          <LwBarChart
            v-if="histogram.length"
            :categories="histogramCategories"
            :values="histogram.map((item) => item.count)"
            :height="132"
            secondary
            clickable
            @bucket-click="drillHour"
          />
          <div v-else class="h-full flex items-center justify-center text-xs" style="color: var(--lw-ink-muted)">
            {{ statsLoading ? t('common.loading') : t('logs.noStats') }}
          </div>
        </div>
      </LwCard>
    </div>

    <!-- 过滤区 -->
    <LwCard>
      <div class="flex flex-wrap items-center gap-3">
        <el-select v-model="query.field" style="width: 130px" @change="handleSearch">
          <el-option v-for="item in FIELD_OPTIONS" :key="item.value" :label="fieldLabel(item.value)" :value="item.value" />
        </el-select>
        <el-input
          v-model="query.keyword"
          :placeholder="t('logs.keywordPlaceholder')"
          clearable
          style="width: 280px"
          @keyup.enter="handleSearch"
          @clear="handleSearch"
        />
        <el-select v-model="query.level" :placeholder="t('logs.level')" clearable style="width: 130px" @change="handleSearch">
          <el-option v-for="item in ['ERROR', 'WARN', 'INFO']" :key="item" :label="t(`logs.level_${item.toLowerCase()}`)" :value="item" />
        </el-select>
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
        <LwButton variant="ghost" @click="handleSearch">
          <template #icon><el-icon><Search /></el-icon></template>
          {{ t('common.search') }}
        </LwButton>
      </div>
      <div class="flex flex-wrap items-center gap-3 mt-3">
        <span class="text-xs" style="color: var(--lw-ink-muted)">{{ t('logs.quickRange') }}</span>
        <LwSegmented
          :model-value="activePreset"
          :options="PRESETS.map((item) => ({ value: item.key, label: t(`logs.preset_${item.key}`) }))"
          equal
          @update:model-value="applyPreset"
        />
      </div>
      <p class="text-xs mt-3" style="color: var(--lw-ink-muted)">
        {{ t('logs.syntaxTip') }}
      </p>
    </LwCard>

    <!-- 结果列表 -->
    <LwCard>
      <LwPageState :loading="loading" :error="error" :empty="!docs.length" :skeleton-rows="8" @retry="search">
        <div v-if="traceMode" class="text-xs mb-3 px-3 py-2 rounded-lg" style="background: var(--lw-secondary-tint); color: var(--lw-primary)">
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
              <LwTag :tone="levelTone(doc.level)">{{ doc.level }}</LwTag>
              <span class="text-xs font-mono opacity-70 shrink-0">{{ formatTime(doc.ts) }}</span>
              <span class="text-sm truncate flex-1" style="color: var(--lw-ink)">
                <template v-for="(seg, index) in highlight(doc.message, query.field === 'message' ? query.keyword : '')" :key="index">
                  <mark v-if="seg.hit" class="lw-mark">{{ seg.text }}</mark>
                  <span v-else>{{ seg.text }}</span>
                </template>
              </span>
              <span class="text-xs opacity-50 shrink-0 hidden md:block">{{ doc.logger }}</span>
            </div>
            <transition name="lw-fade">
              <div v-if="expanded === doc.logId" class="mt-3 text-xs flex flex-col gap-2">
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
/* 关键字高亮：浅紫底圆角，不用浏览器默认黄 */
.lw-mark {
  background-color: var(--lw-secondary-tint);
  color: var(--lw-primary);
  border-radius: 2px;
  padding: 0 1px;
}
</style>
