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
import LwMetricCard from '@/components/ui/LwMetricCard.vue'
import LwPageState from '@/components/ui/LwPageState.vue'
import LwSegmented from '@/components/ui/LwSegmented.vue'
import LwTag from '@/components/ui/LwTag.vue'
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
const viewOptions = computed(() => [
  { value: 'runs', label: t('dashboard.viewRuns') },
  { value: 'tokens', label: t('dashboard.viewTokens') },
])

function setView(value: string): void {
  view.value = value === 'tokens' ? 'tokens' : 'runs'
}

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
    icon: DataLine,
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
    icon: TrendCharts,
    tone: 'ink' as const,
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
  if (value >= 1_000_000) return t('dashboard.unitMillion', { n: (value / 1_000_000).toFixed(1) })
  if (value >= 1_000) return t('dashboard.unitThousand', { n: (value / 1_000).toFixed(1) })
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

    <LwPageState :loading="loading" :error="error" :empty="!overview" :skeleton-rows="6" @retry="load">
      <div class="flex flex-col sm:flex-row justify-end gap-3">
        <LwSegmented :model-value="view" :options="viewOptions" equal @update:model-value="setView" />
      </div>

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

      <LwCard
        :title="view === 'runs' ? t('dashboard.trendTitle') : t('dashboard.tokenTrendTitle')"
        :desc="t('dashboard.trendDesc')"
      >
        <template #actions>
          <LwTag tone="primary">{{ t('dashboard.rangeRecent') }}</LwTag>
        </template>
        <transition name="lw-fade" mode="out-in">
          <LwAreaChart
            v-if="view === 'runs'"
            :key="'runs'"
            :categories="trendCategories"
            :values="trendValues"
            :name="t('dashboard.metricRuns')"
            :height="300"
          />
          <LwAreaChart
            v-else
            :key="'tokens'"
            :categories="tokenCategories"
            :values="tokenValues"
            :name="t('dashboard.metricTokens')"
            :height="300"
            secondary
          />
        </transition>
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
