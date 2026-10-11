<script setup lang="ts">
import { Coin, DataLine, TrendCharts } from '@element-plus/icons-vue'
import { computed, onMounted } from 'vue'
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
    value: String(overview.value?.runCount ?? 0),
    delta: t('dashboard.metricSuccessDesc'),
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
      <div class="grid grid-cols-1 sm:grid-cols-3 gap-4">
        <div
          v-for="metric in metrics"
          :key="metric.key"
          class="lw-card p-5 h-32 relative overflow-hidden group transition-all duration-200 hover:shadow-md hover:-translate-y-0.5"
        >
          <div class="absolute right-0 top-0 p-4 opacity-10 transition-transform duration-200 group-hover:scale-110">
            <el-icon :size="56" :color="metric.tone"><component :is="metric.icon" /></el-icon>
          </div>
          <div class="text-sm font-bold mb-4" style="color: var(--lw-ink-muted)">{{ t(`dashboard.${metric.key}`) }}</div>
          <div class="text-2xl font-bold tracking-tight" style="color: var(--lw-ink)">{{ metric.value }}</div>
          <div class="text-xs font-bold mt-1" style="color: var(--lw-ink-muted)">{{ metric.delta }}</div>
        </div>
      </div>

      <LwCard :title="t('dashboard.trendTitle')" :desc="t('dashboard.trendDesc')">
        <LwAreaChart :categories="trendCategories" :values="trendValues" :name="t('dashboard.metricRuns')" :height="300" />
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
