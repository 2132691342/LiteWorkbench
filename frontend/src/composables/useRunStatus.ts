/** 运行状态展示：色调与文案全站唯一实现，仪表盘与运行记录页共用 */
import { useI18n } from 'vue-i18n'

export type RunTone = 'primary' | 'success' | 'error' | 'muted' | 'warning'

/** 状态语义：成功绿 / 失败红 / 中断灰 / 进行中主靛 / 其余警示 */
export function useRunStatus() {
  const { t } = useI18n()

  function tone(status?: string): RunTone {
    switch (status) {
      case 'success':
        return 'success'
      case 'failed':
        return 'error'
      case 'aborted':
        return 'muted'
      case 'running':
      case 'queued':
        return 'primary'
      default:
        return 'warning'
    }
  }

  function label(status?: string): string {
    const map: Record<string, string> = {
      success: t('runs.statusSuccess'),
      failed: t('runs.statusFailed'),
      aborted: t('runs.statusAborted'),
      running: t('runs.statusRunning'),
      queued: t('runs.statusQueued'),
    }
    return map[status ?? ''] ?? (status ?? '-')
  }

  function isTerminal(status?: string): boolean {
    return status === 'success' || status === 'failed' || status === 'aborted'
  }

  return { tone, label, isTerminal }
}
