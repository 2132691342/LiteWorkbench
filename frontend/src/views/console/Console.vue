<script setup lang="ts">
import { Delete, Edit, Position, Promotion, RefreshLeft, VideoPlay } from '@element-plus/icons-vue'
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import ConsoleQuickStart from '@/components/console/ConsoleQuickStart.vue'
import PlanChecklist from '@/components/console/PlanChecklist.vue'
import LwButton from '@/components/ui/LwButton.vue'
import LwErrorBar from '@/components/ui/LwErrorBar.vue'
import LwMarkdown from '@/components/ui/LwMarkdown.vue'
import LwSkeleton from '@/components/ui/LwSkeleton.vue'
import LwTag from '@/components/ui/LwTag.vue'
import { runApi } from '@/api'
import { useRunStream } from '@/composables/useRunStream'
import { useRunStore, type ChatMessage } from '@/stores/run'
import { useSessionStore } from '@/stores/session'
import type { RunVO } from '@/types/api'
import { formatTime } from '@/utils/const'

/** 工作台：左侧会话与运行历史，右侧流式时间线（实时与回放同路径） */
const { t } = useI18n()
const router = useRouter()
const sessionStore = useSessionStore()
const runStore = useRunStore()
const { start: startStream, dispose: disposeStream } = useRunStream()

const draft = ref('')
const streamError = ref('')
const editing = ref(false)
const saving = ref(false)
const editForm = ref({ sessionId: '', title: '', modelName: '' })
const timelineEl = ref<HTMLElement | null>(null)

const messages = computed<ChatMessage[]>(() => runStore.timeline)
const activeSession = computed(() => sessionStore.activeSession)

/* ===== 会话 ===== */

async function createSession(): Promise<void> {
  await sessionStore.create(t('console.newSession'))
  runStore.loadTimeline([])
  await runStore.loadRuns(sessionStore.activeSessionId)
}

async function removeSession(sessionId: string): Promise<void> {
  await ElMessageBox.confirm(t('console.sessionDelete'), t('common.tip'), { type: 'warning' })
  await sessionStore.remove(sessionId)
  if (!sessionStore.activeSessionId) {
    runStore.reset()
  }
}

async function selectSession(sessionId: string): Promise<void> {
  if (runStore.streaming) return
  await sessionStore.select(sessionId)
  runStore.loadTimeline(sessionStore.messages)
  await runStore.loadRuns(sessionId)
}

function openEdit(): void {
  const session = activeSession.value
  if (!session) return
  editForm.value = {
    sessionId: session.sessionId,
    title: session.title ?? '',
    modelName: session.modelName ?? '',
  }
  editing.value = true
}

async function submitEdit(): Promise<void> {
  saving.value = true
  try {
    await sessionStore.update({ ...editForm.value })
    editing.value = false
  } catch {
    // 拦截器已提示，保留弹窗供修正
  } finally {
    saving.value = false
  }
}

/** 回车发送：输入法组词中的回车只确认候选，不发送 */
function onEnter(event: KeyboardEvent): void {
  if (event.isComposing) {
    return
  }
  event.preventDefault()
  void send()
}

/* ===== 流式对话 ===== */

async function send(): Promise<void> {
  const content = draft.value.trim()
  if (!content || runStore.streaming) return
  if (!sessionStore.activeSessionId) {
    await createSession()
    if (!sessionStore.activeSessionId) return
  }
  draft.value = ''
  streamError.value = ''
  runStore.backToLive()
  runStore.beginStream(content)
  scrollToBottom()

  const sessionId = sessionStore.activeSessionId
  startStream(
    { sessionId, content },
    {
      onEvent: (event) => {
        runStore.applyEvent(event)
        scrollToBottom()
      },
      onEnd: async (aborted) => {
        runStore.finishStream(aborted, t('console.abortedSuffix'))
        await sessionStore.refreshDetail(sessionId)
        await runStore.loadRuns(sessionId)
      },
      onError: (message) => {
        runStore.failStream(message)
        streamError.value = message
      },
    },
  )
}

/** 服务端确认中断后由 onEnd 收尾；fetch 端同步断开作为兜底 */
async function abort(): Promise<void> {
  if (!runStore.streaming) return
  const runId = runStore.currentRunId
  disposeStream()
  if (runId) await runApi.abort(runId)
}

/* ===== 回放 ===== */

async function replay(runId: string): Promise<void> {
  if (runStore.streaming || !runId) return
  await runStore.replay(runId)
  scrollToBottom()
}

function openInRuns(runId: string): void {
  void router.push({ path: '/runs', query: { runId } })
}

async function backToLive(): Promise<void> {
  runStore.backToLive()
  const sessionId = sessionStore.activeSessionId
  if (sessionId) {
    await sessionStore.select(sessionId)
    runStore.loadTimeline(sessionStore.messages)
  }
  scrollToBottom()
}

/* ===== 展示 ===== */

function scrollToBottom(): void {
  void nextTick(() => {
    timelineEl.value?.scrollTo({ top: timelineEl.value.scrollHeight })
  })
}

function toolLabel(name: string): string {
  const labels: Record<string, string> = {
    python_exec: t('console.toolPython'),
    file_read: t('console.toolRead'),
    file_write: t('console.toolWrite'),
    http_get: t('console.toolHttp'),
    kb_search: t('console.toolKb'),
    task_plan: t('console.toolPlan'),
    memory_write: t('console.toolMemoryWrite'),
    memory_search: t('console.toolMemorySearch'),
    task_delegate: t('console.toolDelegate'),
  }
  return labels[name] ?? name
}

function runStatusTone(run: RunVO): 'primary' | 'success' | 'warning' | 'error' {
  if (run.status === 'success') return 'success'
  if (run.status === 'failed') return 'error'
  if (run.status === 'aborted') return 'warning'
  return 'primary'
}

function runStatusText(run: RunVO): string {
  return t(`console.runStatus_${run.status}`)
}

/** 运行元信息：耗时必有，Token 用量有才追加 */
function runMeta(run: RunVO): string {
  const durationMs = run.durationMs ?? 0
  const duration =
    durationMs >= 1000 ? `${(durationMs / 1000).toFixed(1)}s` : `${durationMs}ms`
  const tokens = (run.inputTokens ?? 0) + (run.outputTokens ?? 0)
  if (tokens <= 0) return duration
  return t('console.runMeta', { duration, tokens: tokens.toLocaleString() })
}

watch(() => runStore.timeline.length, scrollToBottom)

onMounted(async () => {
  await sessionStore.loadList()
  const first = sessionStore.list[0]
  if (first) {
    await selectSession(first.sessionId)
  }
})
</script>

<template>
  <div class="flex h-full min-h-0">
    <aside
      class="w-64 border-r flex-col shrink-0 hidden md:flex"
      style="background: var(--lw-surface-raised); border-color: var(--lw-border)"
    >
      <div class="p-4 border-b" style="border-color: var(--lw-border)">
        <LwButton block :loading="sessionStore.loading" @click="createSession">
          <template #icon><el-icon><Position /></el-icon></template>
          {{ t('console.newSession') }}
        </LwButton>
      </div>

      <div class="flex-1 overflow-auto p-3 flex flex-col gap-1 min-h-0">
        <LwSkeleton v-if="sessionStore.loading" :rows="4" />
        <LwErrorBar
          v-else-if="sessionStore.error"
          :message="sessionStore.error"
          @retry="sessionStore.loadList"
        />
        <LwEmpty v-else-if="!sessionStore.list.length" :description="t('console.noSession')" />
        <div
          v-for="item in sessionStore.list"
          v-else
          :key="item.sessionId"
          class="group h-10 px-3 rounded-lg flex items-center justify-between cursor-pointer transition-all duration-200 hover:bg-black/5"
          :style="
            item.sessionId === sessionStore.activeSessionId
              ? 'color: var(--lw-primary-ink); font-weight: 600; background: var(--lw-primary-tint)'
              : 'color: var(--lw-ink)'
          "
          role="button"
          tabindex="0"
          :aria-selected="item.sessionId === sessionStore.activeSessionId"
          @click="selectSession(item.sessionId)"
          @keydown.enter="selectSession(item.sessionId)"
          @keydown.space.prevent="selectSession(item.sessionId)"
        >
          <span class="text-sm truncate">{{ item.title }}</span>
          <el-tooltip :open-delay="300" :content="t('console.sessionDelete')" placement="top">
            <button
              type="button"
              class="lw-icon-btn p-0.5 opacity-0 group-hover:opacity-60 focus-visible:opacity-60 transition-all duration-200 hover:text-lw-errorInk active:scale-90"
              :aria-label="t('console.sessionDelete')"
              @click.stop="removeSession(item.sessionId)"
            >
              <el-icon :size="14"><Delete /></el-icon>
            </button>
          </el-tooltip>
        </div>
      </div>

      <div class="h-56 border-t flex flex-col min-h-0" style="border-color: var(--lw-border)">
        <div class="px-4 py-2 shrink-0">
          <span class="lw-label">{{ t('console.runHistory') }}</span>
        </div>
        <div class="flex-1 overflow-auto px-3 pb-3 flex flex-col gap-1">
          <LwEmpty v-if="!runStore.runs.length" :description="t('console.noRuns')" />
          <!-- v-for 不得与 v-else 同元素：v-if 优先级更高会让分支判定失效，条件与循环必须分层 -->
          <template v-else>
            <div
              v-for="run in runStore.runs"
              :key="run.runId"
              class="px-2 py-1.5 rounded-lg border cursor-pointer transition-all duration-200 hover:lw-shadow-sm"
              :style="{
                'border-color': run.runId === runStore.replayRunId ? 'var(--lw-primary)' : 'var(--lw-border)',
                background: run.runId === runStore.replayRunId ? 'var(--lw-primary-tint)' : 'transparent',
              }"
              @click="replay(run.runId)"
            >
              <div class="flex items-center justify-between gap-2">
                <span class="text-xs truncate" style="color: var(--lw-ink-muted)">{{ formatTime(run.createdAt) }}</span>
                <LwTag :tone="runStatusTone(run)">{{ runStatusText(run) }}</LwTag>
              </div>
              <div class="text-xs truncate mt-0.5">{{ run.prompt }}</div>
              <div class="flex items-center justify-between gap-2 mt-0.5">
                <span v-if="runMeta(run)" class="text-xs opacity-50 font-mono">{{ runMeta(run) }}</span>
                <LwButton variant="text" size="sm" class="shrink-0" @click.stop="openInRuns(run.runId)">
                  {{ t('console.openInRuns') }}
                </LwButton>
              </div>
            </div>
          </template>
        </div>
      </div>
    </aside>

    <section class="flex-1 min-w-0 flex flex-col">
      <header class="p-6 pb-4 flex items-end justify-between shrink-0">
        <div>
          <h2 class="lw-title">{{ activeSession?.title ?? t('console.title') }}</h2>
          <p class="lw-subtitle mt-1">{{ t('console.desc') }}</p>
        </div>
        <div class="flex items-center gap-2">
          <LwTag v-if="activeSession?.modelName" tone="primary">{{ activeSession.modelName }}</LwTag>
          <LwButton v-if="activeSession" variant="ghost" size="sm" @click="openEdit">
            <template #icon><el-icon><Edit /></el-icon></template>
            {{ t('console.editSession') }}
          </LwButton>
        </div>
      </header>

      <div
        v-if="runStore.replaying"
        class="mx-6 mb-2 px-3 py-2 rounded-lg flex items-center justify-between text-xs shrink-0"
        style="background: var(--lw-secondary-tint); color: var(--lw-secondary)"
      >
        <span class="flex items-center gap-2">
          <el-icon><VideoPlay /></el-icon>
          {{ t('console.replaying', { runId: runStore.replayRunId }) }}
        </span>
        <LwButton variant="ghost" size="sm" @click="backToLive">
          <template #icon><el-icon><RefreshLeft /></el-icon></template>
          {{ t('console.backLive') }}
        </LwButton>
      </div>

      <div ref="timelineEl" class="flex-1 min-h-0 overflow-auto px-6">
        <LwSkeleton v-if="sessionStore.loadingMessages" :rows="6" />
        <!-- 两种空态（未选会话 / 会话内无消息）都给创作入口，不留白也不重复实现 -->
        <div v-else-if="!sessionStore.activeSessionId || !messages.length" class="h-full flex items-center justify-center">
          <ConsoleQuickStart />
        </div>
        <div v-else class="max-w-3xl mx-auto flex flex-col gap-4 pb-4">
          <template v-for="(message, index) in messages" :key="index">
            <div v-if="message.role === 'user'" class="flex justify-end">
              <div
                class="max-w-[80%] px-4 py-2.5 rounded-card rounded-br-[4px] text-sm leading-relaxed whitespace-pre-wrap"
                style="background: var(--lw-primary); color: var(--lw-on-primary)"
              >
                {{ message.content }}
              </div>
            </div>
            <div v-else class="flex flex-col gap-2">
              <details v-if="message.thinking" class="tl-thinking">
                <summary>{{ t('console.thinking') }}</summary>
                <pre>{{ message.thinking }}</pre>
              </details>
              <PlanChecklist v-if="message.plan.length" :steps="message.plan" />
              <div
                v-for="(step, toolIndex) in message.tools"
                :key="toolIndex"
                class="px-3 py-2 rounded-lg text-xs border"
                :style="{
                  background: step.isError ? 'var(--lw-error-tint)' : 'var(--lw-surface)',
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
              <div v-if="message.content || message.streaming" class="text-sm">
                <LwMarkdown :content="message.content" /><span v-if="message.streaming" class="lw-cursor">▍</span>
              </div>
            </div>
          </template>
        </div>
      </div>

      <footer class="p-6 pt-4 shrink-0 flex flex-col gap-2 max-w-3xl mx-auto w-full">
        <LwErrorBar v-if="streamError" :message="streamError" />
        <div class="lw-field lw-field--auto py-2" style="background: var(--lw-surface-raised)">
          <textarea
            v-model="draft"
            rows="2"
            class="flex-1 resize-none bg-transparent outline-none text-sm"
            style="padding: 2px 0"
            :placeholder="t('console.inputPlaceholder')"
            :disabled="runStore.streaming || runStore.replaying"
            @keydown.enter.exact="onEnter"
          />
          <LwButton
            v-if="!runStore.streaming"
            :disabled="!draft.trim() || runStore.replaying"
            @click="send"
          >
            <template #icon><el-icon><Promotion /></el-icon></template>
            {{ t('console.send') }}
          </LwButton>
          <LwButton v-else variant="secondary" loading @click="abort">
            {{ t('console.abort') }}
          </LwButton>
        </div>
      </footer>
    </section>

    <el-dialog v-model="editing" :title="t('console.editSession')" width="420px">
      <el-form label-position="top">
        <el-form-item :label="t('console.sessionTitle')">
          <el-input v-model="editForm.title" maxlength="128" />
        </el-form-item>
        <el-form-item :label="t('console.modelName')">
          <el-input v-model="editForm.modelName" :placeholder="t('console.modelPlaceholder')" />
        </el-form-item>
      </el-form>
      <template #footer>
        <LwButton variant="ghost" @click="editing = false">{{ t('common.cancel') }}</LwButton>
        <LwButton :loading="saving" @click="submitEdit">{{ t('common.save') }}</LwButton>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.lw-cursor {
  animation: lw-blink 1s step-start infinite;
  color: var(--lw-primary-ink);
}
@keyframes lw-blink {
  50% {
    opacity: 0;
  }
}
</style>
