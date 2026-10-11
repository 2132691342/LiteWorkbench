/** 后端接口类型定义，与 Java VO/REQ 严格对齐 */

/** 统一响应信封 */
export interface ApiResult<T> {
  code: string
  message: string
  data: T
  requestId?: string
  timestamp: number
}

/** 分页结果 */
export interface PageResult<T> {
  list: T[]
  total: number
  page: number
  size: number
}

/** 分页请求基参数 */
export interface PageREQ {
  page?: number
  size?: number
  keyword?: string
}

/* ===== 认证 ===== */

export interface LoginREQ {
  username: string
  password: string
}

export interface PasswordREQ {
  oldPassword: string
  newPassword: string
}

export interface ResetPasswordREQ {
  userId: string
  newPassword: string
}

export interface LoginRESP {
  token?: string
  user: UserVO
  roleCodes: string[]
  perms: string[]
  menus: MenuVO[]
}

/* ===== 用户 ===== */

export interface UserVO {
  userId: string
  username: string
  nickname: string
  email?: string
  avatarUrl?: string
  state: string
  roleIds?: string[]
  lastLoginAt?: number
  createdAt?: number
}

export interface UserREQ {
  userId?: string
  username?: string
  password?: string
  nickname: string
  email?: string
  roleIds?: string[]
  remark?: string
}

export interface UserPageREQ extends PageREQ {
  state?: string
}

/* ===== 角色 ===== */

export interface RoleVO {
  roleId: string
  roleCode: string
  roleName: string
  sortNo: number
  state: string
  builtin: boolean
  createdAt?: number
}

export interface RoleREQ {
  roleId?: string
  roleCode?: string
  roleName: string
  sortNo?: number
  remark?: string
}

export interface RoleGrantREQ {
  roleId: string
  menuIds: string[]
}

/* ===== 菜单 ===== */

export interface MenuVO {
  menuId: string
  parentId: string
  menuName: string
  menuType: 'dir' | 'menu' | 'button'
  routePath?: string
  component?: string
  icon?: string
  permCode?: string
  sortNo: number
  visible: boolean
  children?: MenuVO[]
}

export interface MenuREQ {
  menuId?: string
  parentId?: string
  menuName: string
  menuType: string
  routePath?: string
  component?: string
  icon?: string
  permCode?: string
  sortNo?: number
  visible?: boolean
  remark?: string
}

/* ===== 会话与运行 ===== */

export interface SessionVO {
  sessionId: string
  userId: string
  title: string
  providerCode?: string
  modelName?: string
  messageCount?: number
  runCount?: number
  totalTokens?: number
  lastActiveAt?: number
  state: string
  createdAt?: number
}

export interface SessionREQ {
  sessionId?: string
  title?: string
  providerCode?: string
  modelName?: string
  systemPrompt?: string
}

export interface MessageVO {
  messageId: string
  runId?: string
  roleCode: 'user' | 'assistant' | 'tool' | 'system'
  content?: string
  createdAt?: number
}

export interface RunVO {
  runId: string
  sessionId: string
  prompt?: string
  modelName?: string
  status: 'queued' | 'running' | 'success' | 'failed' | 'aborted'
  stopReason?: string
  iterCount?: number
  inputTokens?: number
  outputTokens?: number
  durationMs?: number
  startedAt?: number
  endedAt?: number
  createdAt?: number
}

/** 归一化运行事件，与后端 RunEventDTO 字段一致 */
export interface RunEvent {
  runId: string
  sessionId: string
  seq: number
  eventType: string
  blockType?: string
  textDelta?: string
  payload?: Record<string, unknown>
  occurredAt: number
}

/* ===== 工具 ===== */

export interface ToolVO {
  toolId: string
  toolName: string
  toolLabel: string
  category: string
  riskLevel: 'safe' | 'confirm' | 'danger'
  enabled: boolean
}

/* ===== 技能 ===== */

export interface SkillVO {
  skillId: string
  skillCode: string
  skillName: string
  summary?: string
  content?: string
  triggerWords?: string
  enabled: boolean
  createdAt?: number
  updatedAt?: number
}

export interface SkillREQ {
  skillId?: string
  skillCode: string
  skillName: string
  summary?: string
  content?: string
  triggerWords?: string
  enabled?: boolean
}

/* ===== 文件 ===== */

export interface FileVO {
  fileId: string
  sessionId?: string
  fileName: string
  fileSize: number
  contentType?: string
  category: string
  createdBy?: string
  createdAt?: number
}

/* ===== 模型供应方 ===== */

export interface ProviderVO {
  providerId: string
  providerCode: string
  providerName: string
  baseUrl?: string
  apiKeyMasked?: string
  hasApiKey: boolean
  defaultModel?: string
  enabled: boolean
  updatedAt?: number
}

export interface ProviderREQ {
  providerId?: string
  providerCode: string
  providerName: string
  baseUrl?: string
  apiKey?: string
  defaultModel?: string
  enabled?: boolean
}

/* ===== 监控 ===== */

export interface ComponentHealth {
  name: string
  status: 'UP' | 'DOWN'
  detail?: string
  costMs?: number
}

export interface HealthRESP {
  status: 'UP' | 'DEGRADED' | 'DOWN'
  components: ComponentHealth[]
}

export interface PythonStatusVO {
  status: 'READY' | 'NOT_FOUND' | 'BAD_VERSION'
  version?: string
  source?: string
  ok: boolean
}

export interface TrendPoint {
  date: string
  value: number
}

export interface OverviewRESP {
  sessionCount: number
  runCount: number
  todayRunCount: number
  totalTokens: number
  runTrend: TrendPoint[]
  tokenTrend: TrendPoint[]
  toolDistribution: Record<string, number>
}

/* ===== 日志 ===== */

export interface LogDocVO {
  logId: string
  ts: number
  level: string
  logger?: string
  thread?: string
  traceId?: string
  message?: string
  exception?: string
}

export interface LogSearchParams {
  keyword?: string
  level?: string
  from?: number
  to?: number
  page?: number
  size?: number
}

/* ===== 设置 ===== */

export interface SettingREQ {
  settingKey: string
  settingValue?: string
}

/* ===== 对话入参 ===== */

export interface ChatStreamREQ {
  sessionId: string
  content: string
}

export interface InvokeToolREQ {
  args: Record<string, unknown>
  confirmed?: boolean
  timeoutSeconds?: number
}

export interface SessionPageREQ extends PageREQ {
  sessionId?: string
}
