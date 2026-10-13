# DESIGN.md

视觉语言、设计令牌、交互状态机。实现以本文为准，色值只允许出现在 `frontend/src/styles/tokens.css`。

---

## 1. 设计原则

| 原则 | 落地方式 |
|---|---|
| 极简 | 无装饰性渐变与阴影堆叠，层级靠背景差与 1px 边框建立 |
| 克制配色 | 四色紫色序列走完全站，语义色只在必要时出现 |
| 可预期 | 所有可点击元素有五态，所有异步操作有三态 |
| 有反馈 | 悬停、按下、加载、成功、失败均有过渡动画，无静默操作 |

## 2. 品牌令牌

```css
:root {
  --purple_sequential-color-1: #F2F0F7;   /* 浅底 */
  --purple_sequential-color-2: #CBC9E2;   /* 辅助浅紫 */
  --purple_sequential-color-3: #9E9AC8;   /* 辅助紫 */
  --purple_sequential-color-4: #6A51A3;   /* 主紫 */
}
```

语义别名（业务代码只用别名，不用原始令牌）：

| 别名 | 取值 | 用途 |
|---|---|---|
| `--lw-primary` | `var(--purple_sequential-color-4)` | 标题、正文、主按钮底、图表次序列 |
| `--lw-primary-hover` | `#5A448C` | 主色悬停 |
| `--lw-primary-active` | `#4A3A73` | 主色按下 |
| `--lw-primary-tint` | `rgba(106,81,163,.08)` | 主色淡底 |
| `--lw-secondary` | `var(--purple_sequential-color-3)` | 侧栏选中、图表主序列、强调底色 |
| `--lw-secondary-hover` | `#8A85B8` | 辅助紫悬停 |
| `--lw-secondary-tint` | `rgba(158,154,200,.14)` | 辅助紫淡底 |
| `--lw-secondary-selected` | `rgba(158,154,200,.10)` | 侧栏选中底 |
| `--lw-accent-soft` | `var(--purple_sequential-color-2)` | 图表第三序列、分隔强调 |
| `--lw-surface-subtle` | `var(--purple_sequential-color-1)` | 头像底、选中按钮底、品牌卡 |
| `--lw-surface` | `#F8FAFC` | 页面底色 |
| `--lw-surface-raised` | `#FFFFFF` | 卡片、侧栏、顶栏 |
| `--lw-surface-disabled` | `var(--purple_sequential-color-1)` | 禁用底 |
| `--lw-border` | `#E2E8F0` | 默认边框 |
| `--lw-border-strong` | `var(--purple_sequential-color-2)` | 强调边框 |
| `--lw-ink` | `var(--purple_sequential-color-4)` | 主文本 |
| `--lw-ink-muted` | `#64748B` | 次级文本 |
| `--lw-ink-disabled` | `#94A3B8` | 禁用文本 |
| `--lw-on-primary` | `#FFFFFF` | 深底之上的文字 |
| `--lw-on-secondary` | `#2E2536` | `#9E9AC8` 之上的文字 |
| `--lw-overlay` | `rgba(15,23,42,.45)` | 抽屉遮罩 |
| `--lw-shadow-sm` | `0 1px 2px rgba(15,23,42,.06)` | 卡片静止 |
| `--lw-shadow-md` | `0 4px 12px rgba(15,23,42,.12)` | 浮层与悬停卡片 |

动效令牌（所有过渡只允许引用这三个）：

| 令牌 | 取值 | 用途 |
|---|---|---|
| `--lw-dur-fast` | `120ms` | 颜色、透明度切换 |
| `--lw-dur` | `200ms` | 位移、缩放、阴影 |
| `--lw-dur-slow` | `300ms` | 抽屉、弹窗、路由淡入 |
| `--lw-ease` | `cubic-bezier(.4,0,.2,1)` | 统一缓动 |

## 3. 语义色

| 语义 | 色值 | 用途 |
|---|---|---|
| success | `#5ABF8A` | 运行成功、校验通过 |
| warning | `#D6A85C` | 需要确认、资源吃紧 |
| error | `#D46A6A` | 运行失败、请求异常 |
| info | `var(--lw-primary)` | 提示、信息 |

语义色只做**低饱和**处理，避免与品牌紫抢注意力；文字态用 `--lw-*-ink` 加深。

## 4. 排版与间距

| 项 | 规则 |
|---|---|
| 字号 | 页面标题 24 / 卡片标题 16 / 正文 14 / 辅助 12 / 标签 10 |
| 字重 | 标题 700，正文 400，强调 600 |
| 圆角 | 卡片 12px，控件 8px，标签 6px，头像与徽章全圆 |
| 间距 | 4 的倍数：4 / 8 / 12 / 16 / 24 / 32 |
| 阴影 | 仅两级，见上表 |

## 5. 布局骨架

```
┌──────────────────────────────────────────┐
│ TopBar  h-16  白底  下边框                │
├────────┬─────────────────────────────────┤
│ Sidebar│ Main                            │
│ w-52   │ p-6  flex-col gap-6             │
│ 白底   │  ├ 页头（标题 + 操作区）         │
│ 右边框 │  ├ 指标卡网格（1/3 列）          │
│        │  └ 内容区（图表 / 列表 / 时间线）│
└────────┴─────────────────────────────────┘
```

- 顶栏左侧为品牌方块（`--lw-secondary` 底 + 白图标）与产品名，右侧为语言切换与用户区（圆形头像 `--lw-surface-subtle` 底 + `--lw-primary` 字）。
- 侧栏顶部为菜单组（分组标题 10px 大写、`--lw-ink-muted`），一级菜单项 `h-10` 圆角、二级子项 `h-9`，悬停为黑色 5% 叠加，选中为 `--lw-primary` 文字 + `--lw-secondary-selected` 底；底部固定品牌卡（`--lw-secondary` 底 + `--lw-on-secondary` 字，悬停轻微放大）。
- 主区滚屏，侧栏与顶栏固定；断点 `lg` 以下侧栏收起为抽屉（遮罩 `--lw-overlay`）。
- 指标卡：白底 + 1px 边框 + 12px 圆角，右上角 56px 图标 `opacity-10`，悬停时图标放大、卡片上移并加 `--lw-shadow-md`。

## 6. 按钮五态

所有可点击元素必须实现五态，过渡统一 `transition-all duration-200`。

| 状态 | 视觉 | 动效 |
|---|---|---|
| DEFAULT | 主色底白字（主按钮）/ 白底描边（次按钮） | — |
| HOVER | 主色加深 + `--lw-shadow-md` | `scale(1.05)` **放大** |
| PRESSED | 主色再加深，阴影收起 | `scale(.95)` **缩小** |
| DISABLED | `--lw-surface-disabled` + `--lw-ink-disabled` | `cursor-not-allowed`，无 transform |
| LOADING | 保留原底色，文字替换为旋转指示器 | 宽度锁定，屏蔽点击，悬停不放大 |

```css
.lw-btn { transition: all var(--lw-dur) var(--lw-ease); }
.lw-btn:hover:not(:disabled):not(.is-loading)  { transform: scale(1.05); box-shadow: var(--lw-shadow-md); }
.lw-btn:active:not(:disabled):not(.is-loading) { transform: scale(.95);  box-shadow: none; }
.lw-btn:disabled,
.lw-btn.is-disabled { background: var(--lw-surface-disabled); color: var(--lw-ink-disabled); transform: none; }
.lw-btn.is-loading { pointer-events: none; }
.lw-btn.is-loading:hover,
.lw-btn.is-loading:active { transform: none; box-shadow: none; }
```

**规则**：HOVER 放大、PRESSED 缩小，形成明确点击感；LOADING 不落原生 `disabled`，保持主色底与可见指示器（深底变体用 `--lw-on-primary`），宽度不变避免布局跳动。

按钮变体与尺寸：

| 变体 | 静止 | HOVER | PRESSED |
|---|---|---|---|
| `primary` | `--lw-primary` 底 + 白字 | `--lw-primary-hover` | `--lw-primary-active` |
| `secondary` | 透明底 + `--lw-secondary` 描边与文字 | `--lw-secondary-tint` 底 | 文字转 `--lw-secondary-hover` |
| `ghost` | 透明底 + `--lw-primary` 描边与文字 | `--lw-primary-tint` 底 | 描边加深 |
| `danger` | `--lw-error` 底白字 | `--lw-error-hover` | `--lw-error-active` |
| `text` | 无底色主色文字 | `--lw-primary-tint` 底 | 主色加深 |

尺寸：`sm`（h-8 / text-xs）、`md`（h-10 / text-sm，默认）、`lg`（h-12 / text-base）。

**落地方式**：全站按钮唯一实现是 `components/ui/LwButton.vue`（props：`variant` / `size` / `loading` / `disabled` / `block` / `nativeType`）。业务代码禁止直接调用 Element Plus `el-button`；Element Plus 内部组件（确认弹窗、表格操作等）自带的按钮由 `styles/index.css` 全局覆盖为同等五态（含 disabled / loading 视觉）。

### 6.1 分段切换（Segmented）

视图 / 时间范围 / 视角的组内单选一律用 `components/ui/LwSegmented.vue`，禁止各页自造 tab 或 pill：

```
容器：白底 + 1px --lw-border + 8px 圆角 + 4px 内边距，组内按钮等高，容器高 40px（与 h-10 控件对齐）
项  ：px-3 py-1.5，12px 加粗；未选中 --lw-ink-muted，悬停转 --lw-ink
选中：--lw-surface-subtle 底（或浅紫底）+ --lw-primary 字 + 轻阴影
动效：选中底 `--lw-dur-fast` 淡入，悬停颜色 `--lw-dur-fast` 过渡
```

| 规则 | 说明 |
|---|---|
| 组内同宽 | 短标签组内等宽（视图切换），长标签按内容自适应；同一页面同级别分段切换尺寸一致 |
| 单项禁用 | `--lw-ink-disabled` + `cursor-not-allowed`，无悬停反馈 |
| 计数徽标 | 可选项带计数时用 10px 徽标（`--lw-primary-tint` 底 + 主紫字），不改变项高 |

## 7. 异步三态

| 态 | 表现 |
|---|---|
| loading | 骨架屏（列表/卡片）或行内旋转指示器（按钮） |
| error | 内联错误条 + 重试按钮，不弹全屏遮罩 |
| empty | 插画 + 一句说明 + 主操作按钮，不留白板 |

## 8. 反馈与过渡

| 场景 | 反馈 |
|---|---|
| 写操作成功 | 右上角 toast（`--lw-dur-slow` 滑入），2.5s 自动消失 |
| 写操作失败 | toast 错误态，可展开查看错误码；表单弹窗保留输入 |
| 破坏性操作 | 确认弹窗，主按钮为 error 色，取消按钮为 ghost |
| 流式输出 | 逐字追加，光标闪烁；中断按钮常驻 |
| 路由切换 | 顶栏进度条 + 内容区 `--lw-dur` 淡入 |
| 列表加载 | 骨架屏，不做整页 loading |
| 提交进行中 | 按钮进入 LOADING，所在卡片禁止二次提交 |
| 悬停说明 | 图标按钮必须带 tooltip（延迟 300ms），文字按钮可省 |
| 复制成功 | 图标 400ms 内从复制态切换为对勾态，并 toast 提示 |
| 表单校验 | 失焦即校验，错误文案在字段下方，边框转 error 色 |
| 网络中断 | 顶栏红条提示，恢复后自动消失 |
| 空态 | 图标 + 一句说明 + 主操作按钮 |

**规则**：不存在无反馈的交互。任何持续超过 300ms 的等待必须有 loading 或骨架；任何状态变化必须有过渡动画或颜色变化。

## 9. 图表约定

- 主序列 `#9E9AC8`，次序列 `#6A51A3`，第三序列 `#CBC9E2`。
- 面积图带渐变填充：`5%` 处 `opacity .2` → `95%` 处 `opacity 0`。
- 网格线 `#E2E8F0` + `dasharray 3 3` + `opacity .5`。
- 轴标签 `#64748B`，字号 10。
- 不放标题以外的图例装饰，tooltip 白底 1px 边框 圆角 8。
- 顶部提供视图分段切换（运行趋势 / Token 趋势），选中态为浅紫底 + 主紫文字 + 轻阴影。

## 10. 菜单与标签渲染

- 菜单标签一律经 `utils/menu.ts#menuLabel(name)`：命中 i18n 用译文；未命中时把 key 末段拆驼峰为词（`menu.systemUserCreate` → `System User Create`），**绝不裸显 `menu.xxx` 这样的原始 key**。
- 侧栏目录展开/收起 200ms 旋转箭头；选中项主紫文字 + `--lw-secondary-selected` 底；悬停黑色 5% 叠加。
- 目录下的子项缩进 36px，行高 36px；目录行高 40px。
- 图标只允许线性描边风格（stroke 2、圆角端点、24×24 viewBox），禁止混用实心图标集。

## 11. 焦点与可访问性

| 项 | 规则 |
|---|---|
| 焦点环 | `outline: 2px solid var(--lw-primary); outline-offset: 2px`，只在 `:focus-visible` 出现 |
| 键盘 | 所有可点击元素可 Tab 到达、Enter/Space 触发；弹窗打开即锁焦点并在关闭后归还 |
| 对比度 | 正文对底 ≥ 4.5:1；`--lw-on-secondary` 专供 `--lw-secondary` 底，不得用于主紫底 |
| 语义 | 图标按钮必须有 `aria-label`；加载态按钮带 `aria-busy="true"` |
| 动效 | 尊重 `prefers-reduced-motion`，命中时关闭缩放与位移动画，仅保留颜色过渡 |

## 12. 页面模式

两类高频页面有固定骨架，新页面照此落地，不自创布局。

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
运行终态：成功/失败/中断 Tag + 用量（输入/输出 Token）+ 总耗时
流式中：正文末尾 2px 光标闪烁（--lw-primary），中断按钮常驻输入框上方
```

- 时间线按事件 `seq` 严格保序渲染，不做前端重排。
- 回放态：顶部通栏提示条（`--lw-secondary-tint` 底）+ "返回实时" 按钮，与实时态视觉可区分。
- 输入区：多行自适应文本域，回车发送（组词中不发送），发送按钮 loading 与流式状态联动；超过 300ms 无首字节响应时按钮进入 LOADING 并禁用输入。

### 12.3 登录页

```
┌──────────────────────────────────────────┐
│  居中单卡（max-w-[400px]，白底 + 1px 边框）│
│  品牌方块（--lw-secondary 底 + 白图标）    │
│  产品名 + 一句定位说明（12px muted）       │
│  用户名输入（label 上方，8px 圆角，h-10）  │
│  密码输入（type=password，右侧可切换明文） │
│  登录按钮（primary，block，LOADING 五态）  │
│  错误条（LwErrorBar，内联，不弹 toast）    │
└──────────────────────────────────────────┘
```

- 页面背景 `--lw-surface`，无大图无渐变；卡内间距 24px，字段间距 16px。
- 公钥加载失败显示"安全组件加载失败"内联错误条 + 重试按钮，**绝不降级明文**。
- 提交loading 最短显示 400ms（防闪烁）；成功前按钮保持 LOADING。

### 12.4 运行可观测页（运行记录）

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

- 运行状态 Tag 色：`success` 语义绿 / `failed` 错误色 / `aborted` 中性灰 / `running` 主紫（带呼吸点动画）。
- 工具卡状态：执行中显示 2px 旋转指示器，完成转对勾或错误图标，均有 `--lw-dur` 过渡。
- 回放态：详情区顶部通栏提示条（`--lw-secondary-tint` 底 + "回放中 · 返回实时"），与实时态视觉可区分。
- 空态：`LwEmpty` + "选择左侧运行查看详情"；列表空显示"暂无运行记录" + 去工作台按钮。

### 12.5 错误页

| 页 | 视觉 | 动作 |
|---|---|---|
| 403 | 大号数字（48px，`--lw-secondary`）+ 一句说明"没有访问该页面的权限" | "返回首页"主按钮 |
| 404 | 同上 + "页面不存在或已被移动" | "返回首页" + "回到上一页" ghost |
| 500 | 同上 + "服务开小差了，请稍后重试" | "重试"主按钮（reload）+ "返回首页" |

- 三页共用同一骨架组件，居中、`--lw-surface` 底、无插画堆砌；数字用 `--lw-ink-muted`，说明 14px。
- 路由守卫判定无权限时跳 `/403` 并附上所需权限说明（不暴露资源是否存在）。

### 12.6 数据表格与分页

| 项 | 规则 |
|---|---|
| 表头 | 12px 加粗、`--lw-ink-muted`、白底、底线 1px `--lw-border` |
| 行 | 正文 14px，行高 44px，悬停浅紫底 4%，斑马纹禁用 |
| 操作列 | 固定右侧，按钮 `sm` 档，组内间距 8px，等高对齐 |
| 状态列 | 统一 `LwTag`，同状态同色（见语义色表） |
| 空态 | 表格内 `LwEmpty`（图标 + 说明 + 主操作），不留白板 |
| 加载 | 骨架行（3–5 行），不做整页遮罩 |
| 分页 | 右对齐：总数 + 每页条数 + 上一页 / 页码 / 下一页；页码选中态主紫底白字 |

**对齐纪律**：同一筛选行的控件统一 `h-10`（按钮 `md`、输入 `md`、下拉 `md`），标签文本与控件水平基线对齐；表单页标签统一左对齐固定宽度；不同页面同类元素尺寸一致，严禁同屏混用 `sm` / `md`。

## 13. 暗色模式

当前只交付浅色主题，令牌结构预留 `data-theme="dark"`，不做半成品切换。
