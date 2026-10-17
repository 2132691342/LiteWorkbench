# DESIGN.md

## 定位

本文回答前端**长什么样、怎么动**：设计令牌、语义色、排版间距、布局骨架、组件形态与交互状态、页面范式与禁止清单。

不回答前端工程结构（技术栈、目录、路由、状态管理、请求封装与流式管线），后者见 [specs/07-frontend.md](specs/07-frontend.md)。

实现以本文为准；色值只允许出现在 `frontend/src/styles/tokens.css`。

---

## 1. 设计原则

### 1.1 为谁设计

目标用户是**自托管一台服务器的个人创作者与知识工作者**：写作者、研究者、产品经理、独立开发者。他们同时是使用者与运维者——自己配模型供应方、自己看日志排障，没有运维团队兜底。使用场景是长时段、反复回访：一次创作往往跨越多天、多轮对话与多版文档。

他们要完成四件事：让 Agent 干活并看得见过程；把产出沉淀成文档、版本与知识库；事后复盘"这次跑得值不值"；管住多用户多角色的权限边界。

人格定调：**极简、克制、可信**——一个安静的工具，不是一件展品。所有注意力预算留给过程与数据（时间线、工具链、链路瀑布、用量归因），外壳必须安静。差异化记忆点是**让 Agent 的运行过程可读**：别的产品展示结果，本产品展示"它是怎么干出来的"。

### 1.2 视觉原则

**设计哲学：秩序靛（Ordered Indigo）。** 一个只靠背景差、1px 边框与两级阴影建立层级的浅色系统。SaaS 五色调色板承担全部信息编码——靛蓝 `#6366f1` 是品牌与确认动作，`#818cf8` 是选中态与次序列，`#e0e7ff` 是最浅的一档只做底纹，`#f8fafc` 是页面底，`#1e293b` 是文字。所有装饰（渐变堆叠、插画、大图）一律让位给两枚弥散光斑与真实数据本身；界面里唯一被允许"动"的，是状态变化：悬停放大、按下缩小、加载锁宽。判断一个元素该不该存在的标准是它是否替用户省掉一次点击或一次犹豫，不能就删掉。

| 原则 | 落地方式 |
|---|---|
| 极简 | 无装饰性渐变与阴影堆叠，层级靠背景差与 1px 边框建立 |
| 克制配色 | SaaS 五色调色板走完全站，语义色只在 success / warning / error（含 info）出现 |
| 可预期 | 所有可点击元素有五态，所有异步操作有三态 |
| 有反馈 | 悬停、按下、加载、成功、失败均有过渡动画，无静默操作 |

### 1.3 反模板自检

交付前问一遍：这个界面如果被人说是 AI 生成的，会立刻被相信吗？会，就说明它还在用"居中英雄区 + 三等分卡片 + 蓝紫渐变"那套默认模板，重做。辨识度来自**真实数据密度与过程可读性**，不来自装饰。

## 2. 设计令牌

### 2.1 SaaS 五色调色板

全站唯一色值源头。组件与页面一律引用 §2.2 的语义别名，禁止直接引用本组变量。

```css
:root {
  --saas_dashboard-color-1: #6366f1;   /* 主靛蓝：品牌、主按钮底、图表主序列 */
  --saas_dashboard-color-2: #818cf8;   /* 辅助靛蓝：选中态、次序列、输入强调边 */
  --saas_dashboard-color-3: #e0e7ff;   /* 靛蓝淡底：头像底、选中按钮底、思考块底 */
  --saas_dashboard-color-4: #f8fafc;   /* 页面底 */
  --saas_dashboard-color-5: #1e293b;   /* 主文本 */
}
```

### 2.2 语义别名

业务与组件只用别名，不直接引用五色调色板。

| 别名 | 取值 | 用途 |
|---|---|---|
| `--lw-primary` | `var(--saas_dashboard-color-1)` | 主按钮底、主序列、焦点环、图标 |
| `--lw-primary-hover` | `#4f46e5` | 主色悬停 |
| `--lw-primary-active` | `#4338ca` | 主色按下 |
| `--lw-primary-ink` | `#4f46e5` | 浅底之上的主色**文字**（正文对比度 ≥ 4.5:1） |
| `--lw-primary-tint` | `rgba(99,102,241,.08)` | 主色淡底 |
| `--lw-secondary` | `var(--saas_dashboard-color-2)` | 侧栏选中、图表次序列、强调底色 |
| `--lw-secondary-hover` | `#6366f1` | 辅助靛蓝悬停（收敛到主靛蓝） |
| `--lw-secondary-tint` | `rgba(129,140,248,.14)` | 辅助靛蓝淡底 |
| `--lw-secondary-selected` | `rgba(129,140,248,.1)` | 侧栏选中底 |
| `--lw-accent-soft` | `var(--saas_dashboard-color-3)` | 图表第三序列、分隔强调 |
| `--lw-surface-subtle` | `var(--saas_dashboard-color-3)` | 头像底、选中按钮底、品牌卡、思考块底 |
| `--lw-surface` | `var(--saas_dashboard-color-4)` | 页面底色 |
| `--lw-surface-raised` | `#ffffff` | 卡片、侧栏、顶栏 |
| `--lw-surface-disabled` | `#f1f5f9` | 禁用底 |
| `--lw-border` | `#e2e8f0` | 默认边框（分隔） |
| `--lw-border-strong` | `var(--saas_dashboard-color-2)` | **输入类控件**边框 |
| `--lw-ink` | `var(--saas_dashboard-color-5)` | 主文本 |
| `--lw-ink-muted` | `#64748b` | 次级文本 |
| `--lw-ink-disabled` | `#94a3b8` | 禁用文本 |
| `--lw-on-primary` | `#ffffff` | 主靛蓝底之上的文字 |
| `--lw-on-secondary` | `var(--saas_dashboard-color-5)` | 辅助靛蓝底之上的文字 |
| `--lw-overlay` | `rgba(15,23,42,.45)` | 抽屉遮罩 |

**`--lw-primary-ink` 是唯一的主色文字色**：`#6366f1` 对白的对比度约 4.45:1，`#818cf8` 仅约 3.0:1，都过不了 14px 正文的 4.5:1 门槛。故凡"浅底之上的主色/辅助靛文字"（链接、菜单选中项、标签文字、按钮描边变体的文字、徽标 pill）一律取 `--lw-primary-ink`（`#4f46e5`，6.3:1）；`--lw-primary` 与 `--lw-secondary` 只做**底、边框、图形与图表序列**，不直接当文字色。

布局尺寸与圆角同样以令牌表达：`--lw-topbar-h: 64px`、`--lw-sidebar-w: 208px`、`--lw-sidebar-w-collapsed: 64px`；圆角 `--lw-radius-card: 12px` / `--lw-radius-control: 8px` / `--lw-radius-tag: 6px` / `--lw-radius-pill: 999px`，与 UnoCSS `rounded-card` / `rounded-control` / `rounded-tag` 同值，供 scoped CSS 引用——scoped 样式里写 `border-radius: var(--lw-radius-control)`，不写字面量。表格密度三档以 `--lw-row-h` 表达：默认 44px / 中等 36px / 紧凑 28px。

### 2.3 语义色与派生色

| 类别 | 令牌 | 取值 | 用途 |
|---|---|---|---|
| 语义底 | `--lw-success` | `#10b981` | 运行成功、校验通过 |
| | `--lw-warning` | `#f59e0b` | 需要确认、资源吃紧 |
| | `--lw-error` | `#ef4444` | 运行失败、请求异常 |
| | `--lw-info` | `var(--lw-primary)` | 提示、信息 |
| 文字加深 | `--lw-success-ink` | `#047857` | 成功语义文字（浅底 ≥ 4.5:1） |
| | `--lw-warning-ink` | `#b45309` | 警示语义文字 |
| | `--lw-error-ink` | `#b91c1c` | 错误语义文字 |
| 交互态 | `--lw-error-hover` | `#dc2626` | 危险按钮悬停 |
| | `--lw-error-active` | `#b91c1c` | 危险按钮按下 |
| 淡底 | `--lw-success-tint` | `rgba(16,185,129,.12)` | 成功标签底 |
| | `--lw-warning-tint` | `rgba(245,158,11,.14)` | 警示标签底、待收尾行底 |
| | `--lw-error-tint` | `rgba(239,68,68,.1)` | 错误标签底、错误条底 |
| | `--lw-muted-tint` | `rgba(100,116,139,.12)` | 中性标签底、行内代码底 |
| EP 主题映射 | `--lw-primary-light-3/5/7/8` | `#9294f5` / `#b2b2f8` / `#d0d1fb` / `#e0e0fc` | 主靛与白按 30/50/70/80% 混合，仅供 Element Plus 主题映射 |
| | `--lw-primary-dark-2` | `#4f52c1` | 主靛与黑按 20% 混合 |
| 骨架 | `--lw-skeleton-a` | `var(--saas_dashboard-color-3)` | 骨架屏渐变起点 |

**规则**：语义色底只做低饱和处理，避免与品牌靛抢注意力；文字态一律用 ink 变体加深，不直接用语义底——`#10b981` 对白的对比度只有 2.7:1，拿来写字等于没写。

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

UnoCSS 主题只映射令牌，组件禁止硬编码色值：颜色 `lw-<别名>`（如 `text-lw-ink`、`bg-lw-raised`、`border-lw-border`，主色文字用 `text-lw-primaryInk`）、圆角 `rounded-card`(12px) / `rounded-control`(8px) / `rounded-tag`(6px)、阴影 `lw-shadow-sm` / `lw-shadow-md`；快捷类 `lw-card` / `lw-panel` / `lw-page` / `lw-page-head` / `lw-title` / `lw-subtitle` / `lw-label` / `lw-input` / `lw-field` / `lw-table` / `lw-center`。

**输入类控件只允许两种形态**，二者都自带一眼可见的框线，禁止第三种写法：

| 形态 | 用法 | 框线来源 |
|---|---|---|
| Element Plus 控件 | `el-input` / `el-select` / `el-textarea` / `el-date-picker` | `styles/index.css` 全局把 `box-shadow: 0 0 0 1px var(--lw-border-strong)` 打进 wrapper |
| 原生字段 | `<div class="lw-field">…<input />…</div>` | `.lw-field` 画 1.5px 实线边框，内层 `input` / `textarea` 强制 `border: none` |

`.lw-field` 带两个变体，仍属「原生字段」这一种形态：`--textarea` 用于多行文本（解除固定高度、最小 80px），`--auto` 用于容器内混排（如对话输入区：解除固定 `h-10`，高度随内层多行文本与右侧按钮自适应）。除变体外不得再叠加 `rounded-*`、`border` 等覆盖类，圆角与边框一律由 `.lw-field` 决定。

裸 `<input>` / `<textarea>` 不加 `.lw-field` 包装视为违规：浏览器默认边框在 `#f8fafc` 上几乎不可见，且与令牌的圆角、色阶都不对齐。

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
┌─────────────────────────────────────────────┐
│ TopBar h-16 白底 下边框                      │
│  [折叠] 面包屑          问候 · 语言 · 用户区  │
├────────┬────────────────────────────────────┤
│ Sidebar│ Main                               │
│ 208px  │ lw-page: p-6 flex-col gap-6        │
│ 或 64px │  ├ 页头（标题 + 副标题 + 操作区）  │
│ 白底   │  ├ 指标卡网格（1/3 列）             │
│ 右边框 │  └ 内容区（图表 / 列表 / 时间线）   │
└────────┴────────────────────────────────────┘
```

- 顶栏 `--lw-topbar-h`、白底 `--lw-surface-raised`、下边框 `--lw-border`、内边距 `px-4 md:px-6`。左侧依次为折叠按钮（`lg` 以上）与移动端抽屉唤出按钮（`lg` 以下）、面包屑（`lg` 以上），产品名只在移动端出现；右侧为问候语（`md` 以下隐藏）、语言切换（ghost sm）与用户区（昵称/角色 + 36px 圆形头像，`--lw-surface-subtle` 底 + `--lw-primary-ink` 字 + `--lw-shadow-sm`，悬停放大 1.05）。
- 侧栏宽 `--lw-sidebar-w`、白底、右边框。侧栏顶部是与顶栏同高（`--lw-topbar-h`）的品牌区：32px 品牌方块 `--lw-secondary` 底 + `--lw-on-secondary` 图标 + `--lw-shadow-sm`（悬停放大 1.05）后接产品名（14px 加粗），下边框 `--lw-border`。菜单组标题 10px 大写 `--lw-ink-muted`（`px-2 mb-1`）；一级菜单项 `h-10` 圆角 8px，二级子项 `h-9`，悬停为黑色 5% 叠加，选中为 `--lw-primary-ink` 文字 + 字重 600 + `--lw-secondary-selected` 底；目录展开箭头 200ms 旋转。菜单项统一走 `.lw-menu__item` 系列类（`--dir` / `--leaf` 两档高度），不在各页内联书写悬停与选中色。
- 侧栏底部固定品牌卡：`--lw-secondary` 底 + `--lw-on-secondary` 字，12px 圆角 + `--lw-shadow-sm`，内叠一层 `--lw-on-primary` 10% 蒙版；内容为产品名（12px 加粗）、口号（10px）、`用户名 · 角色码`（10px 等宽）；悬停放大 1.02。
- 主区 `lw-page`（`p-6 flex flex-col gap-6`）滚屏，侧栏与顶栏固定；断点 `lg` 以下侧栏收起为抽屉（遮罩 `--lw-overlay`，滑入 `--lw-dur`）。
- 指标卡：`h-32`，白底 + 1px 边框 + 12px 圆角 + `--lw-shadow-sm`，右上角 56px 图标 `opacity-10`，悬停时图标放大 1.1、卡片上移 2px 并加 `--lw-shadow-md`。

### 4.1 侧栏折叠（图标栏）

桌面端折叠不是「把侧栏藏起来」，而是收成 **64px 图标栏**——导航始终在手边，主区一次让出 144px。这是 B 端后台唯一被接受的折叠形态：隐藏式折叠会让用户在二级页里连当前位置都定位不到。

| 部位 | 展开 | 折叠（`--lw-sidebar-w-collapsed: 64px`） |
|---|---|---|
| 品牌区 | 32px 方块 + 产品名 | 只留方块，居中，产品名与分隔线一并收起 |
| 目录项 | 图标 + 文字 + 展开箭头 | 只留图标居中；点击即展开侧栏并打开该目录（不弹浮层：二级菜单在 64px 宽度里做嵌套浮层时 z-index 与溢出洞更难调，而展开就是把导航还原成可读状态） |
| 叶子项 | 图标 + 文字 | 只留 20px 图标，居中；tooltip 右侧浮出菜单文案（延迟 300ms） |
| 选中态 | 浅靛底 + 主靛字 | 浅靛底 + 主靛图标，左侧 3px 主靛竖条标示当前位置 |
| 底部品牌卡 | 完整卡片 | 收起（`display: none`），保留 12px 底部内边距 |
| 过渡 | — | 宽度 `--lw-dur`、文字 `opacity var(--lw-dur-fast)` 淡出后 `width: 0`；文字不允许挤压变形 |

折叠按钮在顶栏左侧常驻（`lg` 以上可见，`md` 以下换抽屉唤出按钮），图标 `Fold` / `Expand` 随状态切换；折叠状态持久化到 `lw-sidebar-collapsed`，刷新后保持。

### 4.2 面包屑

顶栏左侧、折叠按钮之后常驻面包屑：`目录 / 页面` 两级，末级为当前页且不可点（`--lw-ink` 500），上级 `--lw-ink-muted` 可点且悬停转 `--lw-primary-ink`。分隔符用 `/`（12px `--lw-ink-disabled`），**不用** `>` 或斜杠图标。

- 来源是当前 `route.meta.title` 沿菜单树向上回溯的祖先链，不额外请求。
- 只有一个层级（如仪表盘）时不渲染面包屑，避免「/ 仪表盘」这种半截形式。
- 面包屑是**导航层级**，不是操作历史；页面内的视图切换（分段切换、标签页）不进面包屑。
- 窄屏（`< lg`）收起面包屑，只留页面标题——两行文字抢同一行空间不如让标题独占。

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

**输入类控件的边框**：输入框、下拉、多行文本、日期选择、搜索框的静止边框一律取 `--lw-border-strong`（辅助靛）**且不低于 1.5px**，悬停转 `--lw-secondary` 并加 `--lw-shadow-sm`，聚焦转 `--lw-primary` 并加 3px 主靛淡底光晕。`--lw-border`（浅灰）只用于卡片、表格等**分隔**场景——白底上 1px 浅灰几乎不可见，用在输入框上会让用户找不到可输入的位置。原生 `<input>` / `<textarea>` 必须去掉浏览器默认边框，由外层 `.lw-field` 统一绘制（见 §2.5）。禁用态转 `--lw-surface-disabled` 底 + `--lw-ink-disabled` 字，边框保留，不让控件"整块消失"。

**图层层次**：页面 `--lw-surface` → 卡片 `--lw-surface-raised` + `--lw-border` + `--lw-shadow-sm` → 卡内分区 `--lw-surface` 或 `--lw-surface-subtle` 底 + `--lw-border`。相邻两层必须同时满足"**色差 + 边框**"两项，只给色差或只给边框都视为无层次。浮层（弹窗、抽屉、下拉面板）一律 `--lw-shadow-md` 且底色仍为 `--lw-surface-raised`，靠阴影与边框与页面分层，不靠加深底色。

变体：

| 变体 | 静止 | HOVER | PRESSED |
|---|---|---|---|
| `primary` | `--lw-primary` 底 + 白字 | `--lw-primary-hover` | `--lw-primary-active` |
| `secondary` | 透明底 + `--lw-secondary` 2px 描边 + `--lw-primary-ink` 字 | `--lw-secondary-tint` 底 | 描边转 `--lw-secondary-hover`、文字转 `--lw-primary-active` |
| `ghost` | 透明底 + `--lw-primary` 1px 描边 + `--lw-primary-ink` 字 | `--lw-primary-tint` 底 | 描边与文字转 `--lw-primary-active` |
| `danger` | `--lw-error` 底 + 白字 | `--lw-error-hover` | `--lw-error-active` |
| `text` | 无底 `--lw-primary-ink` 文字（左右内边距 8px） | `--lw-primary-tint` 底 | `--lw-primary-tint` 底 + 文字转 `--lw-primary-active` |

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

`tone` 三档：`primary`（主靛淡底）、`secondary`（辅助靛淡底）、`ink`（辅助靛淡底 + 主靛图标）。`hintTone` 只用于表达口径健康度（如成功率），不承担装饰。

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

`retryable` 默认 true。表单提交失败、登录失败这类「重放同一个请求没有意义」的场景传 `retryable="false"` 收起重试按钮，只留文案；仍用本组件而非自造 `<p>`，保证错误视觉全站一处定义。

### 5.9 顶部进度条（LwProgressBar）

固定顶部、`z-50`、高 2px、`--lw-primary` 底 + 8px 同色光晕；宽度按 `--lw-dur`、透明度按 `--lw-dur-fast` 过渡。路由开始推进至 12%，此后每 180ms 随机递增（单次 ≤12%、封顶 88%）；完成时收满 100% 并 240ms 淡出。

### 5.10 Markdown 消息（LwMarkdown）

14px 正文行高 1.65。代码块 `.md-code`：1px `--lw-border` + 10px 圆角 + `--lw-surface` 底，头部 11px `--lw-ink-muted` + 复制按钮（悬停转 `--lw-primary` + `--lw-primary-tint` 底；复制成功 400ms 内图标切为对勾、色转 `--lw-success-ink`）。行内代码 `--lw-muted-tint` 底 + 4px 圆角；引用块 3px 主靛左边 + 右侧 8px 圆角 + `--lw-primary-tint` 底；表格 1px 边框。

### 5.11 工作台输入框（MessageComposer）

工作台输入框是全站唯一的"意图入口"。它必须同时承担四件事：写正文、点名技能、挂引用、发送与中断。**判定标准是"手不离键盘就能完成一次发送"**——任何需要鼠标才能触达的能力都不算数。

| 部位 | 规范 |
|---|---|
| 容器 | 单一圆角框：`--lw-radius-card` + `--lw-field-border`，静止 `--lw-shadow-sm`；悬停转 `--lw-field-border-hover`；聚焦或面板展开转 `--lw-field-border-focus` + `--lw-field-focus-ring`。**禁止**在框内再嵌一层带边框的输入元素 |
| 已选项行 | 输入区之上，`flex-wrap gap-2`，**无边框无底**，靠间距与输入区分层；有 chip 时才出现，容器高度在 `--lw-dur` 内过渡。技能 chip `--lw-surface-subtle` 底 + `--lw-primary-ink` 字，引用 chip `--lw-secondary-tint` 底 + `--lw-primary-ink` 字；两者一律 `--lw-radius-pill`、12px、左侧 14px 类型图标、右侧 16px 圆形删除按钮（悬停转 `--lw-error-ink` 并 `rotate(90deg)`，按下 `scale(.9)`） |
| 输入区 | 透明无边框，14px 行高 1.6，自增高到 200px 后内部滚动；占位色 `--lw-ink-disabled`。**流式期间保持可编辑**——用户可以在 Agent 干活时写下一条，只禁用发送与面板唤出 |
| 拖拽把手 | 输入框顶边 12px 热区（`.lw-composer__grip`）：悬停 / 聚焦 / 拖拽中显形 40×3 胶囊，`ns-resize` 光标；向上拖动拉高输入区（上限 `min(420px, 可视高 − 消息区 120px)`），**双击交还自动高度**。长文本写完自己看不清是最直接的挫败感，固定 200px 上限解决不了它 |
| 附件 | 底部「附件」胶囊上传到当前工作区桶，成功后落 file chip；**支持直接粘贴截图**（与上传同一条链路）。一次最多 5 个，满额时胶囊置灰并在输入区下方给一行警示文案，不靠置灰按钮让用户猜 |
| 工具条 | 左：**模型 chip**、**工作区 chip** 两枚常驻五态控件（28px，胶囊形，`--lw-surface` 底 + `--lw-border` 边，悬停转 `--lw-primary-ink` + `--lw-secondary` 边 + `--lw-primary-tint` 底并 `scale(1.03)`，按下 `scale(0.97)`，选中态主靛描边；未绑定时虚线灰态），后接 `/ 技能`、`@ 引用`、`附件` 三枚弱化胶囊（12px，`--lw-surface` 底、`--lw-border` 边，悬停转 `--lw-primary-ink` + `--lw-secondary` 边 + `--lw-primary-tint` 底，按下 `scale(0.95)`）；中：`Enter 发送 · Shift + Enter 换行` 灰字，流式时替换为 `Esc 中断 · 仍可继续输入下一条`；右：字数计数（等宽，`>= 90%` 上限转 `--lw-warning-ink`）与 `LwButton` 主按钮。窄屏（`<= 1024px`）隐藏中间提示文案，底栏允许换行 |
| 发送 / 中断 | 发送键是 34px 圆形描边键（`.lw-send`，2px `--lw-primary` 边 + `--lw-primary-ink` 图标），五态齐全：悬停底转 `--lw-primary-tint` 并 `scale(1.06)`，按下 `scale(.94)` 底转 `--lw-secondary-tint`，禁用转 `--lw-border` 边 + `--lw-ink-disabled` 图标且去形变；流式中就地换成同尺寸的错误色方形停止键（`.is-stop`），位置与尺寸不变，不产生布局跳动。中断入口是**常驻**的，不是悬浮出现 |
| 唤出面板 | 绝对定位于输入框上方 8px，`--lw-radius-card` + `--lw-border` + `--lw-shadow-md`，最大高 300px 内部滚动；头部 `lw-label` 标题 + 键盘提示；条目 8/10 内边距，当前项 `--lw-primary-tint` 底 + `--lw-primary-ink` 字，条目标题 13px 加粗 + 说明 12px `--lw-ink-muted`；关键字命中片段加粗 |
| 动效 | 面板入场 200ms `--lw-ease-out` 上浮 6px + 淡入，出场 120ms；条目底色切换走 `--lw-dur-fast`；chip 入场 200ms 上浮 + 淡入，删除走 120ms 淡出 |

**键盘契约**（缺一不可）：

| 键 | 行为 |
|---|---|
| `Enter` | 发送；组词中（`isComposing` 或 `keyCode === 229`）不发送 |
| `Shift + Enter` | 换行 |
| `Esc` | 面板开 → 关面板；面板关且流式中 → 中断 |
| `↑` / `↓` | 面板开 → 移动选项；面板关、正文空且光标在首位时 `↑` 回填上一条用户消息（只回填一条：改几个字再发一轮是最高频的续写动作） |
| `/` `@` | 位于行首或空白之后即唤出面板 |
| `Enter` / `Tab`（面板开） | 确认选项 |

**已选项以 chip 为准，不以正文文本为准**：选中技能或引用后，正文里的 `/xxx` `@yyy` 标记**立即移除**，改由 chip 承载并单独随请求提交结构化字段。理由：留在正文里的标记会被用户当成正文的一部分读给模型，也会被编辑操作误删；chip 可以看见、可以点掉，是可预期的。

### 5.12 管理列表页（表格卡）

增删改查类后台页（用户 / 角色 / 菜单 / 文件 / 技能以及后续新增的资源页）不得各写一套卡片加表格，统一走 `components/ui/LwTableCard.vue` + `styles/admin.css`。这是「有 B 端感」的唯一实现面。筛选条由卡片的 `filter` 插槽承载，不为它单设组件。

| 部位 | 规范 |
|---|---|
| 页头 | `.lw-page-head`：标题 24px 加粗 + 副标题 14px `--lw-ink-muted`，右侧只放一枚主操作（新建）；次级动作下沉到筛选条 |
| 容器 | `.lw-tablecard`：白底 `--lw-surface-raised` + 1px `--lw-border` + 12px 圆角 + `--lw-shadow-sm`；**卡头 / 筛选条 / 表体 / 分页同属一张卡**，不拆成多张卡 |
| 筛选条 | `.lw-filterbar`：`px-4 py-3`、`gap-2`；控件一律 `h-10`，查询 / 重置按钮 `sm`，主操作靠右；行尾为「更多筛选」`.lw-filterbar__toggle`（文字按钮形态，**有生效条件时打点**，否则收起后看不出筛选还在生效），展开行为 `.lw-filterbar__adv`（`grid-template-rows: 0fr → 1fr`，收起时不占位） |
| 卡头 | `.lw-tablecard__head`：左侧资源名（14px 加粗）+ 条数徽标 `.lw-tablecard__count`（`--lw-muted-tint` 底、11px 加粗等宽数字，如 `共 128 条`，总数为 0 时不显示）；右侧**表格工具栏** `.lw-toolbar`：刷新 / 密度 / 全屏三枚 28px 图标按钮（`--lw-ink-muted`，悬停转 `--lw-primary-ink` + `--lw-primary-tint` 底 + `scale(1.06)`，按下 `scale(.94)`），再右侧可选业务动作。卡头下 1px 分隔线 |
| 表头 | 12px 加粗 `--lw-ink-muted` + `--lw-surface` 浅底 + 1px 底线；行高 44px，悬停 `--lw-primary-tint`，**禁斑马纹** |
| 密度 | 三档：`默认 44px` / `中等 36px` / `紧凑 28px`，存 `lw-table-density`；只改行高与单元格上下内边距，不改字号 |
| 单元格 | 三档文本：主文本 14px/600（`.lw-cell-title`）、次文本 12px `--lw-ink-muted`（`.lw-cell-sub`）、标识类等宽（`.lw-cell-mono`）；同一列内不混用字号 |
| 操作列 | 固定右侧，`.lw-rowacts` 内 `gap-1`，按钮 `text sm`；常规动作与破坏性动作之间加 `.lw-rowacts__sep`（1px × 14px 竖线）；删除 / 重置类一律 `lw-text-danger`；动作超过 4 项时前三项直出，其余收进「更多」下拉（`LwRowActions` 的 `overflow` 插槽），不是把整列操作都塞下拉 |
| 批量 | `LwBatchBar`：选中不为空时占用筛选条下方位置（`--lw-secondary-tint` 底 + 主靛已选数 + 批量动作 + 取消），**未选中不占位**；批量破坏性动作带二次确认且文案包含数量；返回 `BatchResultVO`，全成功报总数，部分失败逐条列原因并刷新列表 |
| 分页 | `.lw-pager` 右对齐 + 上 1px 分隔线，`layout="total, sizes, prev, pager, next"`；不足一页时不渲染 |
| 三态 | loading → `LwSkeleton` 骨架行（3–6 行）；error → `LwErrorBar` 内联 + 重试；empty → `LwEmpty` 居中，不留白板。**空态以 `empty` 布尔为准**（调用方传 `:empty="!list.length"`），`emptyText` 只给文案——用「文案非空」当判空条件会让表体永远被空态占位，表现为后端有数据而页面空白 |
| 对话框 | 新建 / 编辑用 `el-dialog`，字段垂直 `gap-4`，标签 `.lw-label` 在控件上方；破坏性确认走 `ElMessageBox`，`type: 'error'`，确认按钮文案为具体动作 |

规则：一个资源页 = 页头 + 一张 `LwTableCard`。禁止把筛选条单独包成 `LwCard`——那会把「筛选」和「它筛选的表格」分成两张卡，是最典型的模板拼凑感来源。页头是**全页唯一**主操作位：卡头只有资源名 + 计数 + 工具栏，不再重复标题与新建按钮，否则同一动作在一屏里出现两次。

**与仪表盘的分工**：指标卡、图表、环形图只用 `--saas_dashboard-color-1…5` 序列（见 §8）；管理列表页禁用彩色底、彩色图标磁贴与大色块，只使用靛蓝做选中与悬停——**数据展示页允许有颜色，CRUD 表格页需保持安静**。这一条决定两类页面一眼能分开。

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
- 环形图：内外半径 `58% / 78%`，分片间隔 2px `--lw-surface-raised` 描边、4px 圆角；图例置底、圆形色块 8×8、11px `--lw-ink-muted`；配色按序取 主靛 / 辅助靛 / `--lw-ink` / `--lw-ink-disabled` / 语义色。
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
| 对比度 | 正文对底 ≥ 4.5:1；`--lw-on-secondary` 专供 `--lw-secondary` 底，不得用于主靛底 |
| 语义 | 图标按钮必须有 `aria-label`；加载态按钮带 `aria-busy="true"` |
| 动效 | 尊重 `prefers-reduced-motion`，命中时关闭缩放与位移动画，仅保留颜色过渡 |

## 11. 页面范式

### 11.1 三条硬规则

| 规则 | 说明 |
|---|---|
| 色不出 SaaS 五色序列与中性色 | `#6366f1` `#818cf8` `#e0e7ff` `#f8fafc` `#1e293b` 加中性 `#ffffff` / `#f1f5f9` / `#e2e8f0` / `#94a3b8` / `#64748b`。语义色只在 success / warning / error（含 info）出现 |
| 层级靠背景差 + 1px 边框，输入控件另加粗 | 不用渐变堆叠、不用彩色阴影。页面 `#f8fafc`，卡片 `#ffffff` + `1px #e2e8f0` + `12px` 圆角；输入类控件 `1.5px #818cf8`，绝不与分隔边框同色同粗 |
| 每一处可交互都有形变反馈 | 悬停 `scale(1.05)`、按下 `scale(0.95)`、加载锁宽、禁用去形变。不存在「点了没反应」的元素 |

### 11.2 品牌展示屏（登录页 / 落地页）

背景落两枚**弥散光斑**，取代一切插画与大图：

```html
<div class="absolute top-[-20%] left-[-10%] w-[500px] h-[500px] rounded-full blur-[100px] opacity-20"
     style="background-color: var(--lw-secondary); pointer-events: none"></div>
<div class="absolute bottom-[-10%] right-[-10%] w-[400px] h-[400px] rounded-full blur-[100px] opacity-20"
     style="background-color: var(--lw-primary); pointer-events: none"></div>
```

光斑必须 `pointer-events: none` 且被内容层 `z-10` 压住。页面背景 `--lw-surface`，无大图无渐变。整体为左右双列：左侧品牌区、右侧凭证卡（`max-w-[400px]`）；`lg` 以下纵向堆叠，品牌区能力卡在 `lg` 以下收起。

品牌区：

- 品牌方块 48px、`--lw-secondary` 底 + `--lw-on-primary` 图标 + `--lw-shadow-sm`，悬停放大 1.05；旁接产品名（24px 加粗 `--lw-ink`）。
- 徽标 pill：白底 + 1px `--lw-border` + `--lw-secondary` 字 + 星形图标，圆角全圆，`px-3 py-1.5` 12px 加粗，悬停加 `--lw-shadow-sm`。
- 大标题两行：第一行 `--lw-ink`（主靛）、第二行 `--lw-secondary`，`text-4xl lg:text-5xl font-extrabold tracking-tight leading-tight`。
- 定位描述 `text-base`、`--lw-ink-muted`、`max-w-[480px]`。
- 能力卡（纵向 `gap-3`，`max-w-[460px]`）：白底 + 1px 边框 + 12px 圆角，左侧 36px 图标瓷砖（`--lw-primary-tint` 底 + `--lw-primary` 图标），标题 14px `--lw-ink`、描述 12px `--lw-ink-muted`；悬停上移 2px 并加 `--lw-shadow-md`。

凭证卡（`lw-card p-6 flex flex-col gap-5`）：

- 欢迎语 18px 加粗 + 一句说明 12px `--lw-ink-muted`；字段间距 16px。
- 输入框 `h-10 px-3 rounded-control`，聚焦时边框转主靛并加 `--lw-shadow-sm`；密码显隐按钮悬停黑 5% 叠加、按下缩小。
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
- 色板卡：`h-16 rounded-lg` 大色块 + 下方 `font-mono text-xs` 行（左色值右名称，`--lw-ink-muted`），展示主靛 `#6366F1`、辅助靛 `#818CF8`、淡底 `#E0E7FF`、中性 `#1E293B` 四块，色值取自令牌实时读取。
- 按钮卡展示五态：DEFAULT / HOVER（放大 + 加深 + 阴影）/ PRESSED（缩小 + 再加深）/ DISABLED / LOADING，逐态可点；次要按钮与幽灵按钮各占一格。
- 交互控件卡展示 `.lw-field` 与 EP 输入两类控件：默认态 `1.5px #818cf8` 边框、聚焦态 `#6366f1` 边框 + 淡底光晕，下方跟复选框 / 单选 / 开关三件套（`el-checkbox` / `el-radio-group` / `el-switch`，配色吃 `--el-color-primary` 主题映射，不自绘）。
- 语义反馈用 `LwTag`（六 tone）与 `LwErrorBar`，**不使用 Element Plus Alert**。
- 图表样例用 `LwAreaChart`（ECharts），不自造 canvas 图形。
- 标签统一 `LwTag` 形态（`px-2 py-0.5 rounded-tag(6px) text-xs font-bold`），不另造标签样式。

### 11.5 观测与过程可视化

时间线之外的三类「过程可读」组件，用同一套令牌，不再引入新视觉语言。

| 组件 | 形态 | 关键约束 |
|---|---|---|
| 链路瀑布（`TraceWaterfall`） | 每行 = 一个片段：左标签 / 中轨道（12px 高，底 `--lw-border`）/ 右指标（状态 Tag + 耗时 + Token） | 条用主靛（模型）与辅助靛（工具），错误用 `--lw-error-ink`、待收尾用 `--lw-warning-ink`；宽度按 `offsetMs / totalMs` 百分比、最小 0.8%；悬停整行铺 `--lw-primary-tint`，待收尾行铺 `--lw-warning-tint`；不做缩放与拖拽 |
| Run 树（`RunTree`） | 缩进列表 + 一条竖连接线标层级 | 行高 36px（h-9），子行左内缩 1.25rem/级；选中态 `--lw-secondary-tint` 底 + `--lw-secondary` 边；层级只用缩进与连接线，不加重型树控件 |
| 用量归因（`UsagePanel`） | 指标卡 ×4 + 柱状趋势 + 占比条 | 柱宽自适应、间隔 `gap-1`、无坐标轴，柱底 `--lw-secondary`；占比条底 `--lw-border`，条按序号在主靛/辅助靛间交替；数字一律 `font-mono` 且 `k` / `M` 简写 |
| 计划清单（`PlanChecklist`） | 顶部标题 + 进度条 + 步骤列表 | 卡片底 `--lw-secondary-tint`、边 `--lw-secondary`；完成步骤 `--lw-ink-muted` + 删除线，进行中主靛加粗；进度条 500ms 过渡 |

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
│ 概览条：级别计数 + 迷你直方图，一行紧凑     │
├──────────────────────────────────────────┤
│ 过滤区：主行（字段 + 关键字 + 级别 + 快捷   │
│ 时间）+ 可展开高级行（记录器 / 链路 /       │
│ userId / runId / 自定义起止）+ 查询         │
├──────────────────────────────────────────┤
│ 结果列表：行内高亮 + 两行预览 + 展开全文    │
│ 分页器：total + sizes + prev/pager/next   │
└──────────────────────────────────────────┘
```

- 概览条与直方图随过滤条件联动刷新：三个级别计数（圆点 + 标签 + 数字）与迷你直方图并作一行，统计只占一行高度，不做半屏大卡；直方图为内嵌细柱（`--lw-secondary`，圆角顶部 3px），悬停显示时间与条数，点击钻取。
- 快捷时间预设：15 分钟 / 1 小时 / 24 小时 / 7 天，选中态浅靛底 + 主靛字；自定义时间收进高级行，与快捷时间任选其一。
- 结果行：级别用 `LwTag`（INFO 主靛 / WARN 警告 / ERROR 错误），时间与行号等宽字体，正文单行省略（折叠态不截断短消息，长消息省略号收住）；点击展开显示完整正文（等宽 `pre` 保留换行）+ `traceId` / `logger` / `thread` / `userId` / `runId` 元信息（`dl` 定义列表，字段名禁用色 + 值等宽）与异常栈（异常区 `--lw-error-tint` 底 + `--lw-error-ink` 字）；行尾箭头随展开旋转 180°，含异常栈的行折叠态即有「异常栈」标签。
- **日志流的排版纪律**：表头与数据行共用同一套 CSS Grid 栅格（`grid-template-columns` 只定义一次），列宽不随内容漂移，不会出现表头与行左右错位；统一等宽字体、统一 12.5px 字号与 1.5 行高、数字一律 `tabular-nums`；奇偶行 `--lw-surface` 微差，悬停 `--lw-primary-tint`，展开 `--lw-secondary-tint`；窄屏（`<= 900px` 收起时间列，`<= 640px` 再收级别与异常列）保留「级别 + 正文」两列。
- 关键字命中在正文内高亮：`<mark>` 浅靛底圆角 2px，不使用浏览器默认黄色。
- 钻取动作：复制 `traceId`（图标 400ms 切对勾 + toast）、按该 `traceId` 过滤（一键填入过滤区并刷新）。
- 过滤区主行之下常驻一行简短说明（12px `--lw-ink-muted`）：关键字匹配规则与时间二选一；完整规则说明不放首屏。

### 12.2 对话时间线（工作台）

```
用户回合：右对齐，主靛实底气泡，圆角 12（右上 4），时间贴右下
助手回合：左对齐，头像列 + 一根竖轨，回合内所有内容挂在轨上
  思考块：可折叠，标题行"思考过程"+ 呼吸点，展开为 12px 次级文本
  计划清单：plan.updated 覆盖式快照
  工具卡：白卡 1px 边框，头部 = 工具名 + 状态 Tag + 耗时，
          展开显示输出（超 4KB 截断并提示）
  正文：裸 markdown 渲染，不套气泡；流式中末尾光标闪烁
  呼吸态：正文与过程都未到时三颗跳动点 + "正在组织回答"
  用量行：11px 等宽次级色，N 次模型调用 · 累计 Token
```

- 时间线按事件 `seq` 严格保序渲染，不做前端重排。
- **回合分组**：相邻的 assistant 片段合并成一个回合（用户消息永不合合——把两次提问读成一句会丢语义）；合并时 `tools` / `plan` 必须拷贝，否则会把数组引用带回 store 反向改写源数据。
- **竖轨**（`.tl-turn--a::before`）：1px `--lw-border` 从头像底边贯到回合底，思考 / 计划 / 工具 / 正文全挂在同一根线上。没有这根线时，一屏里六七个各带边框的元素读起来是互不相干的流水账，看不出"同一次动作的几个步骤"。
- **入场动效**：每个回合 `translateY(8px) + 淡入`，200ms `--lw-ease-out`；历史首屏渲染带同一动画，切回会话时整屏一次性淡入而不是"啪"地出现；流式重渲染不重建元素，动画不会重放。
- **流式光环**：流式期间头像外圈 3px `--lw-secondary-tint`，且正文末尾光标 `--lw-primary-ink` 1s 步进闪烁。
- **思考块**（`ThinkingBlock`）：`--lw-surface-subtle` 底 + 8px 圆角 + 左侧 3px `--lw-secondary` 竖条；标题行 12px 加粗 `--lw-primary-ink` + 折叠箭头（200ms 旋转）+ 流式中 `--lw-dur` 脉冲点；展开区 12px `--lw-ink-muted`、最大高 240px 滚动。默认折叠，流式期间自动展开。
- **工具卡**（`ToolStepCard`）：白底 + 1px `--lw-border` + 8px 圆角；头部一行 = 20px 状态图标（执行中旋转指示器 / 完成对勾 / 失败错误图标，均在 `--lw-dur` 内过渡）+ 工具中文名（12px 加粗）+ `LwTag` 状态 + 右侧展开箭头；展开区为 `--lw-surface-subtle` 底 + 6px 圆角 + 12px 等宽输出，最大高 260px 滚动，超 4KB 截断并提示。错误态整卡转 `--lw-error-tint` 底 + `--lw-error` 边。
- **滚动**：新帧进入即粘底（仅在用户已处于底部时生效，用户向上翻阅时不抢滚动）；离底超过 200px 时右下角浮出 36px 圆形「回到底部」按钮（`--lw-surface-raised` 底 + `--lw-shadow-md` + 悬停放大 1.05）。
- 回放态：顶部通栏提示条（`--lw-secondary-tint` 底 + 主靛字）+「返回实时」按钮，与实时态视觉可区分。
- 输入区：见 §5.11。

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

- 运行状态 Tag 色：`success` 语义绿 / `failed` 错误色 / `aborted` 中性灰 / `running`·`queued` 主靛 / 其余警示色。
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
| 表头 | 12px 加粗、`--lw-ink-muted`、`--lw-surface` 浅底、底线 1px `--lw-border` |
| 行 | 正文 14px，行高 44px（密度可调 44/36/28），悬停 `--lw-primary-tint`，斑马纹禁用 |
| 操作列 | 固定右侧，按钮 `sm` 档，组内间距 8px，等高对齐；超过 4 项收进「更多」下拉 |
| 状态列 | 统一 `LwTag`，同状态同色（见语义色表） |
| 空态 | 表格内 `LwEmpty`（图标 + 说明 + 主操作），不留白板 |
| 加载 | 骨架行（3–5 行），不做整页遮罩 |
| 分页 | 右对齐：总数 + 每页条数 + 上一页 / 页码 / 下一页；页码选中态主靛底白字 |

**对齐纪律**：同一筛选行的控件统一 `h-10`（按钮 `md`、输入 `md`、下拉 `md`），标签文本与控件水平基线对齐；表单页标签统一左对齐固定宽度；不同页面同类元素尺寸一致，严禁同屏混用 `sm` / `md`。

实现面：表格卡骨架（卡头 / 工具栏 / 筛选条 / 表体 / 分页同一张白卡）、单元格三档字体与操作列分组全部在 `components/ui/LwTableCard.vue` + `styles/admin.css`，详见 §5.12。

### 12.6 仪表盘（控制台首页）

```
页头：问候语 + 一句话现状          右侧：刷新 / 去工作台
指标卡 ×3（今日运行 / 累计 Token / 成功率）
快捷入口条：四个磁贴（开新会话 / 新建文档 / 传文件 / 建知识库）
主图卡：运行趋势面积图 h-300
副图卡 ×2：Token 趋势柱状图 + 工具分布环形图
最近运行表：5 行，整行可点，右上「查看全部」
```

- **快捷入口条**：一行四枚磁贴（`lw-card p-4`，左 36px 图标磁砖 + 标题 14px + 说明 12px），悬停上移 2px + `--lw-shadow-md` + 图标磁砖 `scale(1.06)`。四个入口（工作台 / 创作台 / 文件 / 知识库）都是登录即可用的页面，因此不做权限判定，四枚恒定等宽出现。
- **最近运行表**：5 行紧凑表（无分页），表头与数据行共用同一套栅格（`150px / 92px / 1fr / 84px / 92px`），列为「时间 / 状态 Tag / 模型 / 耗时 / Token」；整行可点，进 `/runs?runId=…` 由运行记录页直接选中。卡头右侧放一枚「查看全部」文字按钮。无运行时 `LwEmpty` 带一句说明，不留白板。
- **首次使用引导**（`runCount === 0`）：一枚引导卡放在指标卡之下、快捷入口之上，一句说明加两枚动作（去建知识库 / 去工作台）；有运行后不再渲染。
- 指标卡、图表配色一律取 §2.1 五色序列与 §8 规则；不与表格页混用彩色底。
- 概览与最近运行各自并发、各自三态：任一区失败只坏该区，不拉倒整页。

## 13. 暗色模式

只交付浅色主题，不提供 `data-theme` 切换的半成品；暗色主题落地时以本文件令牌表为唯一改造面。

## 14. 视觉验收

设计规范落地后按本节自检。每条判据都能在本文件找到唯一出处，验收逐项对账，不靠主观判断。

### 14.1 外部设计稿色值到令牌的强制映射

外部设计稿一旦出现下列非令牌色，一律按下表替换，禁止照抄：

| 设计稿取值 | 替换为 | 说明 |
|---|---|---|
| `slate-900` 实心按钮 | `LwButton variant="primary"`（`--lw-primary` 底 + `--lw-on-primary` 字） | 深底按钮不用中性黑，主靛即品牌 |
| `slate-50` / `slate-100` / `slate-200` | `--lw-surface` / `--lw-surface-subtle` / `--lw-border` | 中性灰只有三档，全部走令牌 |
| `slate-900` 实心「桌面端 / 移动端」开关 | `LwSegmented` 或 `LwButton variant="secondary"` | 设备态切换不是品牌动作，不用深底实心 |
| `emerald-500` 上升指标 | `--lw-success-ink`（`#047857`） | 语义文字色，不用鲜绿 |
| `amber-500` 视图开关选中态 | `--lw-primary-tint` 底 + `--lw-primary-ink` 字 | 选中态统一走 `LwSegmented`（§5.2） |
| `blue-600` 分段选中态 | `--lw-primary-ink` 字 + `--lw-surface-subtle` 底 | 同上，禁止引入蓝色系 |
| `#818cf8` 之上的 `color: #000` 文字 | `--lw-on-secondary`（`#1e293b`） | 深字 + 浅底才达 5:1，纯黑在浅靛上过硬 |
| `dark:` 变体 | 一律删除 | 只交付浅色主题（§13） |

### 14.2 五态与三态验收

| 检查项 | 判据 |
|---|---|
| 按钮五态 | DEFAULT / HOVER（`scale(1.05)` + 加深）/ PRESSED（`scale(.95)` + 再加深）/ DISABLED（无 transform、无阴影）/ LOADING（锁宽、指示器可见、不落原生 `disabled`）逐项可复现，见 §5.1 |
| 异步三态 | 每个异步区同时具备 loading（骨架或行内指示器）、error（内联错误条 + 重试）、empty（`LwEmpty` + 一句说明 + 主操作），见 §6 |
| 图表 | 面积图渐变 `5%:0.2 → 95%:0`、网格线 `dashed opacity .5`、轴标签 10px `--lw-ink-muted`、tooltip 白底 1px 边框圆角 8，见 §8 |
| 层级 | 只用背景差 + 1px 边框 + 两级阴影，无渐变堆叠、无彩色阴影，见 §11.1 |
| 管理列表页 | 页头 + 一张表格卡；卡头（资源名 + 计数 + 工具栏）/ 筛选条 / 表体 / 分页同属一卡；操作列竖线分组破坏性动作，见 §5.12 |
| 外壳 | 桌面侧栏可折叠为 64px 图标栏（折叠态有 tooltip 与选中竖条）；面包屑仅两级且有上级可点时渲染，见 §4.1 / §4.2 |
| 表格工具栏 | 刷新 / 密度 / 全屏三枚图标按钮齐全且可用，密度三档持久化，见 §5.12 |
| 批量操作 | 用户 / 文件 / 技能三个资源页：选中行出现批量条（未选中不占位），批量删除二次确认带数量，部分失败给出逐条原因；刷新后选中清空 |
| 更多筛选 | 有生效条件时按钮打点，收起后仍能看出筛选在生效；重置会一并清空高级条件 |
| 仪表盘 | 指标卡 + 快捷入口条 + 主图卡 + 副图卡 ×2 + 最近运行表，配色只取五色序列，见 §12.6 |
| 页面分工 | 数据展示页（仪表盘 / 监控）允许彩色磁贴与图表，CRUD 表格页保持安静（只有靛蓝选中与悬停），见 §5.12 末段 |
