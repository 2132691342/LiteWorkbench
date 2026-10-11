<script setup lang="ts">
import { Delete, Position, Promotion } from '@element-plus/icons-vue'
import { computed, nextTick, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessageBox } from 'element-plus'
import LwButton from '@/components/ui/LwButton.vue'
import LwEmpty from '@/components/ui/LwEmpty.vue'
import LwErrorBar from '@/components/ui/LwErrorBar.vue'
import LwSkeleton from '@/components/ui/LwSkeleton.vue'
import LwTag from '@/components/ui/LwTag.vue'
import { openRunStream, runApi, sessionApi } from '@/api'
import type { RunEvent, SessionVO } from '@/types/api'

/** 工作台：左侧会话列表 + 右侧流式对话与工具时间线 */
const { t } = useI18n()

/* ===== 会话列表 ===== */

const sessions = ref<SessionVO[]>([])
const sessionsLoading = ref(false)
const sessionsError = ref('')
const activeSessionId = ref('')

async function loadSessions(): Promise<void> {
  sessionsLoading.value = true
  sessionsError.value = ''
  try {
    const page = await sessionApi.page({ page: 1, size: 100 })
    sessions.value = page.list
  } catch (error) {
    sessionsError.value = (error as Error).message
  } finally {
    sessionsLoading.value = false
  }
}

async function createSession(): Promise<void> {
  const sessionId = await sessionApi.create({})
  sessions.value.unshift({
    sessionId,
    userId: '',
    title: t('console.newSession'),
    state: 'normal',
  })
  await selectSession(sessionId)
}

async function removeSession(sessionId: string): Promise<void> {
  await ElMessageBox.confirm(t('console.sessionDelete'), t('common.tip'), { type: 'warning' })
  await sessionApi.remove(sessionId)
  sessions.value = sessions.value.filter((item) => item.sessionId !== sessionId)
  if (activeSessionId.value === sessionId) {
    activeSessionId.value = ''
    messages.value = []
  }
}

const activeSession = computed(() =>
  sessions.value.find((item) => item.sessionId === activeSessionId.value),
)

/* ===== 消息与流式 ===== */

interface ToolStep {
  name: string
  phase: 'start' | 'end'
  output?: string
  isError?: boolean
}

interface ChatMessage {
  role: 'user' | 'assistant'
  content: string
  thinking: string
  tools: ToolStep[]
  streaming?: boolean
}

const messages = ref<ChatMessage[]>([])
const loadingMessages = ref(false)
const draft = ref('')
const streaming = ref(false)
const streamError = ref('')
let stopStream: ((reason?: string) => void) | null = null
const timelineEl = ref<HTMLElement | null>(null)

async function selectSession(sessionId: string): Promise<void> {
  if (streaming.value) return
  activeSessionId.value = sessionId
  loadingMessages.value = true
  try {
    const list = await sessionApi.messages(sessionId)
    messages.value = list.map((item) => ({
      role: item.roleCode === 'assistant' ? 'assistant' : 'user',
      content: item.content ?? '',
      thinking: '',
      tools: [],
    }))
  } finally {
    loadingMessages.value = false
  }
}

/** 事件进入当前助手消息的 reducer */
function applyEvent(event: RunEvent, target: ChatMessage): void {
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
        if (step) step.phase = 'end'
        else target.tools.push({ name: String(payload.toolName ?? ''), phase: 'end' })
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

async function send(): Promise<void> {
  const content = draft.value.trim()
  if (!content || streaming.value) return
  if (!activeSessionId.value) {
    await createSession()
    if (!activeSessionId.value) return
  }
  draft.value = ''
  streamError.value = ''
  const userMessage: ChatMessage = { role: 'user', content, thinking: '', tools: [] }
  const assistant: ChatMessage = { role: 'assistant', content: '', thinking: '', tools: [], streaming: true }
  messages.value.push(userMessage, assistant)
  streaming.value = true
  scrollToBottom()

  const sessionId = activeSessionId.value
  stopStream = openRunStream({ sessionId, content }, {
    onEvent: (event) => {
      applyEvent(event, assistant)
      scrollToBottom()
    },
    onEnd: (aborted) => {
      assistant.streaming = false
      streaming.value = false
      stopStream = null
      if (aborted) assistant.content += t('console.abortedSuffix')
      refreshSessionsMeta(sessionId)
    },
    onError: (message) => {
      assistant.streaming = false
      streaming.value = false
      stopStream = null
      streamError.value = message
    },
  })
}

/** 服务端确认中断后由 onEnd 收尾；fetch 端同步断开作为兜底 */
async function abort(): Promise<void> {
  const sessionId = activeSessionId.value
  if (!sessionId || !streaming.value) return
  stopStream?.()
  const runs = await runApi.page({ sessionId, page: 1, size: 1 })
  const runId = runs.list[0]?.runId
  if (runId) await runApi.abort(runId)
}

/** 结束后刷新左侧列表的活跃时间与标题序 */
async function refreshSessionsMeta(sessionId: string): Promise<void> {
  try {
    const detail = await sessionApi.detail(sessionId)
    const index = sessions.value.findIndex((item) => item.sessionId === sessionId)
    if (index >= 0) sessions.value.splice(index, 1, detail)
  } catch {
    // 元数据刷新失败不提示
  }
}

function scrollToBottom(): void {
  void nextTick(() => {
    timelineEl.value?.scrollTo({ top: timelineEl.value.scrollHeight })
  })
}

function toolLabel(name: string): string {
  const labels: Record<string, string> = {
    python_exec: 'Python',
    file_read: t('console.toolRead'),
    file_write: t('console.toolWrite'),
    http_get: 'HTTP',
  }
  return labels[name] ?? name
}

onMounted(loadSessions)
</script>

<template>
  <div class="flex h-full min-h-0">
    <aside
      class="w-64 border-r flex flex-col shrink-0 hidden md:flex"
      style="background: var(--lw-surface-raised); border-color: var(--lw-border)"
    >
      <div class="p-4 border-b" style="border-color: var(--lw-border)">
        <LwButton block :loading="sessionsLoading" @click="createSession">
          <template #icon><el-icon><Position /></el-icon></template>
          {{ t('console.newSession') }}
        </LwButton>
      </div>
      <div class="flex-1 overflow-auto p-3 flex flex-col gap-1">
        <LwSkeleton v-if="sessionsLoading" :rows="4" />
        <LwErrorBar v-else-if="sessionsError" :message="sessionsError" @retry="loadSessions" />
        <LwEmpty v-else-if="!sessions.length" :description="t('console.noSession')" />
        <div
          v-for="item in sessions"
          v-else
          :key="item.sessionId"
          class="group h-10 px-3 rounded-lg flex items-center justify-between cursor-pointer transition-all duration-200 hover:bg-black/5"
          :style="
            item.sessionId === activeSessionId
              ? 'color: var(--lw-primary); font-weight: 600; background: var(--lw-primary-tint)'
              : 'color: var(--lw-ink)'
          "
          @click="selectSession(item.sessionId)"
        >
          <span class="text-sm truncate">{{ item.title }}</span>
          <el-icon
            class="opacity-0 group-hover:opacity-60 transition-opacity"
            @click.stop="removeSession(item.sessionId)"
          >
            <Delete />
          </el-icon>
        </div>
      </div>
    </aside>

    <section class="flex-1 min-w-0 flex flex-col">
      <header class="p-6 pb-4 flex items-end justify-between shrink-0">
        <div>
          <h2 class="lw-title">{{ activeSession?.title ?? t('console.title') }}</h2>
          <p class="lw-subtitle mt-1">{{ t('console.desc') }}</p>
        </div>
        <LwTag v-if="activeSession?.modelName" tone="primary">{{ activeSession.modelName }}</LwTag>
      </header>

      <div ref="timelineEl" class="flex-1 min-h-0 overflow-auto px-6">
        <LwSkeleton v-if="loadingMessages" :rows="6" />
        <div v-else-if="!activeSessionId" class="h-full flex items-center justify-center">
          <LwEmpty :description="t('console.noSession')" />
        </div>
        <div v-else class="max-w-3xl mx-auto flex flex-col gap-4 pb-4">
          <template v-for="(message, index) in messages" :key="index">
            <div v-if="message.role === 'user'" class="flex justify-end">
              <div
                class="max-w-[80%] px-4 py-2.5 rounded-2xl rounded-br-md text-sm leading-relaxed whitespace-pre-wrap"
                style="background: var(--lw-primary); color: #fff"
              >
                {{ message.content }}
              </div>
            </div>
            <div v-else class="flex flex-col gap-2">
              <details v-if="message.thinking" class="text-xs">
                <summary class="cursor-pointer opacity-60">{{ t('console.thinking') }}</summary>
                <div class="mt-1 px-3 py-2 rounded-lg whitespace-pre-wrap" style="background: var(--lw-surface)">
                  {{ message.thinking }}
                </div>
              </details>
              <div
                v-for="(step, toolIndex) in message.tools"
                :key="toolIndex"
                class="px-3 py-2 rounded-lg text-xs border"
                :style="{
                  background: step.isError ? 'rgba(212,106,106,.08)' : 'var(--lw-surface)',
                  'border-color': step.isError ? 'var(--lw-error)' : 'var(--lw-border)',
                }"
              >
                <div class="flex items-center gap-2">
                  <LwTag :tone="step.isError ? 'error' : step.phase === 'end' ? 'success' : 'primary'">
                    {{ toolLabel(step.name) }}
                  </LwTag>
                  <span class="opacity-60">
                    {{ step.phase === 'start' ? t('console.toolCall') : t('console.toolResult') }}
                  </span>
                </div>
                <pre v-if="step.output" class="mt-1 whitespace-pre-wrap break-all max-h-40 overflow-auto">{{ step.output }}</pre>
              </div>
              <div
                v-if="message.content || message.streaming"
                class="max-w-[80%] px-4 py-2.5 rounded-2xl rounded-bl-md text-sm leading-relaxed whitespace-pre-wrap border"
                :style="{ background: 'var(--lw-surface-raised)', 'border-color': 'var(--lw-border)' }"
              >
                {{ message.content }}<span v-if="message.streaming" class="lw-cursor">▍</span>
              </div>
            </div>
          </template>
        </div>
      </div>

      <footer class="p-6 pt-4 shrink-0 flex flex-col gap-2 max-w-3xl mx-auto w-full">
        <LwErrorBar v-if="streamError" :message="streamError" />
        <div
          class="flex items-end gap-3 p-3 rounded-xl border transition-all duration-200 focus-within:shadow-md"
          style="background: var(--lw-surface-raised); border-color: var(--lw-border)"
        >
          <textarea
            v-model="draft"
            rows="2"
            class="flex-1 resize-none bg-transparent outline-none text-sm px-2 py-1"
            :placeholder="t('console.inputPlaceholder')"
            :disabled="streaming"
            @keydown.enter.exact.prevent="send"
          />
          <LwButton v-if="!streaming" :disabled="!draft.trim()" @click="send">
            <template #icon><el-icon><Promotion /></el-icon></template>
            {{ t('console.send') }}
          </LwButton>
          <LwButton v-else variant="secondary" loading @click="abort">
            {{ t('console.abort') }}
          </LwButton>
        </div>
      </footer>
    </section>
  </div>
</template>

<style scoped>
.lw-cursor {
  animation: lw-blink 1s step-start infinite;
  color: var(--lw-primary);
}
@keyframes lw-blink {
  50% {
    opacity: 0;
  }
}
</style>
