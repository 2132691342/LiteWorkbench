import axios, { type AxiosRequestConfig } from 'axios'
import { ElMessage } from 'element-plus'
import { i18n } from '@/locales'
import type {
  ApiResult,
  AssistEvent,
  BucketAdminVO,
  ChatStreamREQ,
  DocAssistREQ,
  DocREQ,
  DocSearchParams,
  DocVO,
  DocVersionVO,
  FileVO,
  HealthRESP,
  InvokeToolREQ,
  KbChunkVO,
  KbDocVO,
  ObserveRESP,
  KbREQ,
  KbSearchParams,
  KbVO,
  LogPageVO,
  LogSearchParams,
  LogStatsVO,
  LoginREQ,
  LoginRESP,
  MemoryREQ,
  MemoryVO,
  MenuREQ,
  MenuVO,
  MessageVO,
  OverviewRESP,
  PageREQ,
  PageResult,
  PasswordREQ,
  ProviderPresetVO,
  ProviderREQ,
  ProviderVO,
  PublicKeyVO,
  PythonStatusVO,
  ResetPasswordREQ,
  RoleGrantREQ,
  RoleREQ,
  RoleVO,
  RunEvent,
  RunVO,
  SessionPageREQ,
  SessionREQ,
  SessionVO,
  TraceRunVO,
  TraceSpanVO,
  UsageStatsVO,
  SkillREQ,
  SkillVO,
  ToolVO,
  UserPageREQ,
  UserREQ,
  UserVO,
  WorkspaceREQ,
  WorkspaceVO,
} from '@/types/api'

/** 请求唯一出口：注入令牌、统一解包、统一错误提示 */

function t(key: string, params?: Record<string, unknown>): string {
  return i18n.global.t(key, params ?? {})
}

const TOKEN_KEY = 'lw-token'

export function getToken(): string {
  return localStorage.getItem(TOKEN_KEY) ?? ''
}

export function setToken(token: string): void {
  localStorage.setItem(TOKEN_KEY, token)
}

export function clearToken(): void {
  localStorage.removeItem(TOKEN_KEY)
}

const http = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL ?? '/api/v1',
  timeout: 30000,
})

http.interceptors.request.use((config) => {
  const token = getToken()
  if (token) {
    config.headers.set('Authorization', token)
  }
  return config
})

http.interceptors.response.use(
  (response) => {
    const body = response.data as ApiResult<unknown>
    if (body && typeof body.code === 'string' && body.code !== '0') {
      ElMessage.error(body.message || t('api.requestFailed'))
      return Promise.reject(new Error(body.message))
    }
    return response
  },
  (error) => {
    const status: number | undefined = error.response?.status
    const body = error.response?.data as ApiResult<unknown> | undefined
    if (status === 401) {
      clearToken()
      if (!window.location.pathname.startsWith('/login')) {
        window.location.href = '/login'
      }
    } else if (status === 403) {
      ElMessage.error(body?.message || t('api.noPermission'))
    } else if (body?.message) {
      ElMessage.error(body.message)
    } else {
      ElMessage.error(t('api.networkError'))
    }
    return Promise.reject(error)
  },
)

async function request<T>(config: AxiosRequestConfig): Promise<T> {
  const response = await http.request<ApiResult<T>>(config)
  return response.data.data
}

export function get<T>(url: string, params?: object): Promise<T> {
  return request<T>({ method: 'GET', url, params })
}

/** 二进制下载：绕过 R<T> 解包，用于 CSV 导出等直接落盘的响应 */
export async function getBlob(url: string, params?: object, filename = 'export.csv'): Promise<void> {
  const response = await http.request<Blob>({ method: 'GET', url, params, responseType: 'blob' })
  const blob = response.data
  const link = document.createElement('a')
  link.href = URL.createObjectURL(blob)
  link.download = filename
  link.click()
  URL.revokeObjectURL(link.href)
}

/** POST 写操作，可附加 axios 配置（params / headers / timeout） */
export function post<T>(url: string, data?: unknown, config?: AxiosRequestConfig): Promise<T> {
  return request<T>({ method: 'POST', url, data, ...config })
}

/* ===== 认证 ===== */

export const authApi = {
  publicKey: () => get<PublicKeyVO>('/auth/public-key'),
  login: (data: LoginREQ) => post<LoginRESP>('/auth/login', data),
  logout: () => post<void>('/auth/logout'),
  current: () => get<LoginRESP>('/auth/current'),
  changePassword: (data: PasswordREQ) => post<void>('/auth/password', data),
}

/* ===== 用户 ===== */

export const userApi = {
  page: (params: UserPageREQ) => get<PageResult<UserVO>>('/user/page', params),
  detail: (userId: string) => get<UserVO>(`/user/detail/${userId}`),
  create: (data: UserREQ) => post<string>('/user/create', data),
  update: (data: UserREQ) => post<void>('/user/update', data),
  toggle: (userId: string) => post<void>(`/user/toggle/${userId}`),
  resetPassword: (data: ResetPasswordREQ) => post<void>('/user/reset-password', data),
  remove: (userId: string) => post<void>(`/user/delete/${userId}`),
}

/* ===== 角色 ===== */

export const roleApi = {
  page: (params: PageREQ) => get<PageResult<RoleVO>>('/role/page', params),
  list: () => get<RoleVO[]>('/role/list'),
  detail: (roleId: string) => get<RoleVO>(`/role/detail/${roleId}`),
  menuIds: (roleId: string) => get<string[]>(`/role/menu-ids/${roleId}`),
  create: (data: RoleREQ) => post<string>('/role/create', data),
  update: (data: RoleREQ) => post<void>('/role/update', data),
  grant: (data: RoleGrantREQ) => post<void>('/role/grant', data),
  remove: (roleId: string) => post<void>(`/role/delete/${roleId}`),
}

/* ===== 菜单 ===== */

export const menuApi = {
  tree: () => get<MenuVO[]>('/menu/tree'),
  userTree: () => get<MenuVO[]>('/menu/user-tree'),
  detail: (menuId: string) => get<MenuVO>(`/menu/detail/${menuId}`),
  create: (data: MenuREQ) => post<string>('/menu/create', data),
  update: (data: MenuREQ) => post<void>('/menu/update', data),
  remove: (menuId: string) => post<void>(`/menu/delete/${menuId}`),
}

/* ===== 会话 ===== */

export const sessionApi = {
  page: (params: SessionPageREQ) => get<PageResult<SessionVO>>('/session/page', params),
  detail: (sessionId: string) => get<SessionVO>(`/session/detail/${sessionId}`),
  create: (data: SessionREQ) => post<string>('/session/create', data),
  update: (data: SessionREQ) => post<void>('/session/update', data),
  remove: (sessionId: string) => post<void>(`/session/delete/${sessionId}`),
  messages: (sessionId: string) => get<MessageVO[]>(`/session/messages/${sessionId}`),
}

/* ===== 运行 ===== */

export const runApi = {
  page: (params: SessionPageREQ) => get<PageResult<RunVO>>('/run/page', params),
  detail: (runId: string) => get<RunVO>(`/run/detail/${runId}`),
  events: (runId: string, afterSeq = 0) => get<RunEvent[]>(`/run/events/${runId}`, { afterSeq }),
  abort: (runId: string) => post<void>(`/run/abort/${runId}`),
}

/* ===== 可观测性 ===== */

export const traceApi = {
  spans: (runId: string) => get<TraceSpanVO[]>(`/trace/spans/${runId}`),
  tree: (sessionId: string) => get<TraceRunVO[]>(`/trace/tree/${sessionId}`),
  usage: (params: { from?: number; to?: number; days?: number } = {}) =>
    get<UsageStatsVO>('/trace/usage', params),
}

/* ===== 长期记忆 ===== */

export const memoryApi = {
  page: (params: MemoryREQ) => get<PageResult<MemoryVO>>('/memory/page', params),
  create: (data: MemoryREQ) => post<string>('/memory/create', data),
  update: (data: MemoryREQ) => post<void>('/memory/update', data),
  remove: (memoryId: string) => post<void>(`/memory/delete/${memoryId}`),
}

/* ===== 工具 ===== */

export const toolApi = {
  list: () => get<ToolVO[]>('/tool/list'),
  toggle: (toolId: string, enabled: boolean) => post<void>(`/tool/toggle/${toolId}`, null, { params: { enabled } }),
  invoke: (toolName: string, data: InvokeToolREQ) => post<string>(`/tool/invoke/${toolName}`, data),
}

/* ===== 技能 ===== */

export const skillApi = {
  page: (params: PageREQ) => get<PageResult<SkillVO>>('/skill/page', params),
  create: (data: SkillREQ) => post<string>('/skill/create', data),
  update: (data: SkillREQ) => post<void>('/skill/update', data),
  remove: (skillId: string) => post<void>(`/skill/delete/${skillId}`),
  restore: (skillId: string) => post<void>(`/skill/restore/${skillId}`),
}

/* ===== 文件 ===== */

export const fileApi = {
  page: (params: { page?: number; size?: number; sessionId?: string; category?: string; keyword?: string }) =>
    get<PageResult<FileVO>>('/file/page', params),
  upload: (file: File, sessionId?: string, workspaceId?: string) => {
    const form = new FormData()
    form.append('file', file)
    return post<FileVO>('/file/upload', form, {
      headers: { 'Content-Type': 'multipart/form-data' },
      params: sessionId || workspaceId ? { sessionId, workspaceId } : undefined,
      timeout: 120000,
    })
  },
  url: (fileId: string) => get<string>(`/file/url/${fileId}`),
  remove: (fileId: string) => post<void>(`/file/delete/${fileId}`),
}

/* ===== 知识库 ===== */

export const kbApi = {
  page: (params: PageREQ) => get<PageResult<KbVO>>('/kb/page', params),
  detail: (kbId: string) => get<KbVO>(`/kb/detail/${kbId}`),
  create: (data: KbREQ) => post<string>('/kb/create', data),
  update: (data: KbREQ) => post<void>('/kb/update', data),
  remove: (kbId: string) => post<void>(`/kb/delete/${kbId}`),
  docPage: (params: { kbId: string; page?: number; size?: number; keyword?: string; state?: string }) =>
    get<PageResult<KbDocVO>>('/kb/doc/page', params),
  upload: (kbId: string, file: File) => {
    const form = new FormData()
    form.append('file', file)
    return post<KbDocVO>(`/kb/doc/upload/${kbId}`, form, {
      headers: { 'Content-Type': 'multipart/form-data' },
      timeout: 120000,
    })
  },
  removeDoc: (docId: string) => post<void>(`/kb/doc/delete/${docId}`),
  reparse: (docId: string) => post<void>(`/kb/doc/reparse/${docId}`),
  search: (params: KbSearchParams) => get<PageResult<KbChunkVO>>('/kb/search', params),
}

/* ===== 创作文档 ===== */

export const docApi = {
  page: (params: DocSearchParams & { state?: string }) => get<PageResult<DocVO>>('/doc/page', params),
  detail: (docId: string) => get<DocVO>(`/doc/detail/${docId}`),
  create: (data: DocREQ) => post<string>('/doc/create', data),
  update: (data: DocREQ) => post<void>('/doc/update', data),
  remove: (docId: string) => post<void>(`/doc/delete/${docId}`),
  search: (params: DocSearchParams) => get<PageResult<DocVO>>('/doc/search', params),
  versionPage: (docId: string, page = 1, size = 20) =>
    get<PageResult<DocVersionVO>>('/doc/version/page', { docId, page, size }),
  versionDetail: (versionId: string) => get<DocVersionVO>(`/doc/version/detail/${versionId}`),
  restore: (versionId: string) => post<void>(`/doc/version/restore/${versionId}`),
}

/* ===== 模型供应方 ===== */

export const providerApi = {
  list: () => get<ProviderVO[]>('/provider/list'),
  presets: () => get<ProviderPresetVO[]>('/provider/presets'),
  create: (data: ProviderREQ) => post<string>('/provider/create', data),
  update: (data: ProviderREQ) => post<void>('/provider/update', data),
  remove: (providerId: string) => post<void>(`/provider/delete/${providerId}`),
  test: (providerId: string) => post<ProviderVO>(`/provider/test/${providerId}`),
}

/* ===== 工作区 ===== */

export const workspaceApi = {
  list: () => get<WorkspaceVO[]>('/workspace/list'),
  create: (data: WorkspaceREQ) => post<string>('/workspace/create', data),
  update: (data: WorkspaceREQ) => post<void>('/workspace/update', data),
  setDefault: (workspaceId: string) => post<void>(`/workspace/default/${workspaceId}`),
  remove: (workspaceId: string) => post<void>(`/workspace/delete/${workspaceId}`),
}

/* ===== 存储桶管理（管理员） ===== */

export const storageApi = {
  buckets: () => get<BucketAdminVO[]>('/storage/buckets'),
  createBucket: (bucketName: string) => post<void>('/storage/bucket/create', { bucketName }),
  removeBucket: (bucketName: string) => post<void>(`/storage/bucket/delete/${bucketName}`),
}

/* ===== 监控 ===== */

export const monitorApi = {
  health: () => get<HealthRESP>('/monitor/health'),
  overview: () => get<OverviewRESP>('/monitor/overview'),
  observe: () => get<ObserveRESP>('/monitor/observe'),
  observeReset: () => post<string>('/monitor/observe/reset'),
  pythonStatus: () => get<PythonStatusVO>('/runtime/python'),
  pythonReprobe: () => post<PythonStatusVO>('/runtime/python/reprobe'),
}

/* ===== 日志检索 ===== */

export const logApi = {
  search: (params: LogSearchParams) => get<LogPageVO>('/log/search', params),
  stats: (params: Omit<LogSearchParams, 'page' | 'size'>) => get<LogStatsVO>('/log/stats', params),
  exportCsv: (params: LogSearchParams) => getBlob('/log/export', params, 'lw-log.csv'),
}

/* ===== SSE 流式对话 ===== */

export interface RunStreamHandlers {
  onEvent: (event: RunEvent) => void
  onEnd: (aborted: boolean) => void
  onError: (message: string) => void
}

/** 以 fetch 发起流式对话并解析 SSE 帧（需带 Authorization 头，故不用原生 EventSource），返回中断函数 */
export function openRunStream(body: ChatStreamREQ, handlers: RunStreamHandlers): (reason?: string) => void {
  const controller = new AbortController()
  const base = import.meta.env.VITE_API_BASE_URL ?? '/api/v1'
  ;(async () => {
    try {
      const response = await fetch(`${base}/chat/stream`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', Authorization: getToken() },
        body: JSON.stringify(body),
        signal: controller.signal,
      })
      if (!response.ok || !response.body) {
        handlers.onError(t('api.connectFailed', { code: response.status }))
        handlers.onEnd(false)
        return
      }
      const reader = response.body.getReader()
      const decoder = new TextDecoder('utf-8')
      let buffer = ''
      let aborted = false
      // 已投递给 reducer 的最大 seq：水位快照超过它即说明丢过帧，从库里补齐
      let lastSeq = 0
      let backfilling = false
      /** 丢帧补齐：按 afterSeq 拉回缺失事件后按序投递，失败不阻断实时流 */
      const backfill = async (runId: string, afterSeq: number): Promise<void> => {
        if (backfilling || !runId) return
        backfilling = true
        try {
          const missing = await runApi.events(runId, afterSeq)
          for (const item of missing) {
            lastSeq = Math.max(lastSeq, Number(item.seq ?? 0))
            handlers.onEvent(item)
          }
        } catch {
          // 补齐是兜底能力，失败时以 run.end 的权威结果为准
        } finally {
          backfilling = false
        }
      }
      for (;;) {
        const { done, value } = await reader.read()
        if (done) break
        buffer += decoder.decode(value, { stream: true })
        const frames = buffer.split('\n\n')
        buffer = frames.pop() ?? ''
        for (const frame of frames) {
          for (const line of frame.split('\n')) {
            if (line.startsWith(':')) continue
            if (!line.startsWith('data:')) continue
            const payload = line.slice(5).trim()
            if (!payload) continue
            try {
              const event = JSON.parse(payload) as RunEvent
              if (event.eventType === 'run.snapshot') {
                const watermark = Number(event.payload?.watermark ?? 0)
                if (watermark > lastSeq) {
                  void backfill(event.runId, lastSeq)
                }
              } else if (event.eventType === 'run.end') {
                lastSeq = Math.max(lastSeq, Number(event.seq ?? 0))
                aborted = event.payload?.status === 'aborted'
                handlers.onEvent(event)
              } else if (event.eventType === 'error') {
                handlers.onError(String(event.payload?.message ?? t('api.runFailed')))
              } else {
                lastSeq = Math.max(lastSeq, Number(event.seq ?? 0))
                handlers.onEvent(event)
              }
            } catch {
              // 非 JSON 帧忽略
            }
          }
        }
      }
      handlers.onEnd(aborted)
    } catch (error) {
      if ((error as Error).name !== 'AbortError') {
        handlers.onError((error as Error).message)
        handlers.onEnd(false)
      }
    }
  })()
  return (reason?: string) => controller.abort(reason ?? 'abort')
}

/* ===== SSE 流式辅助写作 ===== */

export interface AssistStreamHandlers {
  onDelta: (text: string) => void
  onEnd: (payload: AssistEvent) => void
  onError: (message: string) => void
}

/** 以 fetch 发起流式辅助写作并解析 SSE 帧（assist.event + data 载荷），断连即取消服务端模型调用 */
export function openAssistStream(body: DocAssistREQ, handlers: AssistStreamHandlers): (reason?: string) => void {
  const controller = new AbortController()
  const base = import.meta.env.VITE_API_BASE_URL ?? '/api/v1'
  let finished = false
  ;(async () => {
    try {
      const response = await fetch(`${base}/doc/assist/stream`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', Authorization: getToken() },
        body: JSON.stringify(body),
        signal: controller.signal,
      })
      if (!response.ok || !response.body) {
        finished = true
        handlers.onError(t('api.connectFailed', { code: response.status }))
        return
      }
      const reader = response.body.getReader()
      const decoder = new TextDecoder('utf-8')
      let buffer = ''
      for (;;) {
        const { done, value } = await reader.read()
        if (done) break
        buffer += decoder.decode(value, { stream: true })
        const frames = buffer.split('\n\n')
        buffer = frames.pop() ?? ''
        for (const frame of frames) {
          const data = frame
            .split('\n')
            .filter((line) => line.startsWith('data:'))
            .map((line) => line.slice(5).trim())
            .join('')
          if (!data) continue
          try {
            const event = JSON.parse(data) as AssistEvent
            if (event.type === 'assist.delta') {
              handlers.onDelta(event.text ?? '')
            } else if (event.type === 'assist.end') {
              finished = true
              handlers.onEnd(event)
            } else if (event.type === 'assist.error') {
              finished = true
              handlers.onError(event.message ?? t('api.requestFailed'))
            }
          } catch {
            // 非 JSON 帧忽略
          }
        }
      }
      if (!finished) {
        handlers.onError(t('api.streamInterrupted'))
      }
    } catch (error) {
      if ((error as Error).name !== 'AbortError') {
        handlers.onError((error as Error).message)
      }
    }
  })()
  return (reason?: string) => controller.abort(reason ?? 'abort')
}
