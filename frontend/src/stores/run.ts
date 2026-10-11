import { defineStore } from 'pinia'
import { ref, shallowRef } from 'vue'
import { runApi } from '@/api'
import type { MessageVO, RunEvent, RunVO } from '@/types/api'

/** 时间线上的工具步骤 */
export interface ToolStep {
  name: string
  phase: 'start' | 'end'
  output?: string
  isError?: boolean
}

/** 时间线消息：实时流与回放共用 */
export interface ChatMessage {
  role: 'user' | 'assistant'
  content: string
  thinking: string
  tools: ToolStep[]
  streaming?: boolean
}

/** 运行状态：当前会话的运行列表 + 唯一时间线 reducer，实时与回放同路径 */
export const useRunStore = defineStore('run', () => {
  /** 当前会话的历史运行 */
  const runs = shallowRef<RunVO[]>([])
  /** 渲染时间线：实时流与回放共用的唯一数据源 */
  const timeline = shallowRef<ChatMessage[]>([])
  const streaming = ref(false)
  const replaying = ref(false)
  const replayRunId = ref('')
  /** 当前进行中的运行 ID，供中断使用 */
  const currentRunId = ref('')

  /** 用已落库的消息重建时间线（会话切换 / 回到实时） */
  function loadTimeline(messages: MessageVO[]): void {
    timeline.value = messages.map((item) => ({
      role: item.roleCode === 'assistant' ? 'assistant' : 'user',
      content: item.content ?? '',
      thinking: '',
      tools: [],
    }))
    replaying.value = false
    replayRunId.value = ''
    currentRunId.value = ''
  }

  /** 发起流式对话：追加用户消息与流式助手占位 */
  function beginStream(prompt: string): void {
    timeline.value = [
      ...timeline.value,
      { role: 'user', content: prompt, thinking: '', tools: [] },
      { role: 'assistant', content: '', thinking: '', tools: [], streaming: true },
    ]
    streaming.value = true
    replaying.value = false
    replayRunId.value = ''
  }

  /** 事件进入最后一条助手消息（实时与回放共用） */
  function applyEvent(event: RunEvent): void {
    currentRunId.value = event.runId || currentRunId.value
    const target = lastAssistant()
    if (target) {
      applyToMessage(event, target)
      timeline.value = [...timeline.value]
    }
  }

  /** 事件 reducer：与后端事件类型一一对应 */
  function applyToMessage(event: RunEvent, target: ChatMessage): void {
    const payload = event.payload ?? {}
    switch (event.eventType) {
      case 'thinking.delta':
        target.thinking += String(payload.delta ?? event.textDelta ?? '')
        break
      case 'text.delta':
        target.content += String(payload.delta ?? event.textDelta ?? '')
        break
      case 'run.result':
        if (payload.text) target.content = String(payload.text)
        break
      case 'tool.call':
        if (payload.phase === 'start') {
          target.tools.push({ name: String(payload.toolName ?? ''), phase: 'start' })
        } else {
          const step = target.tools.find((item) => item.name === payload.toolName && item.phase === 'start')
          if (step) {
            step.phase = 'end'
          } else {
            target.tools.push({ name: String(payload.toolName ?? ''), phase: 'end' })
          }
        }
        break
      case 'tool.result': {
        const step = target.tools.filter((item) => item.name === payload.toolName).pop()
        if (step) {
          step.output = String(payload.output ?? '')
          step.isError = Boolean(payload.isError)
        }
        break
      }
      default:
        break
    }
  }

  /** 流式结束 */
  function finishStream(aborted: boolean, abortedSuffix: string): void {
    streaming.value = false
    const target = lastAssistant()
    if (target) {
      target.streaming = false
      if (aborted) target.content += abortedSuffix
    }
    timeline.value = [...timeline.value]
  }

  /** 服务端返回错误终态 */
  function failStream(message: string): void {
    streaming.value = false
    const target = lastAssistant()
    if (target) target.streaming = false
    timeline.value = [...timeline.value]
    errorDetail.value = message
  }

  const errorDetail = ref('')

  function lastAssistant(): ChatMessage | undefined {
    return timeline.value.filter((item) => item.role === 'assistant').pop()
  }

  /** 拉取当前会话的运行历史 */
  async function loadRuns(sessionId: string): Promise<void> {
    const page = await runApi.page({ sessionId, page: 1, size: 50 })
    runs.value = page.list
  }

  /** 按 runId 回放：拉运行详情与全量事件，走同一 reducer 重建时间线 */
  async function replay(runId: string): Promise<void> {
    const [detail, events] = await Promise.all([runApi.detail(runId), runApi.events(runId)])
    const rebuilt: ChatMessage[] = [
      { role: 'user', content: detail.prompt ?? '', thinking: '', tools: [] },
      { role: 'assistant', content: '', thinking: '', tools: [], streaming: false },
    ]
    timeline.value = rebuilt
    replaying.value = true
    replayRunId.value = runId
    for (const event of events) {
      applyToMessage(event, rebuilt[1])
    }
    timeline.value = [...rebuilt]
  }

  /** 回到实时视图 */
  function backToLive(): void {
    replaying.value = false
    replayRunId.value = ''
  }

  function reset(): void {
    runs.value = []
    timeline.value = []
    streaming.value = false
    replaying.value = false
    replayRunId.value = ''
    currentRunId.value = ''
    errorDetail.value = ''
  }

  return {
    runs,
    timeline,
    streaming,
    replaying,
    replayRunId,
    currentRunId,
    errorDetail,
    loadTimeline,
    beginStream,
    applyEvent,
    finishStream,
    failStream,
    loadRuns,
    replay,
    backToLive,
    reset,
  }
})
