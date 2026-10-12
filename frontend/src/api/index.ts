import axios, { type AxiosRequestConfig } from 'axios'
import { ElMessage } from 'element-plus'
import { i18n } from '@/locales'
import type {
  ApiResult,
  ChatStreamREQ,
  DocAssistREQ,
  DocAssistVO,
  DocREQ,
  DocSearchParams,
  DocVO,
  DocVersionVO,
  FileVO,
  PromptRenderREQ,
  PromptREQ,
  PromptVO,
  HealthRESP,
  InvokeToolREQ,
  KbChunkVO,
  KbDocVO,
  KbREQ,
  KbSearchParams,
  KbVO,
  LogDocVO,
  LogSearchParams,
  LogStatsVO,
  LoginREQ,
  LoginRESP,
  MenuREQ,
  MenuVO,
  MessageVO,
  OverviewRESP,
  PageREQ,
  PageResult,
  PasswordREQ,
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
  SkillREQ,
  SkillVO,
  ToolVO,
  UserPageREQ,
  UserREQ,
  UserVO,
} from '@/types/api'

/** 请求唯一出口：注入令牌、统一解包、统一错误提示 */

/** 按当前语言取提示文案 */
function t(key: string, params?: Record<string, unknown>): string {
  return i18n.global.t(key, params ?? {})
}

/** 本地存储的键名，与请求头名（Authorization）区分 */
const TOKEN_KEY = 'lw-token'

/** 读取本地令牌 */
export function getToken(): string {
  return localStorage.getItem(TOKEN_KEY) ?? ''
}

/** 写入本地令牌 */
export function setToken(token: string): void {
  localStorage.setItem(TOKEN_KEY, token)
}

/** 清除本地令牌 */
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

/** 统一请求入口，返回解包后的业务数据 */
async function request<T>(config: AxiosRequestConfig): Promise<T> {
  const response = await http.request<ApiResult<T>>(config)
  return response.data.data
}

/** GET 查询 */
export function get<T>(url: string, params?: object): Promise<T> {
  return request<T>({ method: 'GET', url, params })
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
}

/* ===== 文件 ===== */

export const fileApi = {
  page: (params: { page?: number; size?: number; sessionId?: string; category?: string; keyword?: string }) =>
    get<PageResult<FileVO>>('/file/page', params),
  upload: (file: File, sessionId?: string) => {
    const form = new FormData()
    form.append('file', file)
    return post<FileVO>('/file/upload', form, {
      headers: { 'Content-Type': 'multipart/form-data' },
      params: sessionId ? { sessionId } : undefined,
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
  assist: (data: DocAssistREQ) => post<DocAssistVO>('/doc/assist', data, { timeout: 180000 }),
}

/* ===== 提示词 ===== */

export const promptApi = {
  page: (params: PageREQ & { category?: string }) => get<PageResult<PromptVO>>('/prompt/page', params),
  detail: (promptId: string) => get<PromptVO>(`/prompt/detail/${promptId}`),
  create: (data: PromptREQ) => post<string>('/prompt/create', data),
  update: (data: PromptREQ) => post<void>('/prompt/update', data),
  remove: (promptId: string) => post<void>(`/prompt/delete/${promptId}`),
  copy: (promptId: string) => post<string>(`/prompt/copy/${promptId}`),
  render: (data: PromptRenderREQ) => post<string>('/prompt/render', data),
}

/* ===== 模型供应方 ===== */

export const providerApi = {
  list: () => get<ProviderVO[]>('/provider/list'),
  create: (data: ProviderREQ) => post<string>('/provider/create', data),
  update: (data: ProviderREQ) => post<void>('/provider/update', data),
  remove: (providerId: string) => post<void>(`/provider/delete/${providerId}`),
  test: (providerId: string) => post<ProviderVO>(`/provider/test/${providerId}`),
}

/* ===== 监控 ===== */

export const monitorApi = {
  health: () => get<HealthRESP>('/monitor/health'),
  overview: () => get<OverviewRESP>('/monitor/overview'),
  pythonStatus: () => get<PythonStatusVO>('/runtime/python'),
  pythonReprobe: () => post<PythonStatusVO>('/runtime/python/reprobe'),
}

/* ===== 日志检索 ===== */

export const logApi = {
  search: (params: LogSearchParams) => get<PageResult<LogDocVO>>('/log/search', params),
  stats: (params: { from?: number; to?: number }) => get<LogStatsVO>('/log/stats', params),
}

/* ===== SSE 流式对话 ===== */

export interface RunStreamHandlers {
  onEvent: (event: RunEvent) => void
  onEnd: (aborted: boolean) => void
  onError: (message: string) => void
}

/**
 * 以 fetch 发起流式对话并解析 SSE 帧。
 * 需要携带 Authorization 头，故不用原生 EventSource。
 * 返回中断函数：调用后停止消费（服务端按中断收尾）。
 */
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
              if (event.eventType === 'run.end') {
                aborted = event.payload?.status === 'aborted'
                handlers.onEvent(event)
              } else if (event.eventType === 'error') {
                handlers.onError(String(event.payload?.message ?? t('api.runFailed')))
              } else {
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
