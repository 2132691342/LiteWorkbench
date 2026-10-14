<script setup lang="ts">
import * as echarts from 'echarts'
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { chartTooltip, token } from '@/utils/theme'

/** 环形图：主色与辅助色两序列起步，其余走中性色 */
const props = withDefaults(
  defineProps<{
    labels: string[]
    values: number[]
    height?: number
  }>(),
  { height: 260 },
)

const container = ref<HTMLDivElement | null>(null)
let chart: echarts.ECharts | null = null

function buildOption(): echarts.EChartsOption {
  /** 分片配色顺序：主色 → 辅助色 → 深字 → 禁用字 → 语义色 */
  const palette = [
    token('--lw-primary'),
    token('--lw-secondary'),
    token('--lw-ink'),
    token('--lw-ink-disabled'),
    token('--lw-success'),
    token('--lw-warning'),
  ]
  return {
    tooltip: { trigger: 'item', ...chartTooltip() },
    legend: {
      bottom: 0,
      icon: 'circle',
      itemWidth: 8,
      itemHeight: 8,
      textStyle: { color: token('--lw-ink-muted'), fontSize: 11 },
    },
    series: [
      {
        type: 'pie',
        radius: ['58%', '78%'],
        center: ['50%', '44%'],
        avoidLabelOverlap: true,
        itemStyle: {
          borderColor: token('--lw-surface-raised'),
          borderWidth: 2,
          borderRadius: 4,
        },
        label: { show: false },
        data: props.labels.map((label, index) => ({
          name: label,
          value: props.values[index] ?? 0,
          itemStyle: { color: palette[index % palette.length] },
        })),
      },
    ],
  }
}

function resize(): void {
  chart?.resize()
}

onMounted(() => {
  if (!container.value) return
  chart = echarts.init(container.value)
  chart.setOption(buildOption())
  window.addEventListener('resize', resize)
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', resize)
  chart?.dispose()
  chart = null
})

watch(
  () => [props.labels, props.values],
  () => chart?.setOption(buildOption()),
  { deep: true },
)
</script>

<template>
  <div ref="container" :style="{ height: `${height}px`, width: '100%' }" />
</template>
