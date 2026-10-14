import { defineStore } from 'pinia'
import { ref, shallowRef } from 'vue'
import { runApi } from '@/api'
import type { MessageVO, PlanStep, RunEvent, RunVO } from '@/types/api'

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
  /** 规划器写下的计划，新帧整体覆盖旧计划 */
  plan: PlanStep[]
  streaming?: boolean
}

/** 运行状态：当前会话的运行列表 + 唯一时间线 reducer，实时与回放同路径 */
export const useRunStore = defineStore('run', () => {
  const runs = shallowRef<RunVO[]>([])
  const timeline = shallowRef<ChatMessage[]>([])
  const streaming = ref(false)
  const replaying = ref(false)
  const replayRunId = ref('')
  const currentRunId = ref('')

  function loadTimeline(messages: MessageVO[]): void {
    timeline.value = messages.map((item) => ({
      role: item.roleCode === 'assistant' ? 'assistant' : 'user',
      content: item.content ?? '',
      thinking: '',
      tools: [],
      plan: [],
    }))
    replaying.value = false
    replayRunId.value = ''
    currentRunId.value = ''
  }

  function beginStream(prompt: string): void {
    timeline.value = [
      ...timeline.value,
      { role: 'user', content: prompt, thinking: '', tools: [], plan: [] },
      { role: 'assistant', content: '', thinking: '', tools: [], plan: [], streaming: true },
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
      case 'plan.updated':
        // 计划是覆盖式快照：新帧整体替换，不做增量拼接
        target.plan = Array.isArray(payload.steps) ? (payload.steps as PlanStep[]) : []
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

  function finishStream(aborted: boolean, abortedSuffix: string): void {
    streaming.value = false
    const target = lastAssistant()
    if (target) {
      target.streaming = false
      if (aborted) target.content += abortedSuffix
    }
    timeline.value = [...timeline.value]
  }

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

  /** 拉取当前会话的运行历史，失败记入 errorDetail 供页面内联提示 */
  async function loadRuns(sessionId: string): Promise<void> {
    try {
      const page = await runApi.page({ sessionId, page: 1, size: 50 })
      runs.value = page.list
    } catch (err) {
      errorDetail.value = err instanceof Error ? err.message : ''
      throw err
    }
  }

  /** 按 runId 回放：拉运行详情与全量事件，走同一 reducer 重建时间线 */
  async function replay(runId: string): Promise<void> {
    const [detail, events] = await Promise.all([runApi.detail(runId), runApi.events(runId)])
    const rebuilt: ChatMessage[] = [
      { role: 'user', content: detail.prompt ?? '', thinking: '', tools: [], plan: [] },
      { role: 'assistant', content: '', thinking: '', tools: [], plan: [], streaming: false },
    ]
    timeline.value = rebuilt
    replaying.value = true
    replayRunId.value = runId
    for (const event of events) {
      applyToMessage(event, rebuilt[1])
    }
    timeline.value = [...rebuilt]
  }

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
