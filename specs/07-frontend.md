# 07 · 前端外壳

## 定位

单页应用，把运行状态渲染出来、把用户意图送进去、把系统健康状况摆出来。边界：所有数据经 `src/api/index.ts` 进出，组件不直接发请求；菜单与按钮权限由后端下发，前端只负责渲染与守卫。

不回答：技术栈与版本、按钮五态与色值令牌（见 [DESIGN.md](../DESIGN.md) 与 `frontend/package.json`）；工程约定（组件命名、i18n、三态、权限码来源）见 AGENTS.md §12；接口形状见 docs/API-CONTRACT.md。

## 设计

### 目录

| 目录 | 内容 |
|---|---|
| `router/` | 静态路由 + 后端菜单驱动的动态注册 + 登录与权限守卫 |
| `views/` | 路由页：`Login` / `ForcePassword` / `Dashboard`、`console/`、`runs/`、`writer/`、`files/`、`skills/`、`kb/`、`logs/`、`monitor/`、`settings/`、`system/`、`showcase/`、`error/`(403/404/500) |
| `components/layout/` | 外壳与导航：`AppShell`、`TheSideBar`（桌面 208px / 64px 图标栏，移动抽屉）、`TheSideMenu`（折叠态切换）、`TheTopBar`（折叠按钮 + 面包屑 + 用户区）、`TheBreadcrumb`、`icons.ts`（图标按需具名引入） |
| `components/ui/` | 基础件：`LwBatchBar`、`LwButton`、`LwCard`、`LwEmpty`、`LwErrorBar`、`LwErrorPage`、`LwMarkdown`、`LwMetricCard`、`LwPageState`、`LwProgressBar`、`LwRowActions`、`LwSegmented`、`LwSkeleton`、`LwTableCard`（含卡头与工具栏）、`LwTag` |
| `components/charts/` | 图表封装：`LwAreaChart`、`LwBarChart`、`LwDonutChart`（色值经 `utils/theme.ts` 读令牌） |
| `components/console/` | 工作台业务件：`ConsoleQuickStart`、`MessageComposer`、`PlanChecklist`、`ThinkingBlock`、`ToolStepCard` |
| `components/runs/` | 运行记录业务件：`TraceWaterfall`、`RunTree`、`UsagePanel` |
| `components/settings/` | 设置业务件：`ProviderPanel`、`ToolPanel`、`MemoryPanel` |
| `components/monitor/` | 监控业务件：`ObservePanel`（调用观测：指标卡 + 慢 SQL / 慢接口榜 + 定时刷新 + 重置窗口） |
| `stores/` | setup 风格 store：`user`、`session`、`run`、`monitor`、`app` |
| `composables/` | `useRunStream`（SSE 订阅）、`usePermission`（权限判断）、`useRunStatus`（运行状态色调与文案唯一实现）、`useBreadcrumb`（沿菜单树回溯祖先链） |
| `directives/` | `perm.ts`：`v-perm` 指令，无权限即从 DOM 移除元素 |
| `api/` | `index.ts` 唯一请求出口（HTTP 封装、令牌、SSE 解析、按域分组的方法表） |
| `plugins/` | `element-plus.ts`：组件与图标按需注册、Element Plus 语言包与应用语言联动（`useElementPlusLocale`） |
| `utils/` | `crypto.ts`（公钥与加密）、`theme.ts`（图表取令牌）、`menu.ts`（`menuLabel()`）、`role.ts`（角色标签唯一实现）、`markdown.ts`、`const.ts`（角色码 / 用户状态 / 菜单类型 / 时间格式化） |
| `types/` | `api.ts` 与后端 VO 对齐的接口类型 |
| `locales/` | 中英双语资源与 i18n 实例 |
| `styles/` | `tokens.css` 唯一色值来源、`index.css` 全局样式、`admin.css` 管理列表页骨架（页头 / 筛选条 / 表格卡 / 分页 / 单元格字体档） |

### 路由

静态路由仅 `/login`、`/force-password`、外壳 `/`（`AppShell`，重定向 `/dashboard`）、错误页 `/403` `/500` 与兜底 `/:pathMatch(.*)*`；其余由后端 `menu/user-tree` 下发后 `router.addRoute('shell', ...)` 注册。路由为历史模式，启动时把 `/#/xxx` 形式的哈希入口（书签与手输习惯）重写为 `/xxx`，否则哈希会被当锚点忽略、用户被弹回仪表盘。

| 路径 | 页面 | 权限 |
|---|---|---|
| `/login` | 登录（密码 RSA 加密提交） | 公开 |
| `/force-password` | 强制改密（`pwdResetRequired` 时唯一可达页） | 登录 |
| `/dashboard` | 仪表盘 | 登录 |
| `/console` | 工作台（会话 + 运行时间线） | 登录 |
| `/runs` | 运行记录（列表 + 时间线/工具链/链路/Run 树/用量 + 回放） | 登录 |
| `/writer` | 创作台（文档编辑 + 版本 + AI 辅助） | 登录 |
| `/files` | 文件空间（含在线预览） | 登录 |
| `/skills` | 技能 | 登录 |
| `/kb` | 知识库 | 登录 |
| `/logs` | 日志中心（本人视角；`monitor:log:global` 可切全局） | `monitor:log:list` |
| `/monitor` | 健康监控 + 调用观测 | `monitor:health:list`（重置窗口另需 `monitor:observe:reset`） |
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
| `useMonitorStore` | 健康、仪表盘汇总、调用观测与 Python 运行时；`loadHealth` / `loadOverview` / `loadObserve` / `resetObserve` / `loadPython` / `reprobe`。观测区自带 `observeLoading` / `observeError`，不污染健康面板加载态 |
| `useAppStore` | 侧栏折叠（持久化 `lw-sidebar-collapsed`）、移动端抽屉、语言（持久化到 `lw-locale`） |

大对象一律 `shallowRef`，标量用 `ref`。

### 外壳与导航

| 部位 | 实现 |
|---|---|
| 侧栏折叠 | 桌面端在 `208px` 与 `64px` 图标栏间切换，状态在 `useAppStore.sidebarCollapsed` 并持久化；折叠由顶栏左侧按钮触发，折叠态下点目录会先展开侧栏再打开该目录 |
| 面包屑 | `useBreadcrumb` 在当前 `useUserStore.menus` 上按 `route.path` 定位，返回根→叶链条；链条长度 >1 才渲染，单级页面（如仪表盘）不渲染 |
| 表格工具栏 | 刷新 / 密度 / 全屏由 `LwTableCard` 自带：刷新触发 `retry`，密度三档 `default` / `medium` / `compact`（持久化 `lw-table-density`），全屏是卡片级 `position: fixed` 而非浏览器全屏 API（后者会吞掉 Esc 与弹窗） |
| 批量与筛选 | `LwBatchBar` 占筛选条下方位置（`batch` 插槽），未选中不占位；高级筛选行在 `filter` 插槽内用 `.lw-filterbar__adv` 折叠，开关按钮有生效条件时打点；行操作溢出收进 `LwRowActions` 的 `overflow` 插槽 |

### 国际化

文案分两层，缺一层就会出现「一会中文一会英文」：

| 层 | 内容 | 实现 |
|---|---|---|
| 应用文案 | 全部业务文案 | `locales/zh-CN.ts` 与 `locales/en-US.ts`，两套 key 严格一一对应；组件内一律 `t()`，禁止硬编码中文或英文 |
| 组件库文案 | 分页「共 N 条」、日期「选择日期」、表格「暂无数据」、弹窗确认/取消等 | `App.vue` 根组件套 `el-config-provider`，locale 由 `useElementPlusLocale()` 按 `useAppStore.locale` 返回 `zhCn` / `en` |

只做第一层时，应用语言切中文后 Element Plus 内置文案仍是英文——这是混显的唯一来源。`switchLocale()` 同时改 i18n 实例与 store，两层随之同步，无需重启。

### 工作台输入框

`components/console/MessageComposer.vue` 承担 `/` 与 `@` 两种唤出。**已选项以 chip 为准，不以正文文本为准**：选中后正文里的标记立即移除，结构化数据单独随请求提交，这是"看得见、点得掉"的前提。

| 触发 | 面板内容 | 选中后 | 随请求提交 |
|---|---|---|---|
| `/` | 内置命令（新建会话）+ 启用技能列表（`skillCode` + 摘要） | 技能 chip | `skillCodes: string[]` |
| `@` | 知识库 / 文件 / 创作文档三类（各按 `hasPerm` 决定是否拉取） | 引用 chip | `refs: [{type, id, label}]` |

- 触发符必须位于行首或空白之后，查询串不含空格才认为面板处于激活态；面板打开时才拉列表，不按 `/` 与 `@` 就不产生额外请求。
- 面板排序：`前缀命中 > 类型序（命令 → 技能 → 引用）> 字典序`，最多 8 条，保证三四个字符就能靠回车命中。
- 输入框自增高到上限后内部滚动；容器是单一圆角框（`--lw-field-border` + 聚焦光环），不再套一层内部输入边框。
- 顶边带 12px 拖拽持有把手：向上拉高输入区（上限跟随可视高，双击恢复自动高度），长文本不用挤在小框里滚。
- 附件：回形针胶囊上传到当前工作区桶；**Ctrl+V 直接粘贴截图**走同一条上传链路。一次最多 5 个，满额时胶囊置灰并在输入区下方给一行警示文案。
- 发送键是 34px 圆形描边键（`.lw-send`），流式中就地换成同尺寸错误色停止键，不产生布局跳动；右上角字数计数与发送键位于同一行尾。
- 视觉与键盘契约（含 chip 形态、发送/中断按钮、Esc 中断、↑ 历史回填、字数计数）见 [DESIGN.md §5.11](../DESIGN.md)。

输入框底部还有一条**模型 / 工作区切换条**（即点即换，改动由宿主持久化到会话），两枚 chip 是常驻控件而非悬浮出现：

| 控件 | 面板形态 | 选中行为 |
|---|---|---|
| 模型 chip | 两级级联面板：左列供应方（hover/focus 即切右列，当前会话所在供应方排最前并打勾），右列该供应方的默认模型（带上下文窗口读数与识图 / 工具能力图标） | 选中即 `update:model{providerCode, modelName}` 并 toast 确认；底部输入框可手输模型名覆盖供应方默认值 |
| 工作区 chip | 单列面板：工作区名 + 桶内对象数与占用，默认工作区带标记；底部可就地新建（`workspaceApi.create`）或跳管理页 | 选中即 `update:workspace(workspaceId)`，下一条消息的上传与 `@` 引用按新工作区取桶 |
| 附件按钮 | — | `fileApi.upload(file, undefined, workspaceId)`；上传成功落 file chip 随消息提交，chip 删除同步删对象 |

两枚 chip 与 `/` `@` `附件` 三枚胶囊共用底栏一行：chip 是带边框的五态控件（悬停放大 1.03、按下缩小 0.97、选中态主靛描边），胶囊是弱化的次级入口。面板外部按下、Esc、流式开始三者均会收口。宿主 `Console.vue` 把切换转成 `sessionApi.update` 落库（流式中锁定，不可切换）；就地新建工作区由 `workspace-created` 事件交回宿主刷新列表并立即切到新桶，用户不必离开当前对话。未配置任何供应方时模型 chip 显示引导文案并给出去设置页的入口。上传与工作区解析在服务端兜底（空 id 回落默认工作区），前端只做快捷切换。

日志中心（`views/logs/LogSearch.vue`）三层结构：概览条（级别计数 + 可钻取直方图）→ 筛选区（检索列 / 关键字 / 级别 / 快捷时间，显示列勾选控制日志流行内列显隐，更多筛选展开 logger / traceId / sessionId / userId / runId / 时间范围，行尾常驻一句匹配规则说明）→ 日志流（表头与数据行共用同一套 CSS Grid 栅格，列宽只定义一次故不会错位；窄屏逐级收起次要列）。

日志流行式展示的排版纪律：等宽字体、统一 12.5px 字号与 1.5 行高、时间与序号一律 `tabular-nums`；级别用 `LwTag`（INFO 主靛 / WARN 警示 / ERROR 错误）；正文单行省略，logger / traceId / sessionId 以左侧 1px 竖线分隔的行内 meta 跟随其后；行号按分页连续编号。点行展开的详情区给完整正文（`pre` 保留换行）、链路字段（`dl` 定义列表，字段名禁用色 + 值等宽）、复制 / 反查动作与异常栈（错误淡底 + 错误语义字）。

### 工作台时间线

`views/console/Console.vue` 只负责编排（会话选中、发送、中断、回放、滚动容器），时间线内部的三类块各自独立成件：

| 组件 | 职责 |
|---|---|
| `components/console/ThinkingBlock.vue` | 思考过程折叠块，默认折叠，流式期间自动展开 |
| `components/console/ToolStepCard.vue` | 工具调用卡，头部状态随 `phase` 在 执行中 / 完成 / 失败 间过渡，输出可折叠 |
| `components/console/PlanChecklist.vue` | 规划清单（`plan.updated` 是覆盖式快照） |

三者都由 `stores/run.ts` 的唯一 reducer 喂数，实时流与回放同路径。

**回合分组**：`Console.vue` 的 `turns` 计算属性把相邻的 assistant 片段折成一个回合——一次「提问 → 回答」是一整块，块间才用大间距断开；用户消息永不合合（相邻的两次提问必须是两块）。合并时 `tools` / `plan` 必须显式拷贝，否则会把 store 里的数组引用带回分组结果，合并会反向改写源数据。

助手回合渲染为「头像列 + 一根竖轨 + 内容体」：思考、计划、工具卡、正文、用量行全部挂在同一根 1px 竖线上，回放与实时视觉一致。正文是裸 markdown 不套气泡（气泡只用于用户消息），流式中末尾带闪烁光标，正文与过程都未到时给三颗跳动点 + 「正在组织回答」。

### 管理列表页

增删改查类页面（用户 / 角色 / 菜单 / 文件 / 技能）一律走 `components/ui/LwTableCard.vue`：一个资源页 = `.lw-page-head` 页头 + 一张表格卡；卡头（资源名 + 总条数 + 表格工具栏）、筛选条、表体、分页同属一张白卡，不拆成多张。表格语言（表头浅底、44px 行高与密度三档、单元格三档字体、操作列竖线分组、右对齐分页）全部在 `styles/admin.css`，组件不就地写这些样式；视觉规范见 [DESIGN.md §5.12](../DESIGN.md)。

**卡头只在传了 `title` 或用了默认插槽之外的 `toolbar` 插槽时渲染**：不是所有表格都需要抬头（嵌在侧栏里的表格没必要），但一旦成为独立资源页，卡头是必须的——它是工具栏的唯一落点。

**空态由 `empty` 布尔控制，不由 `emptyText` 是否为空控制**：`empty` 由调用方按数据源长度给出（`:empty="!list.length"`），`emptyText` 只提供文案。二者混用会让表体永远被空态占位 —— 后端返回了数据而前端依旧空白。页头是全页唯一主操作位，表格卡只承载筛选条 / 表体 / 分页，不再重复渲染标题与新建按钮。

权限码必须与后端 `PermConst` 逐字一致（`system:user:create` 而非 `system:user:add`）；按钮可见性由 `hasPerm()` 判定，后端 `@SaCheckPermission` 兜底。

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
// traceApi / memoryApi / toolApi / skillApi / fileApi / kbApi / docApi /
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

```ts
// components/console/MessageComposer.vue —— 唯一输入入口
defineModel<string>('modelValue')                 // 正文草稿，由宿主持有，发送成功后才清空
defineProps<{ streaming: boolean; replaying: boolean }>()
defineEmits<{
  send: [{ skillCodes: string[]; refs: RunRefREQ[]; content: string }]
  abort: []
  newSession: []
}>()
defineExpose({ focus: () => void; clear: () => void })
```

宿主的 `send` 必须在**流真正建立之后**才清空草稿——先清空会在建会话失败时丢掉用户输入；清空正文的同时一并清空 chip。

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
| 批量走逐条守门不回滚整批 | 一个账号越权不会让整批失败 | 调用方必须消费 `BatchResultVO` 并提示失败原因 |
| 最近运行单独三态 | 概览失败不会连坐最近运行区 | 多一个独立请求（5 条，代价可忽） |
| 全屏用卡片级 fixed | Esc 与弹窗行为可控 | 不能真占满操作系统屏幕 |
| 请求单一出口 + 统一拦截 | 令牌与错误处理只写一遍 | 组件不能各自定制请求行为 |
| i18n 全覆盖 | 可切换语言 | 文案不能直接写死 |

## 约束

- 组件内禁止硬编码色值（一律 `var(--lw-*)`）与中文（一律 i18n）；`/showcase` 设计样例页例外。
- 文案键在 `zh-CN` 与 `en-US` 必须成对：两侧键集合一致且模板 / 脚本引用的静态键必须存在，由 `npm run check:i18n`（`scripts/check-i18n.mjs`）校验，`npm run build` 会先跑它。菜单名 `sys_menu.menu_name` 存 i18n key，渲染统一走 `menuLabel()`。
- 大对象一律 `shallowRef`；所有请求走 `src/api/index.ts`；异步操作必须有 loading / error / empty 三态。
- 按钮一律使用 `LwButton`；破坏性操作（删除、停用、重置密码）必须二次确认。
- 权限控制统一走 `v-perm` 指令或 `hasPerm()`，权限码与后端 `PermConst` 保持一致。
- 动态路由必须在 `loadCurrent()` 成功后注册；登出与切换账号必须回收动态路由与用户状态。
