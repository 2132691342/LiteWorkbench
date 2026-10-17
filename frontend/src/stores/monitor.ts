import { defineStore } from 'pinia'
import { ref, shallowRef } from 'vue'
import { monitorApi, runApi } from '@/api'
import type { HealthRESP, ObserveRESP, OverviewRESP, PythonStatusVO, RunVO } from '@/types/api'

/** 监控状态：健康、仪表盘汇总、调用观测与 Python 运行时，供 Dashboard 与监控页共享 */
export const useMonitorStore = defineStore('monitor', () => {
  const health = shallowRef<HealthRESP | null>(null)
  const overview = shallowRef<OverviewRESP | null>(null)
  const python = shallowRef<PythonStatusVO | null>(null)
  const observe = shallowRef<ObserveRESP | null>(null)
  const loading = ref(false)
  const error = ref('')
  // 观测区自带 loading / error：它会定时自动刷新，不能污染健康面板的加载态
  const observeLoading = ref(false)
  const observeError = ref('')
  // 最近运行自带三态：概览失败不该让「最近运行」也跟着白屏
  const recentRuns = shallowRef<RunVO[]>([])
  const recentLoading = ref(false)
  const recentError = ref('')

  async function loadHealth(): Promise<void> {
    loading.value = true
    error.value = ''
    try {
      health.value = await monitorApi.health()
    } catch (err) {
      error.value = err instanceof Error ? err.message : ''
    } finally {
      loading.value = false
    }
  }

  async function loadOverview(): Promise<void> {
    loading.value = true
    error.value = ''
    try {
      overview.value = await monitorApi.overview()
    } catch (err) {
      error.value = err instanceof Error ? err.message : ''
    } finally {
      loading.value = false
    }
  }

  /** Python 探测失败不阻塞健康面板，错误由健康聚合兜底 */
  async function loadPython(): Promise<void> {
    try {
      python.value = await monitorApi.pythonStatus()
    } catch (err) {
      error.value = err instanceof Error ? err.message : ''
    }
  }

  async function reprobe(): Promise<void> {
    try {
      python.value = await monitorApi.pythonReprobe()
    } catch (err) {
      error.value = err instanceof Error ? err.message : ''
      throw err
    }
  }

  /** 仪表盘最近运行：固定取 5 条，不参与分页 */
  async function loadRecentRuns(): Promise<void> {
    recentLoading.value = true
    recentError.value = ''
    try {
      const result = await runApi.page({ page: 1, size: 5 })
      recentRuns.value = result.list
    } catch (err) {
      recentError.value = err instanceof Error ? err.message : ''
    } finally {
      recentLoading.value = false
    }
  }

  async function loadObserve(): Promise<void> {
    observeLoading.value = true
    observeError.value = ''
    try {
      observe.value = await monitorApi.observe()
    } catch (err) {
      observeError.value = err instanceof Error ? err.message : ''
    } finally {
      observeLoading.value = false
    }
  }

  async function resetObserve(): Promise<void> {
    try {
      await monitorApi.observeReset()
      observe.value = await monitorApi.observe()
    } catch (err) {
      observeError.value = err instanceof Error ? err.message : ''
      throw err
    }
  }

  return {
    health,
    overview,
    python,
    observe,
    loading,
    error,
    observeLoading,
    observeError,
    recentRuns,
    recentLoading,
    recentError,
    loadHealth,
    loadOverview,
    loadRecentRuns,
    loadObserve,
    resetObserve,
    loadPython,
    reprobe,
  }
})
