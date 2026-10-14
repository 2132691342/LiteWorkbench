<script setup lang="ts">
import * as echarts from 'echarts'
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { chartAxis, chartTooltip, token } from '@/utils/theme'

/** 面积图：主序列走品牌主色，渐变填充与网格按 DESIGN.md 约定 */
const props = withDefaults(
  defineProps<{
    categories: string[]
    values: number[]
    name?: string
    height?: number
    secondary?: boolean
  }>(),
  { name: 'value', height: 300, secondary: false },
)

const container = ref<HTMLDivElement | null>(null)
let chart: echarts.ECharts | null = null

function buildOption(): echarts.EChartsOption {
  const color = props.secondary ? token('--lw-secondary') : token('--lw-primary')
  return {
    grid: { left: 40, right: 16, top: 16, bottom: 28 },
    tooltip: { trigger: 'axis', ...chartTooltip() },
    xAxis: {
      type: 'category',
      boundaryGap: false,
      data: props.categories,
      ...chartAxis(),
    },
    yAxis: {
      type: 'value',
      splitLine: {
        lineStyle: { color: token('--lw-border'), type: 'dashed', opacity: 0.5 },
      },
      ...chartAxis(),
    },
    series: [
      {
        name: props.name,
        type: 'line',
        smooth: true,
        symbol: 'none',
        data: props.values,
        lineStyle: { color, width: 3 },
        areaStyle: {
          color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
            { offset: 0.05, color: withAlpha(color, 0.2) },
            { offset: 0.95, color: withAlpha(color, 0) },
          ]),
        },
      },
    ],
  }
}

function withAlpha(color: string, alpha: number): string {
  const hex = color.replace('#', '')
  const r = parseInt(hex.slice(0, 2), 16)
  const g = parseInt(hex.slice(2, 4), 16)
  const b = parseInt(hex.slice(4, 6), 16)
  return `rgba(${r}, ${g}, ${b}, ${alpha})`
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
  () => [props.categories, props.values],
  () => chart?.setOption(buildOption()),
  { deep: true },
)
</script>

<template>
  <div ref="container" :style="{ height: `${height}px`, width: '100%' }" />
</template>
