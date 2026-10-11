<script setup lang="ts">
import * as echarts from 'echarts'
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'

/** 面积图：主序列走品牌主色，渐变填充与网格按 DESIGN.md 约定 */
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
    /** 使用辅助色第二序列 */
    secondary?: boolean
  }>(),
  { name: 'value', height: 300, secondary: false },
)

const container = ref<HTMLDivElement | null>(null)
let chart: echarts.ECharts | null = null

/** 按设计令牌组装配置 */
function buildOption(): echarts.EChartsOption {
  return {
    grid: { left: 40, right: 16, top: 16, bottom: 28 },
    tooltip: {
      trigger: 'axis',
      backgroundColor: '#ffffff',
      borderColor: '#e2e8f0',
      borderWidth: 1,
      borderRadius: 8,
      textStyle: { color: '#334155', fontSize: 12 },
    },
    xAxis: {
      type: 'category',
      boundaryGap: false,
      data: props.categories,
      axisLine: { lineStyle: { color: '#e2e8f0' } },
      axisTick: { show: false },
      axisLabel: { color: '#64748b', fontSize: 10 },
    },
    yAxis: {
      type: 'value',
      splitLine: { lineStyle: { color: '#e2e8f0', type: 'dashed', opacity: 0.5 } },
      axisLabel: { color: '#64748b', fontSize: 10 },
    },
    series: [
      {
        name: props.name,
        type: 'line',
        smooth: true,
        symbol: 'none',
        data: props.values,
        lineStyle: { color: props.secondary ? '#9333ea' : '#2563eb', width: 3 },
        areaStyle: {
          color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
            props.secondary
              ? { offset: 0.05, color: 'rgba(147, 51, 234, 0.2)' }
              : { offset: 0.05, color: 'rgba(37, 99, 235, 0.2)' },
            props.secondary
              ? { offset: 0.95, color: 'rgba(147, 51, 234, 0)' }
              : { offset: 0.95, color: 'rgba(37, 99, 235, 0)' },
          ]),
        },
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
