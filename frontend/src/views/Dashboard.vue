<script setup lang="ts">
import { Clock, Coin, Document, Files, Reading, TrendCharts } from '@element-plus/icons-vue'
import { computed, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import type { RunVO } from '@/types/api'
import LwAreaChart from '@/components/charts/LwAreaChart.vue'
import LwBarChart from '@/components/charts/LwBarChart.vue'
import LwDonutChart from '@/components/charts/LwDonutChart.vue'
import LwButton from '@/components/ui/LwButton.vue'
import LwCard from '@/components/ui/LwCard.vue'
import LwEmpty from '@/components/ui/LwEmpty.vue'
import LwErrorBar from '@/components/ui/LwErrorBar.vue'
import LwMetricCard from '@/components/ui/LwMetricCard.vue'
import LwPageState from '@/components/ui/LwPageState.vue'
import LwSkeleton from '@/components/ui/LwSkeleton.vue'
import LwTag from '@/components/ui/LwTag.vue'
import { useRunStatus } from '@/composables/useRunStatus'
import { useMonitorStore } from '@/stores/monitor'
import { useUserStore } from '@/stores/user'
import { formatTime } from '@/utils/const'

/** 仪表盘：指标卡 + 快捷入口 + 运行趋势 + Token/工具分布 + 最近运行，主数据源 /monitor/overview */
const { t } = useI18n()
const router = useRouter()
const userStore = useUserStore()
const monitorStore = useMonitorStore()
const runStatus = useRunStatus()

const overview = computed(() => monitorStore.overview)
const loading = computed(() => monitorStore.loading)
const error = computed(() => monitorStore.error)

/** 最近运行独立三态：概览失败不该把最近运行也一起打掉 */
const recent = computed(() => monitorStore.recentRuns)
const recentLoading = computed(() => monitorStore.recentLoading)
const recentError = computed(() => monitorStore.recentError)

/** 成功率 = 成功运行 / 运行总数，无运行时显示占位 */
const successRate = computed(() => {
  const total = overview.value?.runCount ?? 0
  if (total <= 0) return '-'
  const success = overview.value?.successRunCount ?? 0
  return `${Math.round((success / total) * 100)}%`
})

/** 成功率语义色：低于 70% 视为异常，70%~90% 警示，达标为成功 */
const rateTone = computed<'muted' | 'success' | 'warning' | 'error'>(() => {
  const total = overview.value?.runCount ?? 0
  if (total <= 0) return 'muted'
  const rate = (overview.value?.successRunCount ?? 0) / total
  if (rate >= 0.9) return 'success'
  if (rate >= 0.7) return 'warning'
  return 'error'
})

const metrics = computed(() => [
  {
    key: 'metricRuns',
    value: String(overview.value?.todayRunCount ?? 0),
    hint: `${t('dashboard.totalRuns')}: ${overview.value?.runCount ?? 0}`,
    hintTone: 'muted' as const,
    icon: TrendCharts,
    tone: 'primary' as const,
  },
  {
    key: 'metricTokens',
    value: formatTokens(overview.value?.totalTokens ?? 0),
    hint: `${t('dashboard.sessionCount')}: ${overview.value?.sessionCount ?? 0}`,
    hintTone: 'muted' as const,
    icon: Coin,
    tone: 'secondary' as const,
  },
  {
    key: 'metricSuccess',
    value: successRate.value,
    hint: `${t('dashboard.successCount')}: ${overview.value?.successRunCount ?? 0}`,
    hintTone: rateTone.value,
    icon: Clock,
    tone: 'ink' as const,
  },
])

/** 快捷入口：四枚磁贴，登录即可用，不下沉到权限点判断（各自目标页再校验） */
const quickLinks = computed(() => [
  { key: 'quickNewSession', to: '/console', icon: TrendCharts },
  { key: 'quickNewDoc', to: '/writer', icon: Document },
  { key: 'quickUpload', to: '/files', icon: Files },
  { key: 'quickKb', to: '/kb', icon: Reading },
])

const trendCategories = computed(() => (overview.value?.runTrend ?? []).map((point) => point.date))
const trendValues = computed(() => (overview.value?.runTrend ?? []).map((point) => point.value))
const tokenCategories = computed(() => (overview.value?.tokenTrend ?? []).map((point) => point.date))
const tokenValues = computed(() => (overview.value?.tokenTrend ?? []).map((point) => point.value))
const toolLabels = computed(() => Object.keys(overview.value?.toolDistribution ?? {}))
const toolValues = computed(() => Object.values(overview.value?.toolDistribution ?? {}))
const hasTrend = computed(() => trendValues.value.some((value) => value > 0))

const subtitle = computed(() => t('dashboard.subtitle', { name: userStore.nickname || '-' }))

function formatTokens(value: number): string {
  if (value >= 1_000_000) return t('dashboard.unitMillion', { n: (value / 1_000_000).toFixed(1) })
  if (value >= 1_000) return t('dashboard.unitThousand', { n: (value / 1_000).toFixed(1) })
  return String(value)
}

function runTokens(run: RunVO): string {
  return formatTokens((run.inputTokens ?? 0) + (run.outputTokens ?? 0))
}

function runCost(run: RunVO): string {
  return run.durationMs ? `${(run.durationMs / 1000).toFixed(1)}s` : '-'
}

/** 行点击进运行记录：带 runId 让目标页直接定位，不必再自己找一遍 */
function openRun(run: RunVO): void {
  router.push({ path: '/runs', query: { runId: run.runId } })
}

async function load(): Promise<void> {
  await Promise.all([monitorStore.loadOverview(), monitorStore.loadRecentRuns()])
}

onMounted(load)
</script>

<template>
  <div class="lw-page">
    <div class="lw-page-head">
      <div>
        <h2 class="lw-title">{{ t('dashboard.title') }}</h2>
        <p class="lw-subtitle mt-1">{{ subtitle }}</p>
      </div>
      <div class="flex gap-2">
        <LwButton variant="secondary" @click="load">{{ t('common.refresh') }}</LwButton>
        <LwButton @click="router.push('/console')">{{ t('dashboard.newSession') }}</LwButton>
      </div>
    </div>

    <LwPageState :loading="loading" :error="error" :empty="!overview" :skeleton-rows="6" @retry="load">
      <div class="grid grid-cols-1 sm:grid-cols-3 gap-4">
        <LwMetricCard
          v-for="metric in metrics"
          :key="metric.key"
          :label="t(`dashboard.${metric.key}`)"
          :value="metric.value"
          :hint="metric.hint"
          :hint-tone="metric.hintTone"
          :tone="metric.tone"
          :icon="metric.icon"
        />
      </div>

      <!-- 首次使用引导：还没有任何运行时给出下一步动作，不留空白仪表盘 -->
      <LwCard v-if="(overview?.runCount ?? 0) === 0" class="flex flex-col sm:flex-row items-start sm:items-center gap-4">
        <div class="flex-1">
          <h3 class="text-sm font-bold" style="color: var(--lw-primary-ink)">{{ t('dashboard.firstRunTitle') }}</h3>
          <p class="text-xs mt-1" style="color: var(--lw-ink-muted)">{{ t('dashboard.firstRunDesc') }}</p>
        </div>
        <div class="flex gap-2 shrink-0">
          <LwButton variant="secondary" size="sm" @click="router.push('/kb')">{{ t('dashboard.firstRunKb') }}</LwButton>
          <LwButton size="sm" @click="router.push('/console')">{{ t('dashboard.firstRunConsole') }}</LwButton>
        </div>
      </LwCard>

      <!-- 快捷入口：四枚磁贴，悬停上移 + 图标放大，是仪表盘唯一的「下一步动作」区 -->
      <div class="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4">
        <button
          v-for="link in quickLinks"
          :key="link.key"
          type="button"
          class="lw-card p-4 flex items-center gap-3 text-left group transition-all duration-200 hover:-translate-y-0.5 hover:lw-shadow-md"
          @click="router.push(link.to)"
        >
          <span class="lw-quick__tile">
            <el-icon :size="18"><component :is="link.icon" /></el-icon>
          </span>
          <span class="min-w-0">
            <span class="block text-sm font-bold" style="color: var(--lw-ink)">{{ t(`dashboard.${link.key}`) }}</span>
            <span class="block text-xs mt-0.5 truncate" style="color: var(--lw-ink-muted)">
              {{ t(`dashboard.${link.key}Desc`) }}
            </span>
          </span>
        </button>
      </div>

      <LwCard :title="t('dashboard.trendTitle')" :desc="t('dashboard.trendDesc')">
        <template #actions>
          <LwTag tone="primary">{{ t('dashboard.rangeRecent') }}</LwTag>
        </template>
        <LwAreaChart
          v-if="hasTrend"
          :categories="trendCategories"
          :values="trendValues"
          :name="t('dashboard.metricRuns')"
          :height="300"
        />
        <p v-else class="py-16 text-center text-sm" style="color: var(--lw-ink-muted)">
          {{ t('dashboard.trendEmpty') }}
        </p>
      </LwCard>

      <div class="grid grid-cols-1 lg:grid-cols-2 gap-4">
        <LwCard :title="t('dashboard.tokenTrendTitle')" :desc="t('dashboard.trendDesc')">
          <LwBarChart
            v-if="tokenValues.some((value) => value > 0)"
            :categories="tokenCategories"
            :values="tokenValues"
            :name="t('dashboard.metricTokens')"
            :height="260"
            secondary
          />
          <p v-else class="py-14 text-center text-sm" style="color: var(--lw-ink-muted)">
            {{ t('dashboard.trendEmpty') }}
          </p>
        </LwCard>
        <LwCard :title="t('dashboard.toolTitle')" :desc="t('dashboard.toolDesc')">
          <template #actions>
            <LwTag tone="primary">{{ t('dashboard.totalRuns') }} {{ overview?.runCount ?? 0 }}</LwTag>
          </template>
          <LwDonutChart v-if="toolLabels.length" :labels="toolLabels" :values="toolValues" :height="260" />
          <p v-else class="py-14 text-center text-sm" style="color: var(--lw-ink-muted)">
            {{ t('dashboard.toolEmpty') }}
          </p>
        </LwCard>
      </div>

      <!-- 最近运行：紧凑五行，行点击进运行记录并定位；区域级三态，不牵连上方图表 -->
      <LwCard :title="t('dashboard.recentTitle')" :desc="t('dashboard.recentDesc')">
        <template #actions>
          <LwButton variant="text" size="sm" @click="router.push('/runs')">{{ t('dashboard.recentAll') }}</LwButton>
        </template>
        <LwSkeleton v-if="recentLoading" :rows="5" />
        <LwErrorBar v-else-if="recentError" :message="recentError" @retry="monitorStore.loadRecentRuns()" />
        <LwEmpty v-else-if="!recent.length" :description="t('dashboard.recentEmpty')" />
        <div v-else class="lw-recent">
          <div class="lw-recent__head">
            <span>{{ t('dashboard.colTime') }}</span>
            <span>{{ t('dashboard.colStatus') }}</span>
            <span>{{ t('dashboard.colModel') }}</span>
            <span class="text-right">{{ t('dashboard.colDuration') }}</span>
            <span class="text-right">{{ t('dashboard.colTokens') }}</span>
          </div>
          <button
            v-for="run in recent"
            :key="run.runId"
            type="button"
            class="lw-recent__row"
            @click="openRun(run)"
          >
            <span class="lw-cell-mono">{{ formatTime(run.createdAt) }}</span>
            <span><LwTag :tone="runStatus.tone(run.status)">{{ runStatus.label(run.status) }}</LwTag></span>
            <span class="truncate text-sm">{{ run.modelName || '-' }}</span>
            <span class="lw-cell-mono text-right">{{ runCost(run) }}</span>
            <span class="lw-cell-mono text-right">{{ runTokens(run) }}</span>
          </button>
        </div>
      </LwCard>
    </LwPageState>
  </div>
</template>
