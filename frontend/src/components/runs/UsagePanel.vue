<script setup lang="ts">
import { DataLine, Download, Timer, Upload } from '@element-plus/icons-vue'
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import type { UsageStatsVO } from '@/types/api'
import LwEmpty from '@/components/ui/LwEmpty.vue'
import LwMetricCard from '@/components/ui/LwMetricCard.vue'
import LwSkeleton from '@/components/ui/LwSkeleton.vue'

/** 用量归因：总量 + 按天趋势 + 按模型分解，数据全部来自 wb_run 的账目字段 */
const props = defineProps<{
  stats: UsageStatsVO | null
  loading?: boolean
}>()

const { t } = useI18n()

const totals = computed(() => props.stats?.totals ?? {})

const modelShares = computed(() => {
  const slices = props.stats?.byModel ?? []
  const sum = slices.reduce((acc, item) => acc + (item.inputTokens ?? 0) + (item.outputTokens ?? 0), 0)
  return slices.map((item, index) => ({
    ...item,
    share: sum <= 0 ? 0 : ((item.inputTokens ?? 0) + (item.outputTokens ?? 0)) / sum,
    tone: index % 2 === 0 ? 'var(--lw-primary)' : 'var(--lw-secondary)',
  }))
})

/** 按天趋势：以 token 峰值为基准做纵向归一，柱高按比例 */
const dailyBars = computed(() => {
  const points = props.stats?.daily ?? []
  const peak = points.reduce((max, item) => Math.max(max, (item.inputTokens ?? 0) + (item.outputTokens ?? 0)), 0)
  return points.map((item) => {
    const value = (item.inputTokens ?? 0) + (item.outputTokens ?? 0)
    return { ...item, value, height: peak <= 0 ? 0 : Math.max((value / peak) * 100, 2) }
  })
})

function formatNumber(value?: number): string {
  if (!value) return '0'
  if (value >= 1_000_000) return `${(value / 1_000_000).toFixed(2)}M`
  if (value >= 1000) return `${(value / 1000).toFixed(1)}k`
  return String(value)
}
</script>

<template>
  <LwSkeleton v-if="loading" :rows="5" />
  <LwEmpty v-else-if="!stats || (stats.daily?.length ?? 0) === 0" :description="t('trace.usageEmpty')" />
  <div v-else class="flex flex-col gap-4">
    <div class="grid grid-cols-2 md:grid-cols-4 gap-3">
      <LwMetricCard
        :label="t('trace.totalRuns')"
        :value="formatNumber(totals.runCount)"
        tone="ink"
        :icon="DataLine"
      />
      <LwMetricCard :label="t('runs.inputTokens')" :value="formatNumber(totals.inputTokens)" :icon="Upload" />
      <LwMetricCard
        :label="t('runs.outputTokens')"
        :value="formatNumber(totals.outputTokens)"
        tone="secondary"
        :icon="Download"
      />
      <LwMetricCard
        :label="t('trace.avgDuration')"
        :value="`${((totals.avgDurationMs ?? 0) / 1000).toFixed(1)}s`"
        :icon="Timer"
      />
    </div>

    <!-- 按天趋势：柱高表示当日 token，柱顶数字为运行数 -->
    <div class="flex flex-col gap-2">
      <span class="text-xs font-bold" style="color: var(--lw-ink)">{{ t('trace.dailyTrend') }}</span>
      <div class="flex items-end gap-1 h-32 overflow-x-auto pb-1">
        <div
          v-for="point in dailyBars"
          :key="point.day"
          class="flex flex-col items-center gap-1 shrink-0 min-w-[1.5rem] flex-1"
          :title="`${point.day} · ${point.runCount ?? 0} runs`"
        >
          <span class="text-[10px] font-mono" style="color: var(--lw-ink-muted)">{{ point.runCount ?? 0 }}</span>
          <div
            class="w-full rounded-t-sm transition-all duration-300"
            :style="{ height: `${point.height}%`, backgroundColor: 'var(--lw-secondary)' }"
          />
          <span class="text-[10px] font-mono" style="color: var(--lw-ink-muted)">
            {{ point.day.slice(5) }}
          </span>
        </div>
      </div>
    </div>

    <div class="flex flex-col gap-2">
      <span class="text-xs font-bold" style="color: var(--lw-ink)">{{ t('trace.byModel') }}</span>
      <div class="flex flex-col gap-2">
        <div v-for="slice in modelShares" :key="slice.modelName" class="flex items-center gap-2">
          <span class="text-xs w-32 truncate" style="color: var(--lw-ink)">
            {{ slice.modelName || t('trace.modelUnknown') }}
          </span>
          <div class="flex-1 h-2 rounded-full overflow-hidden" style="background-color: var(--lw-border)">
            <div
              class="h-full rounded-full transition-all duration-300"
              :style="{ width: `${slice.share * 100}%`, backgroundColor: slice.tone }"
            />
          </div>
          <span class="text-xs font-mono w-24 text-right" style="color: var(--lw-ink-muted)">
            {{ formatNumber((slice.inputTokens ?? 0) + (slice.outputTokens ?? 0)) }}
          </span>
        </div>
      </div>
    </div>
  </div>
</template>
