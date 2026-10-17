/** 后端接口类型定义，与 Java VO/REQ 严格对齐 */

export interface ApiResult<T> {
  code: string
  message: string
  data: T
  requestId?: string
  timestamp: number
}

export interface PageResult<T> {
  list: T[]
  total: number
  page: number
  size: number
}

export interface PageREQ {
  page?: number
  size?: number
  keyword?: string
}

/* ===== 认证 ===== */

export interface PublicKeyVO {
  keyId: string
  algorithm: string
  publicKey: string
  expiresAt: number
}

export interface LoginREQ {
  username: string
  /** RSA 密文，明文不出浏览器 */
  password: string
  keyId: string
}

export interface PasswordREQ {
  oldPassword: string
  newPassword: string
  keyId: string
}

export interface ResetPasswordREQ {
  userId: string
  /** 新密码 RSA 密文 */
  newPassword: string
  /** 加密所用公钥标识 */
  keyId: string
}

export interface LoginRESP {
  token?: string
  user: UserVO
  roleCodes: string[]
  perms: string[]
  /** 为 true 时前端必须强制跳转改密页 */
  pwdResetRequired?: boolean
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
  /** 初始密码 RSA 密文，仅新建时提交 */
  password?: string
  /** 初始密码加密所用公钥标识 */
  passwordKeyId?: string
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
  workspaceId?: string
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
  workspaceId?: string
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

/* ===== 可观测性：链路片段 / Run 树 / 用量归因 ===== */

export interface TraceSpanVO {
  spanId: string
  kind: 'model' | 'tool' | 'plan'
  name: string
  iteration?: number
  offsetMs: number
  durationMs?: number
  status: 'ok' | 'error' | 'pending'
  inputTokens?: number
  outputTokens?: number
  outputChars?: number
  arguments?: string
}

export interface TraceRunVO {
  runId: string
  parentRunId?: string
  relation?: string
  prompt?: string
  modelName?: string
  status?: string
  stopReason?: string
  iterCount?: number
  inputTokens?: number
  outputTokens?: number
  durationMs?: number
  startedAt?: number
  eventCount?: number
  children?: TraceRunVO[]
}

export interface UsageTotals {
  runCount?: number
  inputTokens?: number
  outputTokens?: number
  avgDurationMs?: number
}

export interface UsageDailyPoint {
  day: string
  runCount?: number
  inputTokens?: number
  outputTokens?: number
  successRate?: number
}

export interface UsageModelSlice {
  modelName: string
  runCount?: number
  inputTokens?: number
  outputTokens?: number
}

export interface UsageStateSlice {
  state: string
  runCount?: number
}

export interface UsageStatsVO {
  totals: UsageTotals
  daily: UsageDailyPoint[]
  byModel: UsageModelSlice[]
  byState: UsageStateSlice[]
}

/* ===== 长期记忆 ===== */

export interface MemoryVO {
  memoryId: string
  memoryKey: string
  memoryValue: string
  category: string
  weight?: number
  hitCount?: number
  updatedAt?: number
  createdAt?: number
}

export interface MemoryREQ {
  memoryId?: string
  memoryKey?: string
  memoryValue?: string
  category?: string
  weight?: number
  page?: number
  size?: number
  keyword?: string
}

/* ===== 任务计划 ===== */

export interface PlanStep {
  index: number
  content: string
  status: 'pending' | 'doing' | 'done'
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
  /** 平台内置技能：可改可删，可一键恢复默认 */
  builtin?: boolean
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
  /** 上下文窗口 token 数 */
  contextWindow?: number
  /** 最大输出 token 数 */
  maxOutputTokens?: number
  /** 采样温度 0~2 */
  temperature?: number
  /** top-P 0~1 */
  topP?: number
  /** 支持图片输入 */
  capVision?: boolean
  /** 支持工具调用 */
  capTool?: boolean
  /** 支持推理输出 */
  capReasoning?: boolean
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
  contextWindow?: number
  maxOutputTokens?: number
  temperature?: number
  topP?: number
  capVision?: boolean
  capTool?: boolean
  capReasoning?: boolean
  enabled?: boolean
}

/** 供应方预设：与 hutool-ai 对齐的五个候选，新建表单下拉与默认值来源 */
export interface ProviderPresetVO {
  providerCode: string
  baseUrl?: string
  defaultModel?: string
  protocol: 'openai'
  requiresKey: boolean
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
  successRunCount: number
  todayRunCount: number
  totalTokens: number
  runTrend: TrendPoint[]
  tokenTrend: TrendPoint[]
  toolDistribution: Record<string, number>
}

/* ===== 调用观测 ===== */

export interface CallStatVO {
  /** 观测键：SQL 语句模板或 HTTP 路由模式 */
  key: string
  count: number
  /** 平均耗时，毫秒 */
  avgMs: number
  /** 最慢一次耗时，毫秒 */
  maxMs: number
  /** 最近一次发生时间，毫秒时间戳 */
  lastTs: number
  /** 附加样本：SQL 为 mapper 方法，HTTP 为查询串 */
  sample?: string
}

export interface SqlObserve {
  count: number
  slowCount: number
  avgMs: number
  maxMs: number
  top: CallStatVO[]
}

export interface HttpObserve {
  count: number
  errorCount: number
  avgMs: number
  maxMs: number
  top: CallStatVO[]
}

export interface ObserveRESP {
  windowStartMs: number
  windowMs: number
  sql: SqlObserve
  http: HttpObserve
}

/* ===== 日志 ===== */

export interface LogDocVO {
  logId: string
  ts: number
  level: string
  logger?: string
  thread?: string
  traceId?: string
  userId?: string
  sessionId?: string
  runId?: string
  message?: string
  exception?: string
}

export interface LogSearchParams {
  keyword?: string
  /** 关键字匹配列：message（全文）/ logger / traceId / exception */
  field?: string
  level?: string
  logger?: string
  traceId?: string
  /** 用户 ID 等值，仅全局视角（monitor:log:global）生效 */
  userId?: string
  /** 运行 ID 等值，用于串联一次运行的全部日志 */
  runId?: string
  /** 会话 ID 等值，用于串联一个会话的全部日志 */
  sessionId?: string
  from?: number
  to?: number
  page?: number
  size?: number
}

/* ===== 工作区与存储桶 ===== */

/** 工作区：用户级资产容器，与 MinIO 桶一一绑定 */
export interface WorkspaceVO {
  workspaceId: string
  workspaceName: string
  /** 绑定桶名 */
  bucketName: string
  description?: string
  /** 是否默认工作区 */
  isDefault?: boolean
  /** 桶内对象数 */
  objectCount?: number
  /** 桶内对象总字节数 */
  totalSize?: number
  /** 创建时间，毫秒时间戳 */
  createdAt?: number
}

export interface WorkspaceREQ {
  workspaceId?: string
  workspaceName: string
  description?: string
}

/** 存储桶管理视图（管理员）：全平台桶清单与占用 */
export interface BucketAdminVO {
  bucketName: string
  creationDate?: number
  objectCount?: number
  totalSize?: number
  /** 平台业务桶（主桶与工作区桶）：禁止删除 */
  protectedBucket?: boolean
}

/** 日志分布概览：级别计数与时间直方图；scope 为当前检索视角，bucketMs 为直方图桶宽（小时 / 天） */
export interface LogStatsVO {
  scope?: 'global' | 'self'
  levels?: { level: string; count: number }[]
  histogram?: { hour: number; count: number }[]
  bucketMs?: number
}

/** 日志检索分页结果：分页字段之外带检索视角标识 */
export interface LogPageVO {
  list: LogDocVO[]
  total: number
  page: number
  size: number
  scope?: 'global' | 'self'
}

/* ===== 知识库 ===== */

export interface KbVO {
  kbId: string
  userId?: string
  kbName: string
  description?: string
  docCount?: number
  chunkCount?: number
  createdAt?: number
  updatedAt?: number
}

export interface KbREQ {
  kbId?: string
  kbName: string
  description?: string
}

export interface KbDocVO {
  docId: string
  kbId: string
  docName: string
  fileSize?: number
  contentType?: string
  charCount?: number
  chunkCount?: number
  state: 'pending' | 'processing' | 'ready' | 'failed'
  errorMessage?: string
  createdAt?: number
  updatedAt?: number
}

export interface KbChunkVO {
  chunkId: string
  userId?: string
  kbId?: string
  docId?: string
  docName?: string
  seq?: number
  content: string
  score?: number
  createdAt?: number
}

export interface KbSearchParams {
  keyword?: string
  kbId?: string
  page?: number
  size?: number
}

/* ===== 对话入参 ===== */

/** 运行期引用：输入框 @ 选中的上下文对象 */
export interface RunRefREQ {
  type: 'kb' | 'file' | 'doc'
  id: string
  label?: string
}

export interface ChatStreamREQ {
  sessionId: string
  content: string
  /** 输入框 / 点名的技能码，命中即强制展开 */
  skillCodes?: string[]
  /** 输入框 @ 引用的上下文对象 */
  refs?: RunRefREQ[]
}

export interface InvokeToolREQ {
  args: Record<string, unknown>
  confirmed?: boolean
  timeoutSeconds?: number
}

export interface SessionPageREQ extends PageREQ {
  sessionId?: string
  /** 运行状态过滤，运行记录页使用 */
  state?: string
  /** 起始时间（毫秒），运行记录页使用 */
  from?: number
  /** 结束时间（毫秒），运行记录页使用 */
  to?: number
}

/* ===== 创作文档 ===== */

export interface DocVO {
  docId: string
  userId?: string
  docTitle: string
  docType?: 'markdown' | 'rich' | 'code'
  category?: string
  tags?: string
  summary?: string
  content?: string
  wordCount?: number
  versionNo?: number
  sourceKbId?: string
  state?: 'draft' | 'published' | 'archived'
  createdAt?: number
  updatedAt?: number
}

export interface DocREQ {
  docId?: string
  docTitle: string
  docType?: string
  category?: string
  tags?: string
  summary?: string
  content?: string
  sourceKbId?: string
  state?: string
  changeNote?: string
}

export interface DocVersionVO {
  versionId: string
  docId: string
  versionNo?: number
  docTitle?: string
  content?: string
  changeNote?: string
  createdAt?: number
}

export interface DocAssistREQ {
  docId?: string
  action: string
  selection?: string
  instruction?: string
  kbId?: string
}

/** 辅助写作 SSE 帧：type 与载荷同层，一次解析 */
export interface AssistEvent {
  type: 'assist.start' | 'assist.delta' | 'assist.end' | 'assist.error'
  action?: string
  text?: string
  durationMs?: number
  code?: string
  message?: string
}

export interface DocSearchParams extends PageREQ {
  category?: string
}
