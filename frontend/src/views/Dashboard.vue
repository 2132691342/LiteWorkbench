<script setup lang="ts">
import { Coin, DataLine, TrendCharts } from '@element-plus/icons-vue'
import { computed, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import LwAreaChart from '@/components/charts/LwAreaChart.vue'
import LwBarChart from '@/components/charts/LwBarChart.vue'
import LwDonutChart from '@/components/charts/LwDonutChart.vue'
import LwButton from '@/components/ui/LwButton.vue'
import LwCard from '@/components/ui/LwCard.vue'
import LwPageState from '@/components/ui/LwPageState.vue'
import { useMonitorStore } from '@/stores/monitor'
import { useUserStore } from '@/stores/user'

/** 仪表盘：指标卡 + 运行趋势 + 工具分布，数据源 /monitor/overview */
const { t } = useI18n()
const router = useRouter()
const userStore = useUserStore()
const monitorStore = useMonitorStore()

const overview = computed(() => monitorStore.overview)
const loading = computed(() => monitorStore.loading)
const error = computed(() => monitorStore.error)

/** 主图表视图：运行趋势 / Token 趋势 */
const view = ref<'runs' | 'tokens'>('runs')
const views = [
  { key: 'runs' as const, label: 'dashboard.viewRuns' },
  { key: 'tokens' as const, label: 'dashboard.viewTokens' },
]

/** 成功率 = 成功运行 / 运行总数，无运行时显示占位 */
const successRate = computed(() => {
  const total = overview.value?.runCount ?? 0
  if (total <= 0) return '-'
  const success = overview.value?.successRunCount ?? 0
  return `${Math.round((success / total) * 100)}%`
})

const metrics = computed(() => [
  {
    key: 'metricRuns',
    value: String(overview.value?.todayRunCount ?? 0),
    delta: `${t('dashboard.totalRuns')}: ${overview.value?.runCount ?? 0}`,
    icon: DataLine,
    tone: 'var(--lw-primary)',
  },
  {
    key: 'metricTokens',
    value: formatTokens(overview.value?.totalTokens ?? 0),
    delta: `${t('dashboard.sessionCount')}: ${overview.value?.sessionCount ?? 0}`,
    icon: Coin,
    tone: 'var(--lw-secondary)',
  },
  {
    key: 'metricSuccess',
    value: successRate.value,
    delta: `${t('dashboard.successCount')}: ${overview.value?.successRunCount ?? 0}`,
    icon: TrendCharts,
    tone: 'var(--lw-ink)',
  },
])

const trendCategories = computed(() => (overview.value?.runTrend ?? []).map((point) => point.date))
const trendValues = computed(() => (overview.value?.runTrend ?? []).map((point) => point.value))
const tokenCategories = computed(() => (overview.value?.tokenTrend ?? []).map((point) => point.date))
const tokenValues = computed(() => (overview.value?.tokenTrend ?? []).map((point) => point.value))
const toolLabels = computed(() => Object.keys(overview.value?.toolDistribution ?? {}))
const toolValues = computed(() => Object.values(overview.value?.toolDistribution ?? {}))

const subtitle = computed(() => t('dashboard.subtitle', { name: userStore.nickname || '-' }))

function formatTokens(value: number): string {
  if (value >= 1_000_000) return `${(value / 1_000_000).toFixed(1)}M`
  if (value >= 1_000) return `${(value / 1_000).toFixed(1)}K`
  return String(value)
}

async function load(): Promise<void> {
  await monitorStore.loadOverview()
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
        <LwButton variant="ghost" @click="load">{{ t('common.refresh') }}</LwButton>
        <LwButton @click="router.push('/console')">{{ t('dashboard.newSession') }}</LwButton>
      </div>
    </div>

    <LwPageState :loading="loading" :error="error" :skeleton-rows="6" @retry="load">
      <div class="flex flex-col sm:flex-row justify-end gap-3">
        <div
          class="flex bg-white p-1 rounded-lg border lw-shadow-sm"
          style="border-color: var(--lw-border)"
        >
          <button
            v-for="item in views"
            :key="item.key"
            class="px-3 py-1.5 rounded-md text-xs font-bold transition-all duration-200"
            :style="
              view === item.key
                ? 'background: var(--lw-surface-subtle); color: var(--lw-primary); box-shadow: var(--lw-shadow-sm)'
                : 'color: var(--lw-ink-muted)'
            "
            @click="view = item.key"
          >
            {{ t(item.label) }}
          </button>
        </div>
      </div>

      <div class="grid grid-cols-1 sm:grid-cols-3 gap-4">
        <div
          v-for="metric in metrics"
          :key="metric.key"
          class="lw-card p-5 h-32 relative overflow-hidden group transition-all duration-200 hover:lw-shadow-md hover:-translate-y-0.5"
        >
          <div class="absolute right-0 top-0 p-4 opacity-10 transition-transform duration-200 group-hover:scale-110">
            <el-icon :size="56" :color="metric.tone"><component :is="metric.icon" /></el-icon>
          </div>
          <div class="text-sm font-bold mb-4" style="color: var(--lw-ink-muted)">{{ t(`dashboard.${metric.key}`) }}</div>
          <div class="text-2xl font-bold tracking-tight" style="color: var(--lw-ink)">{{ metric.value }}</div>
          <div class="text-xs font-bold mt-1" style="color: var(--lw-ink-muted)">{{ metric.delta }}</div>
        </div>
      </div>

      <!-- 首次使用引导：还没有任何运行时给出下一步动作，不留空白仪表盘 -->
      <LwCard v-if="(overview?.runCount ?? 0) === 0" class="flex flex-col sm:flex-row items-start sm:items-center gap-4">
        <div class="flex-1">
          <h3 class="text-sm font-bold" style="color: var(--lw-primary)">{{ t('dashboard.firstRunTitle') }}</h3>
          <p class="text-xs mt-1" style="color: var(--lw-ink-muted)">{{ t('dashboard.firstRunDesc') }}</p>
        </div>
        <div class="flex gap-2 shrink-0">
          <LwButton variant="secondary" size="sm" @click="router.push('/kb')">{{ t('dashboard.firstRunKb') }}</LwButton>
          <LwButton size="sm" @click="router.push('/console')">{{ t('dashboard.firstRunConsole') }}</LwButton>
        </div>
      </LwCard>

      <LwCard
        :title="view === 'runs' ? t('dashboard.trendTitle') : t('dashboard.tokenTrendTitle')"
        :desc="t('dashboard.trendDesc')"
      >
        <LwAreaChart
          v-if="view === 'runs'"
          :categories="trendCategories"
          :values="trendValues"
          :name="t('dashboard.metricRuns')"
          :height="300"
        />
        <LwAreaChart
          v-else
          :categories="tokenCategories"
          :values="tokenValues"
          :name="t('dashboard.metricTokens')"
          :height="300"
          secondary
        />
      </LwCard>

      <div class="grid grid-cols-1 lg:grid-cols-2 gap-4">
        <LwCard :title="t('dashboard.tokenTrendTitle')" :desc="t('dashboard.trendDesc')">
          <LwBarChart
            :categories="tokenCategories"
            :values="tokenValues"
            :name="t('dashboard.metricTokens')"
            :height="260"
            secondary
          />
        </LwCard>
        <LwCard :title="t('dashboard.toolTitle')" :desc="t('dashboard.toolDesc')">
          <LwDonutChart :labels="toolLabels" :values="toolValues" :height="260" />
        </LwCard>
      </div>
    </LwPageState>
  </div>
</template>
