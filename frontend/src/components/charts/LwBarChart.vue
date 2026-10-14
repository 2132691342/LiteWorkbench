<script setup lang="ts">
import * as echarts from 'echarts'
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { chartAxis, chartTooltip, token } from '@/utils/theme'

/** 柱状图：主色圆柱圆角 6，辅助色可切第二序列色；点击柱子抛出桶下标，用于时间范围钻取 */
const props = withDefaults(
  defineProps<{
    categories: string[]
    values: number[]
    name?: string
    height?: number
    secondary?: boolean
    clickable?: boolean
  }>(),
  { name: 'value', height: 300, secondary: false, clickable: false },
)

const emit = defineEmits<{ (e: 'bucket-click', index: number): void }>()

const container = ref<HTMLDivElement | null>(null)
let chart: echarts.ECharts | null = null

function bindClick(): void {
  chart?.off('click')
  if (!props.clickable) return
  chart?.on('click', (params) => {
    if (typeof params.dataIndex === 'number') {
      emit('bucket-click', params.dataIndex)
    }
  })
}

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
  bindClick()
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

watch(() => props.clickable, bindClick)
</script>

<template>
  <div ref="container" :style="{ height: `${height}px`, width: '100%' }" />
</template>
