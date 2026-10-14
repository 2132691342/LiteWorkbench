# DESIGN.md

## 定位

本文回答前端**长什么样、怎么动**：设计令牌、语义色、排版间距、布局骨架、组件形态与交互状态、页面范式与禁止清单。

不回答前端工程结构（技术栈、目录、路由、状态管理、请求封装与流式管线），后者见 [specs/07-frontend.md](specs/07-frontend.md)。

实现以本文为准；色值只允许出现在 `frontend/src/styles/tokens.css`。

---

## 1. 设计原则

**设计哲学：秩序紫（Ordered Violet）。** 一个只靠背景差、1px 边框与两级阴影建立层级的浅色系统。四色紫序列承担全部信息编码——深紫是文字与确认动作，浅紫是选中态与数据序列，最浅的一档只做底纹。所有装饰（渐变堆叠、插画、大图）一律让位给两枚弥散光斑与真实数据本身；界面里唯一被允许"动"的，是状态变化：悬停放大、按下缩小、加载锁宽。判断一个元素该不该存在的标准是它是否替用户省掉一次点击或一次犹豫，不能就删掉。

| 原则 | 落地方式 |
|---|---|
| 极简 | 无装饰性渐变与阴影堆叠，层级靠背景差与 1px 边框建立 |
| 克制配色 | 四色紫色序列走完全站，语义色只在 success / warning / error（含 info）出现 |
| 可预期 | 所有可点击元素有五态，所有异步操作有三态 |
| 有反馈 | 悬停、按下、加载、成功、失败均有过渡动画，无静默操作 |

## 2. 设计令牌

### 2.1 品牌四色序列

```css
:root {
  --purple_sequential-color-1: #f2f0f7;   /* 浅底 */
  --purple_sequential-color-2: #cbc9e2;   /* 辅助浅紫 */
  --purple_sequential-color-3: #9e9ac8;   /* 辅助紫 */
  --purple_sequential-color-4: #6a51a3;   /* 主紫 */
}
```

### 2.2 语义别名

业务与组件只用别名，不直接引用四色序列。

| 别名 | 取值 | 用途 |
|---|---|---|
| `--lw-primary` | `var(--purple_sequential-color-4)` | 标题、正文、主按钮底、图表默认序列 |
| `--lw-primary-hover` | `#5a448c` | 主色悬停 |
| `--lw-primary-active` | `#4a3a73` | 主色按下 |
| `--lw-primary-tint` | `rgba(106,81,163,.08)` | 主色淡底 |
| `--lw-secondary` | `var(--purple_sequential-color-3)` | 侧栏选中、图表次序列、强调底色 |
| `--lw-secondary-hover` | `#8a85b8` | 辅助紫悬停 |
| `--lw-secondary-tint` | `rgba(158,154,200,.14)` | 辅助紫淡底 |
| `--lw-secondary-selected` | `rgba(158,154,200,.1)` | 侧栏选中底 |
| `--lw-accent-soft` | `var(--purple_sequential-color-2)` | 图表第三序列、分隔强调 |
| `--lw-surface-subtle` | `var(--purple_sequential-color-1)` | 头像底、选中按钮底、品牌卡、思考块底 |
| `--lw-surface` | `#f8fafc` | 页面底色 |
| `--lw-surface-raised` | `#ffffff` | 卡片、侧栏、顶栏 |
| `--lw-surface-disabled` | `var(--purple_sequential-color-1)` | 禁用底 |
| `--lw-border` | `#e2e8f0` | 默认边框 |
| `--lw-border-strong` | `var(--purple_sequential-color-2)` | 强调边框 |
| `--lw-ink` | `var(--purple_sequential-color-4)` | 主文本 |
| `--lw-ink-muted` | `#64748b` | 次级文本 |
| `--lw-ink-disabled` | `#94a3b8` | 禁用文本 |
| `--lw-on-primary` | `#ffffff` | 深底之上的文字 |
| `--lw-on-secondary` | `#2e2536` | `--lw-secondary` 之上的文字 |
| `--lw-overlay` | `rgba(15,23,42,.45)` | 抽屉遮罩 |

布局尺寸同样以令牌表达：`--lw-topbar-h: 64px`、`--lw-sidebar-w: 208px`。

### 2.3 语义色与派生色

| 类别 | 令牌 | 取值 | 用途 |
|---|---|---|---|
| 语义底 | `--lw-success` | `#5abf8a` | 运行成功、校验通过 |
| | `--lw-warning` | `#d6a85c` | 需要确认、资源吃紧 |
| | `--lw-error` | `#d46a6a` | 运行失败、请求异常 |
| | `--lw-info` | `var(--lw-primary)` | 提示、信息 |
| 文字加深 | `--lw-success-ink` | `#3d8f62` | 成功语义文字 |
| | `--lw-warning-ink` | `#a97e33` | 警示语义文字 |
| | `--lw-error-ink` | `#b45555` | 错误语义文字 |
| 交互态 | `--lw-error-hover` | `#c25555` | 危险按钮悬停 |
| | `--lw-error-active` | `#a94747` | 危险按钮按下 |
| 淡底 | `--lw-success-tint` | `rgba(90,191,138,.14)` | 成功标签底 |
| | `--lw-warning-tint` | `rgba(214,168,92,.16)` | 警示标签底、待收尾行底 |
| | `--lw-error-tint` | `rgba(212,106,106,.14)` | 错误标签底、错误条底 |
| | `--lw-muted-tint` | `rgba(100,116,139,.12)` | 中性标签底、行内代码底 |
| EP 主题映射 | `--lw-primary-light-3/5/7/8` | `#9785bf` / `#b5a8d1` / `#d2cbe3` / `#e1dced` | 仅供 Element Plus 主题映射 |
| 骨架 | `--lw-skeleton-a` | `var(--purple_sequential-color-1)` | 骨架屏渐变起点 |

**规则**：语义色只做低饱和处理，避免与品牌紫抢注意力；文字态一律用 ink 变体加深，不直接用语义底。

### 2.4 阴影与动效

| 令牌 | 取值 | 用途 |
|---|---|---|
| `--lw-shadow-sm` | `0 1px 2px rgba(15,23,42,.06)` | 卡片静止 |
| `--lw-shadow-md` | `0 4px 12px rgba(15,23,42,.12)` | 浮层与悬停卡片 |
| `--lw-dur-fast` | `120ms` | 颜色、透明度切换 |
| `--lw-dur` | `200ms` | 位移、缩放、阴影 |
| `--lw-dur-slow` | `300ms` | 抽屉、弹窗、路由淡入 |
| `--lw-ease` | `cubic-bezier(.4,0,.2,1)` | 状态切换（颜色、阴影、缩放） |
| `--lw-ease-out` | `cubic-bezier(.16,1,.3,1)` | 入场（淡入上浮），末段减速更长 |

阴影仅两级，动效只允许引用上述五个令牌。

### 2.5 UnoCSS 语义类

UnoCSS 主题只映射令牌，组件禁止硬编码色值：颜色 `lw-<别名>`（如 `text-lw-ink`、`bg-lw-raised`、`border-lw-border`）、圆角 `rounded-card`(12px) / `rounded-control`(8px) / `rounded-tag`(6px)、阴影 `lw-shadow-sm` / `lw-shadow-md`；快捷类 `lw-card` / `lw-panel` / `lw-page` / `lw-page-head` / `lw-title` / `lw-subtitle` / `lw-label` / `lw-input` / `lw-table` / `lw-center`。

## 3. 排版与间距

| 项 | 规则 |
|---|---|
| 字号 | 页面标题 24 / 卡片标题 16 / 正文 14 / 辅助 12 / 徽标 10 |
| 字重 | 标题 700，正文 400，强调 600 |
| 圆角 | 卡片 12px，控件 8px，标签 6px，头像与徽章全圆 |
| 间距 | 4 的倍数：4 / 8 / 12 / 16 / 24 / 32 |
| 阴影 | 仅两级，见 §2.4 |

字体栈 `'Inter', 'PingFang SC', 'Microsoft YaHei', system-ui, -apple-system, sans-serif`，正文行高 1.6。

## 4. 布局骨架

```
┌──────────────────────────────────────────┐
│ TopBar  h-16(64px) 白底 下边框            │
├────────┬─────────────────────────────────┤
│ Sidebar│ Main                            │
│ w-52   │ lw-page: p-6 flex-col gap-6     │
│ (208px)│  ├ 页头（标题 + 副标题 + 操作区）│
│ 白底   │  ├ 指标卡网格（1/3 列）          │
│ 右边框 │  └ 内容区（图表 / 列表 / 时间线）│
└────────┴─────────────────────────────────┘
```

- 顶栏 `--lw-topbar-h`、白底 `--lw-surface-raised`、下边框 `--lw-border`、内边距 `px-4 md:px-6`。左侧品牌方块 32px、`--lw-secondary` 底 + `--lw-on-primary` 图标 + `--lw-shadow-sm`，后接产品名（18px 加粗）；右侧为语言切换（ghost sm）与用户区（昵称/角色 + 36px 圆形头像，`--lw-surface-subtle` 底 + `--lw-primary` 字 + `--lw-shadow-sm`，悬停放大 1.05）。
- 侧栏宽 `--lw-sidebar-w`、白底、右边框。菜单组标题 10px 大写 `--lw-ink-muted`（`px-3 mb-2`）；一级菜单项 `h-10` 圆角 8px，二级子项 `h-9`，悬停为黑色 5% 叠加，选中为 `--lw-primary` 文字 + 字重 600 + `--lw-secondary-selected` 底；目录展开箭头 200ms 旋转。
- 侧栏底部固定品牌卡：`--lw-secondary` 底 + `--lw-on-secondary` 字，12px 圆角 + `--lw-shadow-sm`，内叠一层 `--lw-on-primary` 10% 蒙版；内容为产品名（12px 加粗）、口号（10px）、`用户名 · 角色码`（10px 等宽）；悬停放大 1.02。
- 主区 `lw-page`（`p-6 flex flex-col gap-6`）滚屏，侧栏与顶栏固定；断点 `lg` 以下侧栏收起为抽屉（遮罩 `--lw-overlay`，滑入 `--lw-dur`）。
- 指标卡：`h-32`，白底 + 1px 边框 + 12px 圆角 + `--lw-shadow-sm`，右上角 56px 图标 `opacity-10`，悬停时图标放大 1.1、卡片上移 2px 并加 `--lw-shadow-md`。

## 5. 组件规范

### 5.1 按钮（五态）

过渡统一 `all var(--lw-dur) var(--lw-ease)`（200ms）。

| 状态 | 视觉 | 动效 |
|---|---|---|
| DEFAULT | 主色底白字（主按钮）/ 白底描边（次按钮） | — |
| HOVER | 主色加深 + `--lw-shadow-md` | `scale(1.05)` **放大** |
| PRESSED | 主色再加深，阴影收起 | `scale(.95)` **缩小** |
| DISABLED | `--lw-surface-disabled` 底 + `--lw-ink-disabled` 字 + `--lw-border` 边框 | `cursor-not-allowed`，无 transform、无阴影 |
| LOADING | 保留原底色，文字隐藏，居中旋转指示器 | `cursor: progress`，宽度锁定，屏蔽点击，悬停不放大 |

**规则**：HOVER 放大、PRESSED 缩小，形成明确点击感；LOADING 不落原生 `disabled`，保持主色底与可见指示器（深底变体指示器用 `--lw-on-primary`），宽度不变避免布局跳动。`:focus-visible` 显示 2px 主色焦点环（见 §10）。

**输入类控件的边框**：输入框、下拉、多行文本的静止边框一律取 `--lw-border-strong`（辅助紫），悬停转 `--lw-secondary`，聚焦转 `--lw-primary`。`--lw-border`（浅灰）只用于卡片、表格等**分隔**场景——白底上 1px 浅灰几乎不可见，用在输入框上会让用户找不到可输入的位置。原生 `<input>` / `<textarea>` 必须去掉浏览器默认边框，由外层包装统一绘制（`styles/index.css` 的 `.lw-field`）。

变体：

| 变体 | 静止 | HOVER | PRESSED |
|---|---|---|---|
| `primary` | `--lw-primary` 底 + 白字 | `--lw-primary-hover` | `--lw-primary-active` |
| `secondary` | 透明底 + `--lw-secondary` 描边与文字 | `--lw-secondary-tint` 底 | 文字与描边转 `--lw-secondary-hover` |
| `ghost` | 透明底 + `--lw-primary` 描边与文字 | `--lw-primary-tint` 底 | 描边与文字转 `--lw-primary-active` |
| `danger` | `--lw-error` 底 + 白字 | `--lw-error-hover` | `--lw-error-active` |
| `text` | 无底主色文字（左右内边距 8px） | `--lw-primary-tint` 底 | `--lw-primary-tint` 底 + 文字转 `--lw-primary-active` |

尺寸：

| 尺寸 | 高度 | 内边距 | 字号 |
|---|---|---|---|
| `sm` | 32px | `0 12px` | 12px |
| `md`（默认） | 40px | `0 16px` | 14px |
| `lg` | 48px | `0 24px` | 16px |

**落地方式**：全站按钮唯一实现是 `components/ui/LwButton.vue`（props：`variant` / `size` / `loading` / `disabled` / `block` / `nativeType`）。业务代码禁止直接调用 Element Plus `el-button`；Element Plus 内部组件（确认弹窗、表格操作等）自带的按钮由 `styles/index.css` 全局覆盖为同等五态（含 disabled / loading 视觉）。

### 5.2 分段切换（Segmented）

视图 / 时间范围 / 视角的组内单选一律用 `components/ui/LwSegmented.vue`，禁止各页自造 tab 或 pill：

| 部位 | 规范 |
|---|---|
| 容器 | 白底 `--lw-surface-raised` + 1px `--lw-border` + 8px 圆角 + 3px 内边距 + 组内间隔 2px + `--lw-shadow-sm`；高 40px（与 h-10 控件对齐） |
| 项 | `md`（默认）高 32px、内边距 `0 16px`、13px 加粗；`sm` 高 26px、内边距 `0 10px`、12px 加粗；未选中 `--lw-ink-muted` |
| 悬停（未选中） | 文字转 `--lw-ink`，底转 `--lw-primary-tint` |
| 选中 | `--lw-surface-subtle` 底 + `--lw-primary` 字 + `--lw-shadow-sm` |
| 禁用 | `--lw-ink-disabled` + `cursor-not-allowed`，无悬停反馈 |
| 计数徽标 | 最小宽 16px / 高 16px / 10px，`--lw-primary-tint` 底 + `--lw-primary` 字；选中项内反转为 `--lw-primary` 底 + `--lw-on-primary` 字 |
| 动效 | 选中底与悬停色均在 `--lw-dur-fast` 内切换 |

规则：短标签组内等宽（`equal`，视图切换用），长标签按内容自适应；同一页面同级别分段切换尺寸一致。

### 5.3 标签（LwTag）

`px-2 py-0.5 rounded-tag(6px) text-xs font-bold`，圆角 6px。六种 tone 一律「语义淡底 + 加深文字」：

| tone | 文字 | 底 |
|---|---|---|
| `primary` | `--lw-primary` | `--lw-primary-tint` |
| `secondary` | `--lw-secondary` | `--lw-secondary-tint` |
| `success` | `--lw-success-ink` | `--lw-success-tint` |
| `warning` | `--lw-warning-ink` | `--lw-warning-tint` |
| `error` | `--lw-error-ink` | `--lw-error-tint` |
| `muted` | `--lw-ink-muted` | `--lw-muted-tint` |

同状态同 tone，禁止按页自选颜色。

### 5.4 卡片（LwCard）

`lw-card p-5 flex flex-col gap-4`；`lw-card` = 白底 `--lw-surface-raised` + 1px `--lw-border` + 12px 圆角 + `--lw-shadow-sm`。可选标题行：标题 `text-base` 加粗 `--lw-ink`，说明 `text-xs` `--lw-ink-muted`，右侧为操作区（`gap-2`）。

### 5.5 异步三态外壳与零件

| 组件 | 形态 |
|---|---|
| `LwPageState` | 三态外壳：`loading` → 骨架、`error` → 错误条 + 重试、`empty` → 空态；三态间 `lw-fade` `out-in` 过渡；未命中三态时直接透传 slot、不加包裹，避免破坏调用方网格 |
| `LwEmpty` | 居中 `flex-col gap-3 py-10`：56px 圆角瓷砖（2xl 圆角、`--lw-primary-tint` 底 + 主色 26px 图标）+ 14px `--lw-ink-muted` 说明 + 可选主操作 |
| `LwSkeleton` | `flex-col gap-3`，默认 4 行；每行 36px 高、8px 圆角；宽度在 100/92/78/86/64/90% 间循环；渐变扫光 1.4s；`aria-hidden` |

### 5.6 指标卡（LwMetricCard）

仪表盘与统计页的指标一律用 `components/ui/LwMetricCard.vue`，禁止各页自造指标块：

| 部位 | 规范 |
|---|---|
| 容器 | `lw-card p-5`，固定高 `h-32`，`relative overflow-hidden`；悬停上移 2px 并加 `--lw-shadow-md` |
| 标签行 | 左侧标签 14px 加粗 `--lw-ink-muted`；右侧 28px 圆角瓷砖（圆角 6px、内边距 6px），底与图标同色系淡底 |
| 装饰图标 | 右上角 56px 图标 `opacity-10`，悬停放大 1.1；色随 `tone` |
| 数值 | 24px 加粗、`tracking-tight`、`--lw-ink` |
| 页脚 | 12px 加粗，缺省 `--lw-ink-muted`；可按口径取成功 / 警示 / 错误语义文字色，超长截断 |

`tone` 三档：`primary`（主紫淡底）、`secondary`（辅助紫淡底）、`ink`（辅助紫淡底 + 主紫图标）。`hintTone` 只用于表达口径健康度（如成功率），不承担装饰。

### 5.7 危险文字按钮（lw-text-danger）

不可逆操作（删除、重置密码）一律取错误语义色，与常规操作一眼区分：

| 部位 | 规范 |
|---|---|
| 用法 | `LwButton variant="text" class="lw-text-danger"`，只用于带二次确认的破坏性动作 |
| 颜色 | 文字 `--lw-error-ink`，悬停底纹 `--lw-error-tint`，按下文字 `--lw-error-active` |
| 分组 | 与常规操作之间加 1px 竖分隔线（`w-px h-4` + `--lw-border`），整组 `gap-1` |

禁止把破坏性动作混在一排等距的文字按钮里：误点没有挽回余地，颜色与分隔线是唯一的事前提示。危险色只用于文字按钮，实心危险按钮仍是 `variant="danger"`。

### 5.8 错误条（LwErrorBar）

内联展示，不弹全屏遮罩：`px-4 py-3 rounded-control`，1px `--lw-error` 边框 + `--lw-error-tint` 底；左侧警示图标 + 14px `--lw-error` 文案；右侧 `text` sm 重试按钮。

### 5.9 顶部进度条（LwProgressBar）

固定顶部、`z-50`、高 2px、`--lw-primary` 底 + 8px 同色光晕；宽度按 `--lw-dur`、透明度按 `--lw-dur-fast` 过渡。路由开始推进至 12%，此后每 180ms 随机递增（单次 ≤12%、封顶 88%）；完成时收满 100% 并 240ms 淡出。

### 5.10 Markdown 消息（LwMarkdown）

14px 正文行高 1.65。代码块 `.md-code`：1px `--lw-border` + 10px 圆角 + `--lw-surface` 底，头部 11px `--lw-ink-muted` + 复制按钮（悬停转 `--lw-primary` + `--lw-primary-tint` 底；复制成功 400ms 内图标切为对勾、色转 `--lw-success-ink`）。行内代码 `--lw-muted-tint` 底 + 4px 圆角；引用块 3px 主紫左边 + 右侧 8px 圆角 + `--lw-primary-tint` 底；表格 1px 边框。

## 6. 异步三态

| 态 | 表现 |
|---|---|
| loading | 骨架屏（列表/卡片）或行内旋转指示器（按钮） |
| error | 内联错误条 + 重试按钮，不弹全屏遮罩 |
| empty | 插画 + 一句说明 + 主操作按钮，不留白板 |

每个异步操作必须同时具备三态。

## 7. 反馈与过渡

| 场景 | 反馈 |
|---|---|
| 写操作成功 | 右上角 toast（`--lw-dur-slow` 滑入），3s 自动消失 |
| 写操作失败 | toast 错误态，可展开查看错误码；表单弹窗保留输入 |
| 破坏性操作 | 确认弹窗，主按钮为 error 色，取消按钮为 ghost |
| 流式输出 | 逐字追加，正文末尾 `--lw-primary` 光标以 1s 步进闪烁；中断按钮常驻输入区 |
| 路由切换 | 顶部进度条 + 内容区 `--lw-dur` 淡入并上浮 4px（入场用 `--lw-ease-out`） |
| 列表加载 | 骨架屏，不做整页 loading |
| 提交进行中 | 按钮进入 LOADING，所在卡片禁止二次提交 |
| 悬停说明 | 图标按钮必须带 tooltip（延迟 300ms），文字按钮可省 |
| 复制成功 | 图标 400ms 内从复制态切换为对勾态，并 toast 提示 |
| 表单校验 | 失焦即校验，错误文案在字段下方，边框转 error 色 |
| 网络中断 | 顶栏红条提示，恢复后自动消失 |
| 空态 | 图标 + 一句说明 + 主操作按钮 |

**规则**：不存在无反馈的交互。任何持续超过 300ms 的等待必须有 loading 或骨架；任何状态变化必须有过渡动画或颜色变化。

## 8. 图表约定

- 序列取色经 `utils/theme.ts` 读令牌：默认序列 `--lw-primary`，`secondary` 选项切 `--lw-secondary`；第三序列 `--lw-accent-soft`。
- 面积图：折线宽 3、平滑、无符号点；渐变填充纵向 `5%` 处 `opacity .2` → `95%` 处 `opacity 0`。
- 网格线 `--lw-border` + `dashed` + `opacity .5`。
- 轴标签 `--lw-ink-muted`，字号 10；轴线 `--lw-border`；不显示轴刻度线。
- Tooltip 白底 `--lw-surface-raised` + 1px `--lw-border` + 8px 圆角 + 12px `--lw-ink` 文字。
- 柱状图：柱最大宽 28px，顶部圆角 6px，色同序列规则。
- 环形图：内外半径 `58% / 78%`，分片间隔 2px `--lw-surface-raised` 描边、4px 圆角；图例置底、圆形色块 8×8、11px `--lw-ink-muted`；配色按序取 主紫 / 辅助紫 / `--lw-ink` / `--lw-ink-disabled` / 语义色。
- 不放标题以外的图例装饰，tooltip 白底 1px 边框圆角 8。

## 9. 菜单与标签渲染

- 侧栏目录展开/收起 200ms 旋转箭头，子项容器按 `grid-template-rows: 0fr → 1fr` 过渡（`--lw-ease-out`），比 max-height 平滑且不抖动；选中项 `--lw-primary` 文字 + `--lw-secondary-selected` 底；悬停黑色 5% 叠加。
- 目录下子项左缩进 36px、行高 36px；目录行高 40px。
- 图标只允许线性描边风格（stroke 2、圆角端点、24×24 viewBox），禁止混用实心图标集。
- 菜单标签的 i18n 渲染与降级规则见 [specs/07-frontend.md](specs/07-frontend.md)，本节只定视觉。
- 数字与等宽文本（指标值、耗时、Token、ID）一律 `font-variant-numeric: tabular-nums`：流式刷新时字宽不变，不左右跳动。

## 10. 焦点与可访问性

| 项 | 规则 |
|---|---|
| 焦点环 | `outline: 2px solid var(--lw-primary); outline-offset: 2px`，只在 `:focus-visible` 出现 |
| 键盘 | 所有可点击元素可 Tab 到达、Enter/Space 触发；弹窗打开即锁焦点并在关闭后归还 |
| 对比度 | 正文对底 ≥ 4.5:1；`--lw-on-secondary` 专供 `--lw-secondary` 底，不得用于主紫底 |
| 语义 | 图标按钮必须有 `aria-label`；加载态按钮带 `aria-busy="true"` |
| 动效 | 尊重 `prefers-reduced-motion`，命中时关闭缩放与位移动画，仅保留颜色过渡 |

## 11. 页面范式

### 11.1 三条硬规则

| 规则 | 说明 |
|---|---|
| 色不出四色序列与中性色 | `#f2f0f7` `#cbc9e2` `#9e9ac8` `#6a51a3` 加中性 `#ffffff` / `#f8fafc` / `#e2e8f0` / `#64748b`。语义色只在 success / warning / error（含 info）出现 |
| 层级靠背景差与 1px 边框 | 不用渐变堆叠、不用彩色阴影。页面 `#f8fafc`，卡片 `#ffffff` + `1px #e2e8f0` + `12px` 圆角 |
| 每一处可交互都有形变反馈 | 悬停 `scale(1.05)`、按下 `scale(0.95)`、加载锁宽、禁用去形变。不存在「点了没反应」的元素 |

### 11.2 品牌展示屏（登录页 / 落地页）

背景落两枚**弥散光斑**，取代一切插画与大图：

```html
<div class="absolute top-[-20%] left-[-10%] w-[500px] h-[500px] rounded-full blur-[100px] opacity-20"
     style="background-color: var(--lw-secondary); pointer-events: none"></div>
<div class="absolute bottom-[-10%] right-[-10%] w-[400px] h-[400px] rounded-full blur-[100px] opacity-20"
     style="background-color: var(--lw-surface-subtle); pointer-events: none"></div>
```

光斑必须 `pointer-events: none` 且被内容层 `z-10` 压住。页面背景 `--lw-surface`，无大图无渐变。整体为左右双列：左侧品牌区、右侧凭证卡（`max-w-[400px]`）；`lg` 以下纵向堆叠，品牌区能力卡在 `lg` 以下收起。

品牌区：

- 品牌方块 48px、`--lw-secondary` 底 + `--lw-on-primary` 图标 + `--lw-shadow-sm`，悬停放大 1.05；旁接产品名（24px 加粗 `--lw-ink`）。
- 徽标 pill：白底 + 1px `--lw-border` + `--lw-secondary` 字 + 星形图标，圆角全圆，`px-3 py-1.5` 12px 加粗，悬停加 `--lw-shadow-sm`。
- 大标题两行：第一行 `--lw-ink`（主紫）、第二行 `--lw-secondary`，`text-4xl lg:text-5xl font-extrabold tracking-tight leading-tight`。
- 定位描述 `text-base`、`--lw-ink-muted`、`max-w-[480px]`。
- 能力卡（纵向 `gap-3`，`max-w-[460px]`）：白底 + 1px 边框 + 12px 圆角，左侧 36px 图标瓷砖（`--lw-primary-tint` 底 + `--lw-primary` 图标），标题 14px `--lw-ink`、描述 12px `--lw-ink-muted`；悬停上移 2px 并加 `--lw-shadow-md`。

凭证卡（`lw-card p-6 flex flex-col gap-5`）：

- 欢迎语 18px 加粗 + 一句说明 12px `--lw-ink-muted`；字段间距 16px。
- 输入框 `h-10 px-3 rounded-control`，聚焦时边框转主紫并加 `--lw-shadow-sm`；密码显隐按钮悬停黑 5% 叠加、按下缩小。
- 主操作 `primary lg block`；提交 loading 最短显示 400ms（防闪烁），成功前保持 LOADING。
- 公钥加载失败显示内联错误条 + 重试按钮，**绝不降级明文**。

### 11.3 控制台仪表盘

```
┌────────────────────────────────────────────────────┐
│ TopBar h-16 白底 下边框  logo 方块(--lw-secondary) │
├──────────┬─────────────────────────────────────────┤
│ Sidebar  │ 页头：标题 24px + 副标题 14px muted       │
│ w-52     │      右侧操作区（次 + 主）                │
│ 白底右边框│ 指标卡 ×3：h-32，右上角 56px 图标        │
│ 分组标题 │   opacity-10，悬停 scale-110              │
│ 菜单项   │ 主图卡：面积图（height 300）+ 渐变填充    │
│ h-10     │ 副图卡 ×2：柱状图 + 环形图（height 260）  │
│ 底部品牌卡│                                          │
└──────────┴─────────────────────────────────────────┘
```

- **页头** `lw-page-head`：标题 `lw-title`（24px 加粗）+ 副标题 `lw-subtitle`（14px `--lw-ink-muted`），右侧操作区为「次 + 主」两枚按钮。
- **指标卡**：见 §5.6，全站唯一实现是 `components/ui/LwMetricCard.vue`（`tone` 决定淡底图标瓷砖，`hintTone` 决定页脚语义色）。
- **面积图**（见 §8）：主序列 `--lw-primary`，`secondary` 时 `--lw-secondary`，渐变 `5%:0.2 → 95%:0`；网格线 `--lw-border` `dashed` `opacity .5`，Y 轴刻度 `--lw-ink-muted` 10px。
- 图卡右上角可放 12px 加粗的时间范围徽标（`--lw-muted-tint` 底），**不得**再放装饰性图例。
- **侧栏底部品牌卡**：`--lw-secondary` 底 + `--lw-on-secondary` 字，12px 圆角 + `--lw-shadow-sm`，`p-4`，内叠 `--lw-on-primary` 10% 蒙版；内容为产品名、口号与 `用户名 · 角色码`。这是侧栏唯一的深色块，用于把非菜单信息与导航分开。

### 11.4 组件示例页

用于自证设计系统的页面（`/showcase`）为 1/2 列响应式网格（`gap-6`），由四张 `LwCard` 组成：品牌与按钮 / 功能语义色 / 交互控件 / 信息展示。

- 每张卡走 `LwCard` 形态：白底 + 1px 边框 + 12px 圆角 + `--lw-shadow-sm` + `p-5`，标题 16px 加粗 `--lw-ink`、说明 12px `--lw-ink-muted`。
- 色板卡：`h-16 rounded-lg` 大色块 + 下方 `font-mono text-xs` 行（左色值右名称，`--lw-ink-muted`），展示主紫 `#6A51A3` 与辅助紫 `#9E9AC8`。
- 语义反馈用 `LwTag`（六 tone）与 `LwErrorBar`，**不使用 Element Plus Alert**。
- 图表样例用 `LwAreaChart`（ECharts），不自造 canvas 图形。
- 标签统一 `LwTag` 形态（`px-2 py-0.5 rounded-tag(6px) text-xs font-bold`），不另造标签样式。

### 11.5 观测与过程可视化

时间线之外的三类「过程可读」组件，用同一套令牌，不再引入新视觉语言。

| 组件 | 形态 | 关键约束 |
|---|---|---|
| 链路瀑布（`TraceWaterfall`） | 每行 = 一个片段：左标签 / 中轨道（12px 高，底 `--lw-border`）/ 右指标（状态 Tag + 耗时 + Token） | 条用主紫（模型）与辅助紫（工具），错误用 `--lw-error-ink`、待收尾用 `--lw-warning-ink`；宽度按 `offsetMs / totalMs` 百分比、最小 0.8%；悬停整行铺 `--lw-primary-tint`，待收尾行铺 `--lw-warning-tint`；不做缩放与拖拽 |
| Run 树（`RunTree`） | 缩进列表 + 一条竖连接线标层级 | 行高 36px（h-9），子行左内缩 1.25rem/级；选中态 `--lw-secondary-tint` 底 + `--lw-secondary` 边；层级只用缩进与连接线，不加重型树控件 |
| 用量归因（`UsagePanel`） | 指标卡 ×4 + 柱状趋势 + 占比条 | 柱宽自适应、间隔 `gap-1`、无坐标轴，柱底 `--lw-secondary`；占比条底 `--lw-border`，条按序号在主紫/辅助紫间交替；数字一律 `font-mono` 且 `k` / `M` 简写 |
| 计划清单（`PlanChecklist`） | 顶部标题 + 进度条 + 步骤列表 | 卡片底 `--lw-secondary-tint`、边 `--lw-secondary`；完成步骤 `--lw-ink-muted` + 删除线，进行中主紫加粗；进度条 500ms 过渡 |

共同规则：**颜色只表达「谁在耗时 / 到哪一步」，不承担装饰**；所有状态变化都要有过渡（`200ms` 起）；无数据一律 `LwEmpty` + 一句说明，不留白板。

### 11.6 禁止清单

| 禁止 | 替代 |
|---|---|
| 硬编码色值（含 `#ffffff` / `#f8fafc`） | `var(--lw-*)` 或 UnoCSS `lw-*` 语义类 |
| 渐变背景（除 §11.2 光斑与面积图填充） | 背景差 + 边框 |
| 一级以上的阴影堆叠 | `--lw-shadow-sm` / `--lw-shadow-md` |
| 自造 Tab / Pill / 开关 / 标签 | `LwSegmented` / `LwTag` / `el-switch` 主题已对齐 |
| 实心图标与线性图标混用 | 统一 stroke 2、24×24 viewBox |
| 表格整页 loading | 骨架行 3–5 行 |
| 空状态留白板 | `LwEmpty` + 一句说明 + 主操作 |

## 12. 页面模式

高频页面有固定骨架，新页面照此落地，不自创布局。

### 12.1 检索页（日志中心 / 知识库检索）

```
┌──────────────────────────────────────────┐
│ 页头：标题 + 一句说明                      │
├──────────────────────────────────────────┤
│ 概览卡网格：计数卡（级别分布）+ 直方图卡片   │
├──────────────────────────────────────────┤
│ 过滤区：关键字 + 字段选择 + 等值过滤 +       │
│         快捷时间预设 + 查询按钮             │
├──────────────────────────────────────────┤
│ 结果列表：行内高亮 + 展开详情 + 钻取动作     │
│ 分页器：total + sizes + prev/pager/next   │
└──────────────────────────────────────────┘
```

- 概览卡与直方图随过滤条件联动刷新，顶部有 `--lw-dur` 高度过渡，不做整页 loading。
- 快捷时间预设：15 分钟 / 1 小时 / 24 小时 / 7 天，选中态浅紫底 + 主紫字；自定义时间用日期选择器。
- 结果行：级别用 `LwTag`（INFO 主紫 / WARN 警告 / ERROR 错误），时间等宽字体，正文单行截断；点击展开显示 `traceId` / `logger` / `thread` 与异常栈（异常区 `--lw-error-tint` 底 + `--lw-error-ink` 字）。
- 关键字命中在正文内高亮：`<mark>` 浅紫底圆角 2px，不使用浏览器默认黄色。
- 钻取动作：复制 `traceId`（图标 400ms 切对勾 + toast）、按该 `traceId` 过滤（一键填入过滤区并刷新）。
- 过滤区下方常驻一行语法说明（12px `--lw-ink-muted`）：关键字匹配哪个字段、等值过滤有哪些、时间怎么传。

### 12.2 对话时间线（工作台）

```
用户消息：右对齐，主紫淡底气泡，圆角 12（右下 4）
助手消息：左对齐无气泡，Markdown 全文渲染
  思考块：可折叠，标题行"思考过程"+ 耗时，展开为 12px 次级文本
  工具卡：白卡 1px 边框，头部 = 工具名 + 状态 Tag + 耗时，
          展开显示参数 JSON 与输出（输出超 4KB 截断并提示）
  模型调用行：11px 主紫淡底胶囊，标注模型名
运行终态：成功/失败/中断 Tag + 用量（输入/输出 Token）+ 总耗时
流式中：正文末尾光标闪烁（--lw-primary，1s 步进），中断按钮常驻输入区
```

- 时间线按事件 `seq` 严格保序渲染，不做前端重排。
- 思考块底 `--lw-surface-subtle`、8px 圆角；工具卡白底 + 1px `--lw-border` + 8px 圆角，展开代码区 `--lw-surface-subtle` 底 + 6px 圆角 + 最大高 260px 滚动。
- 回放态：顶部通栏提示条（`--lw-secondary-tint` 底 + 主紫字）+「返回实时」按钮，与实时态视觉可区分。
- 输入区：多行自适应文本域，回车发送（组词中不发送），发送按钮 loading 与流式状态联动；流式中中断按钮为 `secondary` + loading 形态，常驻输入区。

### 12.3 运行可观测页（运行记录）

```
┌──────────────┬────────────────────────────────┐
│ 过滤条：状态  │ 头部：状态 Tag + 模型 + 耗时 +   │
│  分段切换 +   │       Token + 中断按钮           │
│  时间预设     │ 分段切换：时间线 | 工具链 | 用量  │
├──────────────┤ 时间线：思考块 / 正文块 /         │
│ 运行列表      │  工具卡 / 模型调用行（见 12.2）   │
│ 行：时间 状态 │ 底部：回放控制条                 │
│ 耗时 Token    │  播放 · 速度档 · 进度 · 返回实时  │
└──────────────┴────────────────────────────────┘
```

- 运行状态 Tag 色：`success` 语义绿 / `failed` 错误色 / `aborted` 中性灰 / `running`·`queued` 主紫 / 其余警示色。
- 工具卡状态：执行中显示旋转指示器，完成转对勾或错误图标，均有 `--lw-dur` 过渡。
- 回放态：详情区顶部通栏提示条（`--lw-secondary-tint` 底 +「回放中 · 返回实时」），与实时态视觉可区分。
- 空态：`LwEmpty` + 「选择左侧运行查看详情」；列表空显示「暂无运行记录」+ 去工作台按钮。

### 12.4 错误页

| 页 | 视觉 | 动作 |
|---|---|---|
| 403 | 48px 大号数字（`--lw-secondary`）+ 说明"没有访问该页面的权限" | 主按钮"返回首页" + ghost"回到上一页" |
| 404 | 同上 + "页面不存在或已被移动" | 主按钮"返回首页" + ghost"回到上一页" |
| 500 | 同上 + "服务开小差了，请稍后重试" | 主按钮"重试"（reload）+ ghost"返回首页" |

- 三页共用同一骨架组件（`LwErrorPage`）：居中 `lw-page`、最小高 60vh、`--lw-surface` 底、无插画堆砌；数字 `text-5xl` 加粗 `--lw-secondary`，说明 14px `--lw-ink-muted`，标题 16px 加粗 `--lw-ink`。
- 文案一律走 i18n；路由守卫判定无权限时跳 `/403` 并附上所需权限说明（不暴露资源是否存在）。

### 12.5 数据表格与分页

| 项 | 规则 |
|---|---|
| 表头 | 12px 加粗、`--lw-ink-muted`、`--lw-surface-raised` 底、底线 1px `--lw-border` |
| 行 | 正文 14px，行高 44px，悬停 `--lw-primary-tint`，斑马纹禁用 |
| 操作列 | 固定右侧，按钮 `sm` 档，组内间距 8px，等高对齐 |
| 状态列 | 统一 `LwTag`，同状态同色（见语义色表） |
| 空态 | 表格内 `LwEmpty`（图标 + 说明 + 主操作），不留白板 |
| 加载 | 骨架行（3–5 行），不做整页遮罩 |
| 分页 | 右对齐：总数 + 每页条数 + 上一页 / 页码 / 下一页；页码选中态主紫底白字 |

**对齐纪律**：同一筛选行的控件统一 `h-10`（按钮 `md`、输入 `md`、下拉 `md`），标签文本与控件水平基线对齐；表单页标签统一左对齐固定宽度；不同页面同类元素尺寸一致，严禁同屏混用 `sm` / `md`。

## 13. 暗色模式

只交付浅色主题，不提供 `data-theme` 切换的半成品；暗色主题落地时以本文件令牌表为唯一改造面。

## 14. 案例对照与验收

本节把设计基准案例（落地页 / 仪表盘 / 组件库三屏）映射到本文档的令牌与章节，作为实现的验收面：案例中出现的每一个视觉决策，都能在本文件找到唯一出处。

### 14.1 三屏对照

| 案例屏 | 本文件出处 | 骨架要点 |
|---|---|---|
| 落地页（品牌展示屏） | §11.2 | 左右双列；两枚 `blur-[100px]` 弥散光斑（`--lw-secondary` 与 `--lw-surface-subtle`，`opacity-20`、`pointer-events-none`）；左侧品牌区（品牌方块 + 徽标 pill + 两行大标题 + 能力卡），右侧凭证卡 `max-w-[400px]` |
| 仪表盘（控制台） | §11.3、§5.6、§8 | TopBar `h-16` + Sidebar `w-52` + `lw-page`；页头「次 + 主」两枚按钮；指标卡 ×3（`h-32`、右上角 56px 图标 `opacity-10`、悬停上移 2px）；面积图 + 柱状图 + 环形图 |
| 组件库（自证页） | §11.4 | 1/2 列响应式网格 `gap-6`，四张 `LwCard`：品牌与按钮 / 功能语义色 / 交互控件 / 信息展示 |

### 14.2 案例色到令牌的强制映射

案例稿里出现的非令牌色一律按下表替换，禁止照抄：

| 案例取值 | 替换为 | 说明 |
|---|---|---|
| `slate-900` 实心按钮 | `LwButton variant="primary"`（`--lw-primary` 底 + `--lw-on-primary` 字） | 深底按钮不用中性黑，主色即品牌紫 |
| `slate-50` / `slate-100` / `slate-200` | `--lw-surface` / `--lw-surface-subtle` / `--lw-border` | 中性灰只有三档，全部走令牌 |
| `emerald-500` 上升指标 | `--lw-success-ink` | 语义文字色，不用鲜绿 |
| `amber-500` 视图开关选中态 | `--lw-primary-tint` 底 + `--lw-primary` 字 | 主题/视图切换的选中态统一走 `LwSegmented` 规范（§5.2） |
| `blue-600` 分段选中态 | `--lw-primary` 字 + `--lw-surface-subtle` 底 | 同上，禁止引入蓝色系 |
| `dark:` 变体 | 一律删除 | 只交付浅色主题（§13） |

### 14.3 五态与三态验收

| 检查项 | 判据 |
|---|---|
| 按钮五态 | DEFAULT / HOVER（`scale(1.05)` + 加深）/ PRESSED（`scale(.95)` + 再加深）/ DISABLED（无 transform、无阴影）/ LOADING（锁宽、指示器可见、不落原生 `disabled`）逐项可复现，见 §5.1 |
| 异步三态 | 每个异步区同时具备 loading（骨架或行内指示器）、error（内联错误条 + 重试）、empty（`LwEmpty` + 一句说明 + 主操作），见 §6 |
| 图表 | 面积图渐变 `5%:0.2 → 95%:0`、网格线 `dashed opacity .5`、轴标签 10px `--lw-ink-muted`、tooltip 白底 1px 边框圆角 8，见 §8 |
| 层级 | 只用背景差 + 1px 边框 + 两级阴影，无渐变堆叠、无彩色阴影，见 §11.1 |
