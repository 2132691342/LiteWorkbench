import { defineStore } from 'pinia'
import { computed, ref, shallowRef } from 'vue'
import { sessionApi } from '@/api'
import type { MessageVO, SessionREQ, SessionVO } from '@/types/api'

/** 会话状态：列表、当前会话与消息，工作台各视图共享同一份数据 */
export const useSessionStore = defineStore('session', () => {
  const list = shallowRef<SessionVO[]>([])
  const messages = shallowRef<MessageVO[]>([])
  const activeSessionId = ref('')
  const loading = ref(false)
  const loadingMessages = ref(false)
  const error = ref('')

  const activeSession = computed(
    () => list.value.find((item) => item.sessionId === activeSessionId.value) ?? null,
  )

  /** 拉取会话列表（骨架屏态由 loading 驱动） */
  async function loadList(): Promise<void> {
    loading.value = true
    error.value = ''
    try {
      const page = await sessionApi.page({ page: 1, size: 100 })
      list.value = page.list
    } catch (err) {
      error.value = err instanceof Error ? err.message : ''
    } finally {
      loading.value = false
    }
  }

  /** 新建会话并设为当前会话 */
  async function create(title: string): Promise<string> {
    const sessionId = await sessionApi.create({ title })
    list.value = [{ sessionId, title }, ...list.value] as SessionVO[]
    activeSessionId.value = sessionId
    messages.value = []
    return sessionId
  }

  /** 更新标题 / 模型等元信息 */
  async function update(payload: SessionREQ): Promise<void> {
    await sessionApi.update(payload)
    if (payload.sessionId) {
      await refreshDetail(payload.sessionId)
    }
  }

  /** 删除会话并从列表移除 */
  async function remove(sessionId: string): Promise<void> {
    await sessionApi.remove(sessionId)
    list.value = list.value.filter((item) => item.sessionId !== sessionId)
    if (activeSessionId.value === sessionId) {
      activeSessionId.value = ''
      messages.value = []
    }
  }

  /** 选中会话并加载消息 */
  async function select(sessionId: string): Promise<void> {
    activeSessionId.value = sessionId
    loadingMessages.value = true
    try {
      messages.value = await sessionApi.messages(sessionId)
    } finally {
      loadingMessages.value = false
    }
  }

  /** 重新拉取单个会话详情，回填列表 */
  async function refreshDetail(sessionId: string): Promise<void> {
    const detail = await sessionApi.detail(sessionId)
    const index = list.value.findIndex((item) => item.sessionId === sessionId)
    if (index >= 0) {
      const next = [...list.value]
      next.splice(index, 1, detail)
      list.value = next
    }
  }

  function reset(): void {
    list.value = []
    messages.value = []
    activeSessionId.value = ''
    error.value = ''
  }

  return {
    list,
    messages,
    activeSessionId,
    loading,
    loadingMessages,
    error,
    activeSession,
    loadList,
    create,
    update,
    remove,
    select,
    refreshDetail,
    reset,
  }
})
