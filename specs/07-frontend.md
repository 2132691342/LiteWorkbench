# 07 · 前端外壳

## 定位

单页应用，把运行状态渲染出来、把用户意图送进去、把系统健康状况摆出来。

边界：所有数据经 `src/api/index.ts` 进出，组件不直接发请求；菜单与按钮权限由后端下发，前端只负责渲染与守卫。

## 设计

### 技术栈

| 领域 | 选型 | 用途 |
|---|---|---|
| 核心 | Vue 3.4 + TypeScript 5.4 + Vite 5.2 | 框架与构建 |
| UI | Element Plus 2.9 + UnoCSS 0.58 | 控件与原子样式 |
| 状态 / 路由 | Pinia 2.1 + Vue Router 4.3 | 状态与动态路由 |
| 请求 | Axios 1.6 | 唯一出口封装 |
| 可视化 | ECharts 5.5 | 图表，色值经 `utils/theme.ts` 读令牌 |
| 富文本 | WangEditor 5.1 | 创作台富文本模式 |
| 文件处理 | XLSX、docx-preview、vue-pdf-embed | 文件空间在线预览，按需异步加载 |
| 国际化 | Vue I18n 9.10 | 中英双语，菜单标签经 `menuLabel()` 兜底 |
| 安全 | JSEncrypt | 登录密码 `RSA/ECB/PKCS1Padding` 加密 |
| 渲染 | markdown-it + highlight.js | 会话消息 Markdown 与代码高亮 |

### 目录

| 目录 | 内容 |
|---|---|
| `views/` | 路由页：登录、仪表盘、工作台、运行记录、创作台、提示词、文件、技能、知识库、日志、监控、设置、系统管理、组件库、错误页（403/404/500） |
| `components/layout/` | 顶栏、侧栏、外壳 |
| `components/ui/` | 基础件：`LwButton`、`LwCard`、`LwEmpty`、`LwSkeleton`、`LwErrorBar`、`LwTag`、`LwPageState`、`LwMarkdown`、`LwProgressBar`、`LwSegmented`、`LwErrorPage` |
| `components/charts/` | ECharts 封装（面积图、环形图、柱状图） |
| `components/<domain>/` | 业务件：`console/`、`settings/`（供应方、工具面板） |
| `stores/` | Pinia setup store |
| `composables/` | `useRunStream`（SSE 订阅）、`usePermission`（权限判断） |
| `directives/` | `v-perm`：无权限即移除元素 |
| `styles/tokens.css` | 唯一色值来源，规范见 [DESIGN.md](../DESIGN.md) |
| `locales/` | 中英双语 |
| `utils/` | `crypto.ts`（公钥与加密）、`theme.ts`（图表取令牌）、`menu.ts`（`menuLabel()`）、`markdown.ts`、`const.ts` |

### 路由

| 路径 | 页面 | 权限 |
|---|---|---|
| `/login` | 登录（密码 RSA 加密提交） | 公开 |
| `/force-password` | 强制改密（`pwdResetRequired = true` 时唯一可达页） | 登录 |
| `/dashboard` | 仪表盘 | 登录 |
| `/console` | 工作台（会话 + 运行时间线） | 登录 |
| `/runs` | 运行记录（列表 + 时间线/工具链/用量 + 回放） | 登录 |
| `/writer` | 创作台（文档编辑 + 版本 + AI 辅助） | 登录 |
| `/prompt` | 提示词库 | 登录 |
| `/files` | 文件空间（含在线预览） | 登录 |
| `/skills` | 技能 | 登录 |
| `/kb` | 知识库 | 登录 |
| `/logs` | 日志中心（本人视角；`monitor:log:global` 可切全局） | 登录 |
| `/monitor` | 健康监控 | `monitor:health:list` |
| `/settings` | 设置（账户安全 + 平台级分区） | 登录 |
| `/system/user` | 用户管理 | `system:user:list` |
| `/system/role` | 角色管理 | `system:role:list` |
| `/system/menu` | 菜单管理 | `system:menu:list` |
| `/showcase` | 组件库 | 登录 |
| `/403` `/500` `/404` | 无权限 / 服务异常 / 不存在 | 公开 |

路由分静态与动态：静态仅 `/login`、`/force-password`、外壳 `/`（重定向 `/dashboard`）、错误页与兜底 404；其余由 `menu/user-tree` 下发后 `router.addRoute` 注册，`component` 字段映射到 `views/` 下的文件，`meta.perm` 不满足时跳 `/403`。设置页内嵌两个平台级分区，按权限点显示：模型供应方（`wb:provider:*`）与工具管理（`wb:tool:*`），普通用户看不到。

### 状态

| Store | 内容 |
|---|---|
| `useUserStore` | 令牌、用户信息、角色码、权限标识、菜单树 |
| `useSessionStore` | 会话列表与当前会话 |
| `useRunStore` | 运行事件流与流式缓冲 |
| `useMonitorStore` | 健康状态与仪表盘汇总 |
| `useAppStore` | 语言、侧栏折叠状态 |

大对象一律 `shallowRef`，标量用 `ref`。

## 契约

```ts
// src/api/index.ts —— 唯一出口
request.get<T>(url, params)     // GET /api/v1/...
request.post<T>(url, body)
// 拦截器职责：注入 Authorization、401 跳登录、403 跳错误页、统一 Toast

// 权限：模板用 v-perm 或 hasPerm
const { hasPerm, hasRole } = usePermission()

// 流式对话：POST /chat/stream 以 fetch 发起，读 ReadableStream 解析 SSE 帧
// （携带 Authorization 头，故不用原生 EventSource）
const runStream = useRunStream()
runStream.start({ sessionId, content }, {
  onEvent: (e) => runStore.applyEvent(e),        // e 为 RunEvent，含 seq/type/payload
  onEnd: (aborted) => runStore.finishStream(aborted),
  onError: (err) => runStore.failStream(err),
})

// 回放：GET /run/events/{runId} 按 seq 拉全量事件，走同一 reducer 重建时间线
await runStore.replay(runId)
```

按钮五态（`default / hover / pressed / disabled / loading`，hover 放大、pressed 缩小、loading 锁宽）见 [DESIGN.md](../DESIGN.md)；全站按钮唯一实现 `LwButton.vue`，Element Plus 自带按钮由全局样式覆盖为同等五态。

## 关键流程

```
打开应用
  ├─ 有 token → GET /auth/current → 存用户/权限/菜单 → addRoute 动态注册
  ├─ 无 token → 跳 /login
  └─ 登录成功 → 同上有 token 分支

登录
  ├─ 进入页面即 GET /auth/public-key（失败即报"安全组件加载失败"，绝不降级明文）
  ├─ 提交 → crypto.encrypt(password) → POST /auth/login { username, password:密文, keyId }
  ├─ pwdResetRequired = true → 跳 /force-password，改密成功后强制重登
  └─ 否则 → 按 redirect 或 /dashboard 进入外壳

工作台
  ├─ 加载会话列表（骨架屏）→ 选中会话拉消息与最近运行
  ├─ 输入发送 → 建立 SSE → 逐帧 apply 到 runStore
  ├─ 中断按钮 → POST /run/abort/{runId} → 收到 run.end
  └─ 刷新页面 → 按 runId 拉事件回放

运行记录
  ├─ 列表：GET /run/page（状态过滤 + 分页，行显示状态 Tag、模型、耗时、Token）
  ├─ 详情：GET /run/detail/{runId} + GET /run/events/{runId}
  ├─ 回放：本地游标遍历已加载块（0.5x–4x），不重发请求
  └─ 中断：非终态运行可 abort，状态变化就地刷新

创作台
  ├─ 左列文档列表（关键字走 /doc/search，否则 /doc/page）
  ├─ 保存 → /doc/update（写版本 + 同步索引）→ 提示已保存
  ├─ AI 辅助 → /doc/assist/stream → 增量渲染，可中途停止，结果插入 / 替换选区 / 复制
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
| 后端下发菜单 + 前端动态注册 | 权限改动无需发版 | 首屏多一次请求，路由注册异步 |
| 事件流单一 reducer | 渲染逻辑集中，回放与实时同路径 | reducer 需覆盖全部事件类型 |
| 样式令牌集中一处 | 改色只改一个文件 | 组件不能就地写色值 |
| 自研 LwButton | 五态与动效完全可控 | 不能直接吃 Element Plus 的按钮生态 |
| 图表用 ECharts 自封装 | 自主可控、令牌驱动 | 需自己维护一层封装 |
| i18n 全覆盖 | 可切换语言 | 文案不能直接写死 |

## 约束

- 组件内禁止硬编码色值（一律 `var(--lw-*)`）与中文（一律 i18n）；`/showcase` 设计样例页例外。
- 大对象一律 `shallowRef`；所有请求走 `src/api/index.ts`；异步操作必须有 loading / error / empty 三态。
- 按钮一律使用 `LwButton`；破坏性操作（删除、停用、重置密码）必须二次确认。
- 权限控制统一走 `v-perm` 指令或 `hasPerm()`，权限码与后端 `PermConst` 保持一致。
