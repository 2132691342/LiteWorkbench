import { defineStore } from 'pinia'
import { ref, shallowRef } from 'vue'
import { monitorApi } from '@/api'
import type { HealthRESP, OverviewRESP, PythonStatusVO } from '@/types/api'

/** 监控状态：健康、仪表盘汇总与 Python 运行时，供 Dashboard 与监控页共享 */
export const useMonitorStore = defineStore('monitor', () => {
  const health = shallowRef<HealthRESP | null>(null)
  const overview = shallowRef<OverviewRESP | null>(null)
  const python = shallowRef<PythonStatusVO | null>(null)
  const loading = ref(false)
  const error = ref('')

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

  return { health, overview, python, loading, error, loadHealth, loadOverview, loadPython, reprobe }
})
