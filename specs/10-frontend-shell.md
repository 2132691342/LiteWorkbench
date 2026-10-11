# 10 · 前端外壳

## 定位

前端是单页应用，负责三件事：把运行状态渲染出来、把用户意图送进去、把系统健康状况摆出来。

边界：所有数据经 `src/api/index.ts` 进出，组件不直接发请求；菜单与按钮权限由后端下发，前端只负责渲染与守卫。

## 设计

### 技术栈

| 领域 | 选型 | 用途 |
|---|---|---|
| 核心 | Vue 3.4.34 + TypeScript 5.4.5 + Vite 5.2.12 | 框架与构建 |
| UI | Element Plus 2.9.0 + UnoCSS 0.58.6 | 控件与原子样式 |
| 状态 / 路由 | Pinia 2.1.7 + Vue Router 4.3.2 | 状态与动态路由 |
| 请求 | Axios 1.6.8 | 唯一出口封装 |
| 可视化 | ECharts 5.5.0、AntV X6 2.18.1 | 图表与流程编排 |
| 工作流 | BPMN-JS 16.4.0 | 流程建模页（预置） |
| 富文本 | WangEditor 5.1.23、Mavon-Editor、CodeMirror 6 | 内容创作与代码编辑 |
| 文件处理 | XLSX、docx-preview、vue-pdf-embed | 产物预览 |
| 微前端 | Qiankun 2.10.16 | 预留子应用挂载点 |
| 国际化 | Vue I18n 9.10.2 | 中英双语 |

### 目录

| 目录 | 内容 |
|---|---|
| `views/` | 路由页：登录、仪表盘、工作台、文件、技能、日志、监控、设置、系统管理、组件库 |
| `components/layout/` | 顶栏、侧栏、外壳 |
| `components/ui/` | 基础件：`LwButton`、`LwCard`、`LwEmpty`、`LwSkeleton`、`LwErrorBar`、`LwTag` |
| `components/charts/` | ECharts 封装（面积图、环形图、柱状图） |
| `stores/` | Pinia setup store |
| `composables/` | 复用逻辑：SSE 订阅、分页、Toast、权限判断 |
| `styles/tokens.css` | 唯一色值来源 |
| `locales/` | 中英双语 |

### 路由

| 路径 | 页面 | 权限 |
|---|---|---|
| `/login` | 登录 | 公开 |
| `/dashboard` | 仪表盘 | 登录 |
| `/console` | 工作台（会话 + 运行时间线） | 登录 |
| `/files` | 文件空间 | 登录 |
| `/skills` | 技能 | 登录 |
| `/logs` | 日志中心 | `monitor:log:list` |
| `/monitor` | 健康监控 | `monitor:health:list` |
| `/settings` | 设置 | 登录 |
| `/system/user` | 用户管理 | `system:user:list` |
| `/system/role` | 角色管理 | `system:role:list` |
| `/system/menu` | 菜单管理 | `system:menu:list` |
| `/showcase` | 组件库 | 登录 |

路由分静态（登录、404）与动态（其余，含 `/showcase` 组件库演示页，后端 `menu/user-tree` 下发后 `router.addRoute` 注册）。

### 状态

| Store | 内容 |
|---|---|
| `useUserStore` | 令牌、用户信息、角色码、权限标识、菜单树 |
| `useSessionStore` | 会话列表与当前会话 |
| `useRunStore` | 运行事件流与流式缓冲 |
| `useMonitorStore` | 健康状态 |
| `useAppStore` | 主题、语言、侧栏折叠 |

大对象一律 `shallowRef`，标量用 `ref`。

## 契约

### 请求封装

```ts
// src/api/index.ts —— 唯一出口
request.get<T>(url, params)     // GET /api/v1/...
request.post<T>(url, body)
// 拦截器职责：注入 Authorization、401 跳登录、403 提示无权限、统一 Toast
```

### 权限

```ts
const { hasPerm, hasRole } = usePermission()
// 模板：<LwButton v-if="hasPerm('system:user:add')">新建</LwButton>
```

### SSE 订阅

```ts
// 流式对话：POST /chat/stream 以 fetch 发起，读取 ReadableStream 解析 SSE 帧
// （携带 Authorization 头，故不用原生 EventSource）
useRunStream({
  onEvent: (e) => runStore.apply(e),   // e 为 RunEvent，含 seq/type/payload
  onEnd:   () => runStore.finish(),
  onError: (err) => toast.error(err),
})
// 回放：GET /run/events/{runId} 按 seq 拉全量事件走同一 reducer
```

### 按钮五态

见 [DESIGN.md](../DESIGN.md#6-按钮五态)。全站按钮唯一实现 `LwButton.vue`，必须实现 `default / hover / pressed / disabled / loading`，hover 放大、pressed 缩小，加载时宽度锁定。

## 关键流程

```
打开应用
  ├─ 有 token → GET /auth/current → 存用户/权限/菜单 → addRoute 动态注册
  ├─ 无 token → 跳 /login
  └─ 登录成功 → 同上有 token 分支

工作台
  ├─ 加载会话列表（骨架屏）
  ├─ 选中会话 → 拉消息与最近运行
  ├─ 输入发送 → 建立 SSE → 逐帧 apply 到 runStore
  ├─ 中断按钮 → POST /run/abort/{runId} → 收到 run.end
  └─ 刷新页面 → 按 runId 拉事件回放
```

## 取舍

| 选择 | 换来什么 | 代价 |
|---|---|---|
| 后端下发菜单 + 前端动态注册 | 权限改动无需发版 | 首屏多一次请求，路由注册异步 |
| 事件流单一 reducer | 渲染逻辑集中，回放与实时同路径 | reducer 需覆盖全部事件类型 |
| 样式令牌集中一处 | 改色只改一个文件 | 组件不能就地写色值 |
| 自研 LwButton | 五态与动效完全可控 | 不能直接吃 Element Plus 的按钮生态 |
| 不引入组件库的图表 | ECharts 自主可控 | 需自己封装一层 |
| i18n 全覆盖 | 可切换语言 | 文案不能直接写死 |

## 约束

- 组件内禁止硬编码色值，一律用 `var(--lw-*)`。
- 组件内禁止硬编码中文，一律走 i18n。
- 所有请求走 `src/api/index.ts`；异步操作必须有 loading / error / empty 三态。
- 按钮一律使用 `LwButton`，`el-button` 仅允许出现在 Element Plus 内部组件中。
- 破坏性操作（删除、停用、重置密码）必须二次确认。
