# 07 · 前端外壳

## 定位

单页应用，把运行状态渲染出来、把用户意图送进去、把系统健康状况摆出来。边界：所有数据经 `src/api/index.ts` 进出，组件不直接发请求；菜单与按钮权限由后端下发，前端只负责渲染与守卫。

不回答：技术栈与版本、按钮五态与色值令牌（见 [DESIGN.md](../DESIGN.md) 与 `frontend/package.json`）；工程约定（组件命名、i18n、三态、权限码来源）见 AGENTS.md §12；接口形状见 docs/API-CONTRACT.md。

## 设计

### 目录

| 目录 | 内容 |
|---|---|
| `router/` | 静态路由 + 后端菜单驱动的动态注册 + 登录与权限守卫 |
| `views/` | 路由页：`Login` / `ForcePassword` / `Dashboard`、`console/`、`runs/`、`writer/`、`prompts/`、`files/`、`skills/`、`kb/`、`logs/`、`monitor/`、`settings/`、`system/`、`showcase/`、`error/`(403/404/500) |
| `components/layout/` | 外壳与导航：`AppShell`、`TheSideBar`、`TheSideMenu`、`TheTopBar`、`icons.ts`（图标按需具名引入） |
| `components/ui/` | 基础件：`LwButton`、`LwCard`、`LwEmpty`、`LwErrorBar`、`LwErrorPage`、`LwMarkdown`、`LwPageState`、`LwProgressBar`、`LwSegmented`、`LwSkeleton`、`LwTag` |
| `components/charts/` | 图表封装：`LwAreaChart`、`LwBarChart`、`LwDonutChart`（色值经 `utils/theme.ts` 读令牌） |
| `components/console/` | 工作台业务件：`ConsoleQuickStart`、`PlanChecklist` |
| `components/runs/` | 运行记录业务件：`TraceWaterfall`、`RunTree`、`UsagePanel` |
| `components/settings/` | 设置业务件：`ProviderPanel`、`ToolPanel`、`MemoryPanel` |
| `stores/` | setup 风格 store：`user`、`session`、`run`、`monitor`、`app` |
| `composables/` | `useRunStream`（SSE 订阅）、`usePermission`（权限判断） |
| `directives/` | `perm.ts`：`v-perm` 指令，无权限即从 DOM 移除元素 |
| `api/` | `index.ts` 唯一请求出口（HTTP 封装、令牌、SSE 解析、按域分组的方法表） |
| `utils/` | `crypto.ts`（公钥与加密）、`theme.ts`（图表取令牌）、`menu.ts`（`menuLabel()`）、`markdown.ts`、`const.ts`（角色码 / 用户状态 / 菜单类型 / 时间格式化） |
| `types/` | `api.ts` 与后端 VO 对齐的接口类型 |
| `locales/` | 中英双语资源与 i18n 实例 |
| `styles/` | `tokens.css` 唯一色值来源、`index.css` 全局样式 |

### 路由

静态路由仅 `/login`、`/force-password`、外壳 `/`（`AppShell`，重定向 `/dashboard`）、错误页 `/403` `/500` 与兜底 `/:pathMatch(.*)*`；其余由后端 `menu/user-tree` 下发后 `router.addRoute('shell', ...)` 注册。

| 路径 | 页面 | 权限 |
|---|---|---|
| `/login` | 登录（密码 RSA 加密提交） | 公开 |
| `/force-password` | 强制改密（`pwdResetRequired` 时唯一可达页） | 登录 |
| `/dashboard` | 仪表盘 | 登录 |
| `/console` | 工作台（会话 + 运行时间线） | 登录 |
| `/runs` | 运行记录（列表 + 时间线/工具链/链路/Run 树/用量 + 回放） | 登录 |
| `/writer` | 创作台（文档编辑 + 版本 + AI 辅助） | 登录 |
| `/prompt` | 提示词库 | 登录 |
| `/files` | 文件空间（含在线预览） | 登录 |
| `/skills` | 技能 | 登录 |
| `/kb` | 知识库 | 登录 |
| `/logs` | 日志中心（本人视角；`monitor:log:global` 可切全局） | `monitor:log:list` |
| `/monitor` | 健康监控 | `monitor:health:list` |
| `/settings` | 设置（账户安全 + 平台级分区） | 登录 |
| `/system` | 系统管理目录 | — |
| `/system/user` | 用户管理 | `system:user:list` |
| `/system/role` | 角色管理 | `system:role:list` |
| `/system/menu` | 菜单管理 | `system:menu:list` |
| `/showcase` | 组件库 | 登录 |
| `/403` `/500` `/404` | 无权限 / 服务异常 / 不存在 | 公开 |

动态注册把 `menu.component` 映射到 `views/` 下的文件（`import.meta.glob('../views/**/*.vue')`），路由 `name` 用 `menuId`、`meta.perm` 用 `permCode`；仅 `menuType = 'menu'` 且带 `routePath`、`component` 的节点注册。已注册的 `menuId` 记入模块级集合，登出与切换账号时回收。设置页内嵌三个平台级分区，按权限点显示：模型供应方（`wb:provider:*`）、工具管理（`wb:tool:*`）与长期记忆，普通用户看不到前两个。

**守卫**（`router.beforeEach`）：

- 未登录：回收动态路由；公开页放行，其余跳 `/login` 并带 `redirect`。
- 已登录首次进入：`store.loaded` 为假时 `loadCurrent()` 拉当前用户，失败即重置并回 `/login`。
- 动态路由未注册：注册后以 `replace` 重进当前地址（否则目标地址会命中兜底 404）。
- 首登强制改密：`pwdResetRequired` 为真只允许停在 `/force-password`，为假则不允许再进入该页。
- 权限：`meta.perm` 不在 `store.perms` 内即跳 `/403`。

### 状态

| Store | 内容 |
|---|---|
| `useUserStore` | 令牌、用户信息、角色码、权限标识、菜单树、`pwdResetRequired`、`loaded`；`login`（本地加密后提交）、`loadCurrent`、`logout`、`reset` |
| `useSessionStore` | 会话列表、当前会话、消息与三个加载态（`loading` / `loadingMessages` / `error`） |
| `useRunStore` | 运行列表、唯一时间线 reducer（`timeline`）、`streaming` / `replaying` / `replayRunId` / `currentRunId` / `errorDetail`；`applyEvent`（reducer）、`loadTimeline`、`beginStream`、`replay`、`backToLive` |
| `useMonitorStore` | 健康、仪表盘汇总与 Python 运行时；`loadHealth` / `loadOverview` / `loadPython` / `reprobe` |
| `useAppStore` | 侧栏折叠、移动端抽屉、语言（持久化到 `lw-locale`） |

大对象一律 `shallowRef`，标量用 `ref`。

## 契约

```ts
// src/api/index.ts —— 唯一出口
get<T>(url, params): Promise<T>        // GET /api/v1/...
post<T>(url, body, config?): Promise<T> // POST 写操作，可带 params / headers / timeout
// 请求拦截：注入 Authorization（裸令牌，读 lw-token）
// 响应拦截：code !== '0' 弹提示并 reject；401 清令牌跳 /login；403 弹无权限提示（不跳转）
// request 负责解包 response.data.data

// 令牌
getToken(): string
setToken(token: string): void
clearToken(): void

// 按域分组的方法表：authApi / userApi / roleApi / menuApi / sessionApi / runApi /
// traceApi / memoryApi / toolApi / skillApi / fileApi / kbApi / docApi / promptApi /
// providerApi / monitorApi / logApi

// 权限：模板用 v-perm 或 hasPerm
const { hasPerm, hasRole } = usePermission()

// 流式对话：POST /chat/stream 以 fetch 发起，读 ReadableStream 解析 SSE 帧
// （需携带 Authorization 头，故不用原生 EventSource）
const stream = useRunStream()
stream.start({ sessionId, content }, {
  onEvent: (e) => runStore.applyEvent(e),        // e 为 RunEvent，含 seq/eventType/payload
  onEnd: (aborted) => runStore.finishStream(aborted),
  onError: (msg) => runStore.failStream(msg),
})
stream.dispose()                                 // 断开本地消费；服务端由 /run/abort 收尾

// 辅助写作：POST /doc/assist/stream，事件为 assist.delta / assist.end / assist.error
const stopAssist = openAssistStream(body, { onDelta, onEnd, onError })

// 回放：GET /run/events/{runId} 按 seq 拉全量事件，走同一 reducer 重建时间线
await runStore.replay(runId)
```

`openRunStream` 处理 SSE 帧时按类型分流：`run.snapshot` 的 `watermark` 大于本地已投递最大 `seq` 时按 `afterSeq` 从 `/run/events` 补齐丢帧（补齐失败不阻断实时流）；`run.end` 取 `payload.status === 'aborted'` 作为中断标记；`error` 帧转 `onError`。返回一个 `AbortController.abort` 包装的中断函数。

按钮五态、`LwButton` 唯一实现与组件库按钮的全局覆盖规则见 [DESIGN.md](../DESIGN.md)。

## 关键流程

```
打开应用
  ├─ 有 token → GET /auth/current → 存用户/权限/菜单 → addRoute 动态注册
  ├─ 无 token → 跳 /login
  └─ 登录成功 → 同上有 token 分支

登录
  ├─ 进入页面取公钥（失败即报"安全组件加载失败"，绝不降级明文）
  ├─ 提交 → crypto.encryptSecret(password) → POST /auth/login { username, password:密文, keyId }
  ├─ pwdResetRequired = true → 跳 /force-password，改密成功后强制重登
  └─ 否则 → 按 redirect 或 /dashboard 进入外壳

工作台
  ├─ 加载会话列表（骨架屏）→ 选中会话拉消息与最近运行
  ├─ 输入发送 → openRunStream 建立 SSE → 逐帧 applyEvent 到 runStore
  ├─ 中断按钮 → POST /run/abort/{runId} → 收到 run.end
  └─ 刷新页面 → GET /run/events/{runId} 回放

运行记录
  ├─ 列表：GET /run/page（状态过滤 + 分页，行显示状态 Tag、模型、耗时、Token）
  ├─ 详情：GET /run/detail/{runId} + GET /run/events/{runId}
  ├─ 分段切换：时间线 / 工具链 / 链路 / Run 树 / 用量 → traceApi.spans / traceApi.tree / traceApi.usage
  ├─ 回放：本地游标遍历已加载块（0.5x–4x），不重发请求
  └─ 中断：非终态运行可 abort，状态变化就地刷新

创作台
  ├─ 左列文档列表（关键字走 /doc/search，否则 /doc/page）
  ├─ 保存 → /doc/update（写版本 + 同步索引）→ 提示已保存
  ├─ AI 辅助 → /doc/assist/stream → 增量渲染，可中途停止
  └─ 版本历史 → /doc/version/page → 回滚即再走一次保存

日志中心
  ├─ 进入即拉 /log/stats（近 24 小时）渲染概览卡与直方图，带视角标识
  ├─ 过滤：关键字 + 字段（message/logger/traceId）+ 级别 + logger + traceId + 时间预设
  ├─ 全局视角（monitor:log:global）：增加 userId / runId 过滤与行内钻取
  └─ 查询 → /log/search → 结果行内高亮，点击展开异常栈
```

## 取舍

| 选择 | 换来什么 | 代价 |
|---|---|---|
| 后端下发菜单 + 前端动态注册 | 权限改动无需发版 | 首屏多一次请求，路由注册异步（未注册前命中兜底 404，须以 `replace` 重进） |
| 事件流单一 reducer | 渲染逻辑集中，回放与实时同路径 | reducer 需覆盖全部事件类型 |
| 流式用 fetch 手解 SSE 帧 | 可携带 `Authorization` 头、可中断 | 自己维护分帧、丢帧补齐与终态判定 |
| 样式令牌集中一处 | 改色只改一个文件 | 组件不能就地写色值 |
| 请求单一出口 + 统一拦截 | 令牌与错误处理只写一遍 | 组件不能各自定制请求行为 |
| i18n 全覆盖 | 可切换语言 | 文案不能直接写死 |

## 约束

- 组件内禁止硬编码色值（一律 `var(--lw-*)`）与中文（一律 i18n）；`/showcase` 设计样例页例外。
- 大对象一律 `shallowRef`；所有请求走 `src/api/index.ts`；异步操作必须有 loading / error / empty 三态。
- 按钮一律使用 `LwButton`；破坏性操作（删除、停用、重置密码）必须二次确认。
- 权限控制统一走 `v-perm` 指令或 `hasPerm()`，权限码与后端 `PermConst` 保持一致。
- 动态路由必须在 `loadCurrent()` 成功后注册；登出与切换账号必须回收动态路由与用户状态。
