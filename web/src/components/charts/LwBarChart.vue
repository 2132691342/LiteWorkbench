<script setup lang="ts">
import * as echarts from 'echarts'
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { chartAxis, chartTooltip, token } from '@/utils/theme'

/** 柱状图：主色圆柱圆角 6，辅助色可切第二序列色 */
const props = withDefaults(
  defineProps<{
    /** x 轴类目 */
    categories: string[]
    /** 序列数据 */
    values: number[]
    /** 序列名 */
    name?: string
    /** 画布高度 */
    height?: number
    /** 使用辅助色 */
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
        type: 'bar',
        data: props.values,
        barMaxWidth: 28,
        itemStyle: { color, borderRadius: [6, 6, 0, 0] },
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
  () => [props.categories, props.values],
  () => chart?.setOption(buildOption()),
  { deep: true },
)
</script>

<template>
  <div ref="container" :style="{ height: `${height}px`, width: '100%' }" />
</template>
