<script setup lang="ts">
import * as echarts from 'echarts'
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'

/** 环形图：主色与辅助色两序列起步，其余走中性色 */
const props = withDefaults(
  defineProps<{
    /** 分片名称 */
    labels: string[]
    /** 分片数值 */
    values: number[]
    /** 画布高度 */
    height?: number
  }>(),
  { height: 260 },
)

const PALETTE = ['#2563eb', '#9333ea', '#334155', '#94a3b8', '#5abf8a', '#d6a85c']

const container = ref<HTMLDivElement | null>(null)
let chart: echarts.ECharts | null = null

function buildOption(): echarts.EChartsOption {
  return {
    tooltip: {
      trigger: 'item',
      backgroundColor: '#ffffff',
      borderColor: '#e2e8f0',
      borderWidth: 1,
      borderRadius: 8,
      textStyle: { color: '#334155', fontSize: 12 },
    },
    legend: {
      bottom: 0,
      icon: 'circle',
      itemWidth: 8,
      itemHeight: 8,
      textStyle: { color: '#64748b', fontSize: 11 },
    },
    series: [
      {
        type: 'pie',
        radius: ['58%', '78%'],
        center: ['50%', '44%'],
        avoidLabelOverlap: true,
        itemStyle: { borderColor: '#ffffff', borderWidth: 2, borderRadius: 4 },
        label: { show: false },
        data: props.labels.map((label, index) => ({
          name: label,
          value: props.values[index] ?? 0,
          itemStyle: { color: PALETTE[index % PALETTE.length] },
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
