<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import { Delete, Edit, Position, RefreshLeft, VideoPlay } from '@element-plus/icons-vue'
import ConsoleQuickStart from '@/components/console/ConsoleQuickStart.vue'
import MessageComposer from '@/components/console/MessageComposer.vue'
import PlanChecklist from '@/components/console/PlanChecklist.vue'
import ThinkingBlock from '@/components/console/ThinkingBlock.vue'
import ToolStepCard from '@/components/console/ToolStepCard.vue'
import LwButton from '@/components/ui/LwButton.vue'
import LwEmpty from '@/components/ui/LwEmpty.vue'
import LwErrorBar from '@/components/ui/LwErrorBar.vue'
import LwMarkdown from '@/components/ui/LwMarkdown.vue'
import LwSkeleton from '@/components/ui/LwSkeleton.vue'
import LwTag from '@/components/ui/LwTag.vue'
import { providerApi, runApi, workspaceApi } from '@/api'
import { useRunStream } from '@/composables/useRunStream'
import { useRunStore, type ChatMessage } from '@/stores/run'
import { useSessionStore } from '@/stores/session'
import { useUserStore } from '@/stores/user'
import type { ProviderVO, RunRefREQ, RunVO, WorkspaceVO } from '@/types/api'
import { formatClock, formatTime } from '@/utils/const'

/** 工作台：左侧会话与运行历史，右侧流式时间线（实时与回放同路径） */
const { t } = useI18n()
const router = useRouter()
const userStore = useUserStore()
const sessionStore = useSessionStore()
const runStore = useRunStore()
const { start: startStream, dispose: disposeStream } = useRunStream()

const draft = ref('')
const streamError = ref('')
const editing = ref(false)
const saving = ref(false)
const editForm = ref({ sessionId: '', title: '', modelName: '' })
const scrollEl = ref<HTMLElement | null>(null)
const composerRef = ref<InstanceType<typeof MessageComposer> | null>(null)
/** 距底部多少像素以内算「跟着看」，超过就不再抢用户的滚动位置 */
const STICK_THRESHOLD = 200
const stick = ref(true)

/** 模型选择器与工作区切换的数据源：供应方取启用的，工作区取当前用户的 */
const providers = ref<ProviderVO[]>([])
const workspaces = ref<WorkspaceVO[]>([])

/** 模型即点即换：持久化到会话后立即生效，下一条消息按新模型走 */
async function updateModel(model: { providerCode: string; modelName: string }): Promise<void> {
  const sessionId = sessionStore.activeSessionId
  if (!sessionId) return
  await sessionStore.update({
    sessionId,
    providerCode: model.providerCode,
    modelName: model.modelName,
  })
}

/** 工作区即点即换：落会话绑定，上传与 @ 引用随后按新工作区取桶 */
async function updateWorkspace(workspaceId: string): Promise<void> {
  const sessionId = sessionStore.activeSessionId
  if (!sessionId) return
  await sessionStore.update({ sessionId, workspaceId })
}

/** 输入框就地新建工作区：创建后立即切到新桶，用户不必离开当前对话 */
async function onWorkspaceCreated(workspaceId: string): Promise<void> {
  const list = await workspaceApi.list()
  workspaces.value = list
  await updateWorkspace(workspaceId)
}

onMounted(async () => {
  const [providerList, workspaceList] = await Promise.all([
    providerApi.list().catch(() => [] as ProviderVO[]),
    workspaceApi.list().catch(() => [] as WorkspaceVO[]),
  ])
  providers.value = providerList.filter((item) => item.enabled)
  workspaces.value = workspaceList
})

const messages = computed<ChatMessage[]>(() => runStore.timeline)
const activeSession = computed(() => sessionStore.activeSession)
const lastUserMessage = computed(
  () => [...messages.value].reverse().find((item) => item.role === 'user')?.content ?? '',
)

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
  scrollToBottom(true)
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

/* ===== 流式对话 ===== */

async function send(payload: { content: string; skillCodes: string[]; refs: RunRefREQ[] }): Promise<void> {
  if (!payload.content || runStore.streaming) return
  if (!sessionStore.activeSessionId) {
    await createSession()
    if (!sessionStore.activeSessionId) return
  }
  streamError.value = ''
  runStore.backToLive()
  runStore.beginStream(payload.content)
  scrollToBottom(true)

  const sessionId = sessionStore.activeSessionId
  // 首帧到达才清空草稿：连接失败时用户的输入还在，不会因为一次网络抖动丢掉整段话
  let cleared = false
  startStream(
    { sessionId, content: payload.content, skillCodes: payload.skillCodes, refs: payload.refs },
    {
      onEvent: (event) => {
        runStore.applyEvent(event)
        if (!cleared) {
          cleared = true
          composerRef.value?.clear()
        }
        if (stick.value) scrollToBottom()
      },
      onEnd: async (aborted) => {
        runStore.finishStream(aborted, t('console.abortedSuffix'))
        await sessionStore.refreshDetail(sessionId)
        await runStore.loadRuns(sessionId)
        if (stick.value) scrollToBottom()
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
  scrollToBottom(true)
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
  scrollToBottom(true)
}

/* ===== 展示 ===== */

function scrollToBottom(force = false): void {
  void nextTick(() => {
    if (!force && !stick.value) return
    const el = scrollEl.value
    if (!el) return
    el.scrollTo({ top: el.scrollHeight })
    stick.value = true
  })
}

function onScroll(): void {
  const el = scrollEl.value
  if (!el) return
  const distance = el.scrollHeight - el.scrollTop - el.clientHeight
  stick.value = distance <= STICK_THRESHOLD
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
  const duration = durationMs >= 1000 ? `${(durationMs / 1000).toFixed(1)}s` : `${durationMs}ms`
  const tokens = (run.inputTokens ?? 0) + (run.outputTokens ?? 0)
  if (tokens <= 0) return duration
  return t('console.runMeta', { duration, tokens: tokens.toLocaleString() })
}

/** 助手消息页脚用量：只有真的调用过模型才出现，不补零不占位 */
function usageText(message: ChatMessage): string {
  const usage = message.usage
  if (!usage || usage.calls <= 0) return ''
  const total = (usage.input + usage.output).toLocaleString()
  return `${usage.calls} ${t('console.modelCalls')} · ${total} ${t('console.tokens')}`
}

watch(() => runStore.timeline.length, () => scrollToBottom())

onMounted(async () => {
  await sessionStore.loadList()
  const first = sessionStore.list[0]
  if (first) {
    await selectSession(first.sessionId)
  }
  composerRef.value?.focus()
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

      <!-- 会话列表按内容高度（上限 46% 后内部滚动），运行历史吸收剩余空间：
           反过来写会让中间的会话区被 flex-1 拉成一大片空白 -->
      <div class="overflow-auto p-3 flex flex-col gap-1 min-h-0 max-h-[46%] shrink-0">
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

      <div class="flex-1 border-t flex flex-col min-h-0" style="border-color: var(--lw-border)">
        <div class="px-4 py-2 shrink-0 flex items-center justify-between">
          <span class="lw-label">{{ t('console.runHistory') }}</span>
          <span v-if="runStore.runs.length" class="text-[10px] font-bold" style="color: var(--lw-ink-disabled)">
            {{ runStore.runs.length }}
          </span>
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

    <section class="flex-1 min-w-0 flex flex-col relative">
      <header class="px-6 pt-5 pb-3 flex items-end justify-between gap-4 shrink-0">
        <div class="min-w-0">
          <h2 class="lw-title truncate">{{ activeSession?.title ?? t('console.title') }}</h2>
          <p class="lw-subtitle mt-0.5 truncate">{{ t('console.desc') }}</p>
        </div>
        <div class="flex items-center gap-2 shrink-0">
          <LwTag v-if="activeSession?.modelName" tone="primary">{{ activeSession.modelName }}</LwTag>
          <LwButton v-if="activeSession" variant="ghost" size="sm" @click="openEdit">
            <template #icon><el-icon><Edit /></el-icon></template>
            {{ t('console.editSession') }}
          </LwButton>
        </div>
      </header>

      <Transition name="lw-rise">
        <div
          v-if="runStore.replaying"
          class="mx-6 mb-2 px-3 py-2 rounded-lg flex items-center justify-between text-xs shrink-0"
          style="background: var(--lw-secondary-tint); color: var(--lw-secondary)"
        >
          <span class="flex items-center gap-2 min-w-0">
            <el-icon><VideoPlay /></el-icon>
            <span class="truncate">{{ t('console.replaying', { runId: runStore.replayRunId }) }}</span>
          </span>
          <LwButton variant="ghost" size="sm" @click="backToLive">
            <template #icon><el-icon><RefreshLeft /></el-icon></template>
            {{ t('console.backLive') }}
          </LwButton>
        </div>
      </Transition>

      <div class="flex-1 min-h-0 relative">
      <div ref="scrollEl" class="absolute inset-0 overflow-auto px-6" @scroll.passive="onScroll">
        <LwSkeleton v-if="sessionStore.loadingMessages" :rows="6" />
        <!-- 两种空态（未选会话 / 会话内无消息）都给创作入口，不留白也不重复实现 -->
        <div v-else-if="!sessionStore.activeSessionId || !messages.length" class="h-full flex items-center justify-center pb-16">
          <ConsoleQuickStart />
        </div>
        <div v-else class="max-w-3xl mx-auto flex flex-col gap-5 pb-6">
          <div
            v-for="(message, index) in messages"
            :key="index"
            class="tl-msg"
            :class="message.role === 'user' ? 'is-user' : 'is-assistant'"
          >
            <div class="tl-avatar">
              <template v-if="message.role === 'user'">
                {{ (userStore.nickname ?? 'U').slice(0, 1).toUpperCase() }}
              </template>
              <svg v-else width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor"
                   stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                <path d="m12 3-1.912 5.813a2 2 0 0 1-1.275 1.275L3 12l5.813 1.912a2 2 0 0 1 1.275 1.275L12 21l1.912-5.813a2 2 0 0 1 1.275-1.275L21 12l-5.813-1.912a2 2 0 0 1-1.275-1.275L12 3Z" />
              </svg>
            </div>

            <div class="tl-body">
              <div class="tl-meta">
                <span class="tl-who">{{ message.role === 'user' ? t('console.roleUser') : t('console.roleAgent') }}</span>
                <span v-if="message.createdAt" class="tl-time">{{ formatClock(message.createdAt) }}</span>
              </div>

              <div v-if="message.role === 'user'" class="tl-bubble">{{ message.content }}</div>

              <div v-else class="flex flex-col gap-2">
                <ThinkingBlock :content="message.thinking" :streaming="message.streaming" />
                <PlanChecklist v-if="message.plan.length" :steps="message.plan" />
                <ToolStepCard
                  v-for="(step, toolIndex) in message.tools"
                  :key="toolIndex"
                  :label="toolLabel(step.name)"
                  :phase="step.phase"
                  :output="step.output"
                  :is-error="step.isError"
                />
                <div v-if="message.content || message.streaming" class="text-sm">
                  <LwMarkdown :content="message.content" />
                  <span v-if="message.streaming" class="lw-cursor">▍</span>
                </div>
                <div v-if="usageText(message)" class="tl-usage">{{ usageText(message) }}</div>
              </div>
            </div>
          </div>
        </div>
      </div>

        <Transition name="lw-rise">
          <button
            v-if="!stick && messages.length"
            type="button"
            class="tl-jump"
            :aria-label="t('console.jumpLatest')"
            @click="scrollToBottom(true)"
          >
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"
                 stroke-linecap="round" stroke-linejoin="round">
              <path d="M12 5v14M19 12l-7 7-7-7" />
            </svg>
          </button>
        </Transition>
      </div>

      <footer class="px-6 pb-5 pt-3 shrink-0 flex flex-col gap-2 max-w-3xl mx-auto w-full">
        <LwErrorBar v-if="streamError" :message="streamError" />
        <MessageComposer
          ref="composerRef"
          v-model="draft"
          :streaming="runStore.streaming"
          :replaying="runStore.replaying"
          :last-user-message="lastUserMessage"
          :providers="providers"
          :provider-code="activeSession?.providerCode ?? ''"
          :model-name="activeSession?.modelName ?? ''"
          :workspaces="workspaces"
          :workspace-id="activeSession?.workspaceId ?? ''"
          @send="send"
          @abort="abort"
          @new-session="createSession"
          @update:model="updateModel"
          @update:workspace="updateWorkspace"
          @workspace-created="onWorkspaceCreated"
        />
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
.lw-title {
  font-size: 24px;
  font-weight: 700;
  line-height: 1.3;
  color: var(--lw-ink);
}
.lw-subtitle {
  font-size: 14px;
  color: var(--lw-ink-muted);
}
.tl-msg {
  display: flex;
  gap: 10px;
  align-items: flex-start;
}
.tl-msg.is-user {
  flex-direction: row-reverse;
}
.tl-avatar {
  display: flex;
  align-items: center;
  justify-content: center;
  flex: none;
  width: 28px;
  height: 28px;
  margin-top: 2px;
  border-radius: 50%;
  font-size: 12px;
  font-weight: 700;
  box-shadow: var(--lw-shadow-sm);
}
.tl-msg.is-user .tl-avatar {
  background: var(--lw-primary);
  color: var(--lw-on-primary);
}
.tl-msg.is-assistant .tl-avatar {
  background: var(--lw-secondary);
  color: var(--lw-on-primary);
}
.tl-body {
  display: flex;
  flex-direction: column;
  gap: 4px;
  min-width: 0;
  max-width: calc(100% - 38px);
}
.tl-msg.is-user .tl-body {
  align-items: flex-end;
}
.tl-meta {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
}
.tl-who {
  font-weight: 700;
  color: var(--lw-ink);
}
.tl-time {
  font-size: 11px;
  color: var(--lw-ink-disabled);
  font-variant-numeric: tabular-nums;
}
.tl-bubble {
  max-width: 100%;
  padding: 10px 14px;
  border-radius: var(--lw-radius-card);
  border-bottom-right-radius: 4px;
  font-size: 14px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-word;
  background: var(--lw-primary);
  color: var(--lw-on-primary);
}
.tl-usage {
  font-size: 11px;
  color: var(--lw-ink-disabled);
  font-variant-numeric: tabular-nums;
}
.tl-jump {
  position: absolute;
  right: 24px;
  bottom: 16px;
  z-index: 5;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 36px;
  height: 36px;
  border: 1px solid var(--lw-border);
  border-radius: 50%;
  background: var(--lw-surface-raised);
  color: var(--lw-primary-ink);
  box-shadow: var(--lw-shadow-md);
  cursor: pointer;
  transition: transform var(--lw-dur) var(--lw-ease), color var(--lw-dur) var(--lw-ease),
    border-color var(--lw-dur) var(--lw-ease);
}
.tl-jump:hover {
  transform: scale(1.05);
  border-color: var(--lw-secondary);
}
.tl-jump:active {
  transform: scale(0.95);
}
.lw-cursor {
  animation: lw-blink 1s step-start infinite;
  color: var(--lw-primary-ink);
}
.lw-rise-enter-active {
  transition: opacity var(--lw-dur) var(--lw-ease-out), transform var(--lw-dur) var(--lw-ease-out);
}
.lw-rise-leave-active {
  transition: opacity var(--lw-dur-fast) var(--lw-ease), transform var(--lw-dur-fast) var(--lw-ease);
}
.lw-rise-enter-from,
.lw-rise-leave-to {
  opacity: 0;
  transform: translateY(6px);
}
@keyframes lw-blink {
  50% {
    opacity: 0;
  }
}
@media (prefers-reduced-motion: reduce) {
  .tl-jump:hover,
  .tl-jump:active {
    transform: none;
  }
}
</style>
