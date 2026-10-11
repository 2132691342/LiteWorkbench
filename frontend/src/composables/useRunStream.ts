import { onBeforeUnmount, ref } from 'vue'
import { openRunStream, type RunStreamHandlers } from '@/api'
import type { ChatStreamREQ } from '@/types/api'

/** SSE 订阅：开启流式对话并跟踪活动状态，组件卸载时自动断开本地消费 */
export function useRunStream() {
  const active = ref(false)
  let stop: ((reason?: string) => void) | null = null

  /** 发起流式对话，终态统一收敛为 active=false */
  function start(body: ChatStreamREQ, handlers: RunStreamHandlers): void {
    active.value = true
    stop = openRunStream(body, {
      onEvent: handlers.onEvent,
      onEnd: (aborted) => {
        active.value = false
        stop = null
        handlers.onEnd(aborted)
      },
      onError: (message) => {
        active.value = false
        stop = null
        handlers.onError(message)
      },
    })
  }

  /** 断开本地消费；服务端由 /run/abort 收尾 */
  function dispose(): void {
    stop?.()
    stop = null
    active.value = false
  }

  onBeforeUnmount(dispose)

  return { active, start, dispose }
}
