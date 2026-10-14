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

  async function create(title: string): Promise<string> {
    const sessionId = await sessionApi.create({ title })
    list.value = [{ sessionId, title }, ...list.value] as SessionVO[]
    activeSessionId.value = sessionId
    messages.value = []
    return sessionId
  }

  async function update(payload: SessionREQ): Promise<void> {
    await sessionApi.update(payload)
    if (payload.sessionId) {
      await refreshDetail(payload.sessionId)
    }
  }

  async function remove(sessionId: string): Promise<void> {
    await sessionApi.remove(sessionId)
    list.value = list.value.filter((item) => item.sessionId !== sessionId)
    if (activeSessionId.value === sessionId) {
      activeSessionId.value = ''
      messages.value = []
    }
  }

  /** 选中会话并加载消息，失败记入 error 供页面内联提示 */
  async function select(sessionId: string): Promise<void> {
    activeSessionId.value = sessionId
    loadingMessages.value = true
    error.value = ''
    try {
      messages.value = await sessionApi.messages(sessionId)
    } catch (err) {
      messages.value = []
      error.value = err instanceof Error ? err.message : ''
      throw err
    } finally {
      loadingMessages.value = false
    }
  }

  async function refreshDetail(sessionId: string): Promise<void> {
    try {
      const detail = await sessionApi.detail(sessionId)
      const index = list.value.findIndex((item) => item.sessionId === sessionId)
      if (index >= 0) {
        const next = [...list.value]
        next.splice(index, 1, detail)
        list.value = next
      }
    } catch (err) {
      error.value = err instanceof Error ? err.message : ''
      throw err
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
