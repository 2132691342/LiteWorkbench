# DESIGN.md

视觉语言、设计令牌、交互状态机。实现以本文为准，色值只允许出现在令牌定义处。

---

## 1. 设计原则

| 原则 | 落地方式 |
|---|---|
| 极简 | 无装饰性渐变与阴影堆叠，层级靠背景差与 1px 边框建立 |
| 克制配色 | 四色令牌走完全站，语义色只在必要时出现 |
| 可预期 | 所有可点击元素有五态，所有异步操作有三态 |
| 有反馈 | 悬停、按下、加载、成功、失败均有过渡动画，无静默操作 |

## 2. 品牌令牌

```css
:root {
  --data_visualization-color-1: #2563EB;  /* 主色 Primary */
  --data_visualization-color-2: #9333EA;  /* 辅助色 Secondary */
  --data_visualization-color-3: #F8FAFC;  /* 浅底 Surface */
  --data_visualization-color-4: #334155;  /* 深字 Ink */
}
```

语义别名（业务代码只用别名，不用原始令牌）：

| 别名 | 取值 | 用途 |
|---|---|---|
| `--lw-primary` | `var(--data_visualization-color-1)` | 主按钮、选中态、图表主序列 |
| `--lw-primary-hover` | `#1D4ED8` | 主色悬停 |
| `--lw-primary-active` | `#1E40AF` | 主色按下 |
| `--lw-primary-tint` | `rgba(37,99,235,.08)` | 主色淡底 |
| `--lw-secondary` | `var(--data_visualization-color-2)` | 次按钮、图表第二序列 |
| `--lw-secondary-hover` | `#7E22CE` | 辅助色悬停 |
| `--lw-secondary-tint` | `rgba(147,51,234,.08)` | 辅助色淡底 |
| `--lw-surface` | `var(--data_visualization-color-3)` | 页面底色 |
| `--lw-surface-raised` | `#FFFFFF` | 卡片、侧栏、顶栏 |
| `--lw-border` | `#E2E8F0` | 默认边框 |
| `--lw-border-strong` | `#CBD5E1` | 强调边框 |
| `--lw-ink` | `var(--data_visualization-color-4)` | 主文本 |
| `--lw-ink-muted` | `#64748B` | 次级文本 |
| `--lw-ink-disabled` | `#94A3B8` | 禁用文本 |
| `--lw-shadow-sm` | `0 1px 2px rgba(51,65,85,.06)` | 卡片静止 |
| `--lw-shadow-md` | `0 4px 12px rgba(51,65,85,.10)` | 浮层与悬停卡片 |

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
| info | `#2563EB` | 提示、信息 |

语义色只做**低饱和**处理，避免与品牌主色抢注意力。

## 4. 排版与间距

| 项 | 规则 |
|---|---|
| 字号 | 页面标题 24 / 卡片标题 16 / 正文 14 / 辅助 12 / 标签 10 |
| 字重 | 标题 700，正文 400，强调 600 |
| 圆角 | 卡片 12px，控件 8px，标签 6px，头像与徽章全圆 |
| 间距 | 4 的倍数：4 / 8 / 12 / 16 / 24 / 32 |
| 阴影 | 仅两级，见下方阴影令牌 |

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

- 侧栏顶部为菜单组，底部固定「升级/存储」类卡片与账号区。
- 主区滚屏，侧栏与顶栏固定。
- 断点：`lg` 以下侧栏折叠为抽屉。

## 6. 按钮五态

所有可点击元素必须实现五态，过渡统一 `transition-all duration-200`。

| 状态 | 视觉 | 动效 |
|---|---|---|
| DEFAULT | 主色底白字（主按钮）/ 白底描边（次按钮） | — |
| HOVER | 主色加深 + `shadow-md` | `scale-105` **放大** |
| PRESSED | 主色再加深，阴影收起 | `scale-95` **缩小** |
| DISABLED | `bg-slate-100` + `--lw-ink-disabled` | `cursor-not-allowed`，无 transform |
| LOADING | 保留主色底，文字替换为旋转指示器 | 宽度锁定，禁用点击 |

```css
.lw-btn { transition: all .2s cubic-bezier(.4,0,.2,1); }
.lw-btn:hover:not(:disabled)  { transform: scale(1.05); box-shadow: var(--lw-shadow-md); }
.lw-btn:active:not(:disabled) { transform: scale(.95);  box-shadow: none; }
.lw-btn:disabled              { cursor: not-allowed; opacity: .6; transform: none; }
.lw-btn.is-loading            { pointer-events: none; }
```

**规则**：HOVER 放大、PRESSED 缩小，形成明确点击感；LOADING 期间保持按钮宽度不变，避免布局跳动。

按钮变体与尺寸：

| 变体 | 静止 | HOVER | PRESSED |
|---|---|---|---|
| `primary` | 主色底白字 | `--lw-primary-hover` + shadow-md | `--lw-primary-active` |
| `secondary` | 辅助色描边 + 辅助色文字 | 辅助色淡底 | `--lw-secondary-hover` 文字 |
| `ghost` | 主色描边 + 主色文字 | 主色淡底 `--lw-primary-tint` | 主色描边加深 |
| `danger` | error 底白字 | error 加深 | error 再加深 |
| `text` | 无底色主色文字 | 主色淡底 | 主色加深 |

尺寸：`sm`（h-8 / text-xs）、`md`（h-10 / text-sm，默认）、`lg`（h-12 / text-base）。

**落地方式**：全站按钮唯一实现是 `components/ui/LwButton.vue`（props：`variant` / `size` / `loading` / `disabled` / `icon`）。业务代码禁止直接调用 Element Plus `el-button`；Element Plus 内部组件（确认弹窗、表格操作等）自带的按钮由 `styles/index.css` 全局覆盖为同等五态。禁用与加载同时为真时，展示 loading 优先。

## 7. 异步三态

| 态 | 表现 |
|---|---|
| loading | 骨架屏（列表/卡片）或行内旋转指示器（按钮） |
| error | 内联错误条 + 重试按钮，不弹全屏遮罩 |
| empty | 插画 + 一句说明 + 主操作按钮 |

## 8. 反馈与过渡

| 场景 | 反馈 |
|---|---|
| 写操作成功 | 右上角 toast（`--lw-dur-slow` 滑入），2.5s 自动消失 |
| 写操作失败 | toast 错误态，可展开查看错误码，附重试按钮 |
| 破坏性操作 | 确认弹窗，主按钮为 error 色，取消按钮为 ghost |
| 流式输出 | 逐字追加，光标闪烁；中断按钮常驻 |
| 路由切换 | 顶栏进度条 + 内容区 `--lw-dur` 淡入 |
| 列表加载 | 骨架屏，不做整页 loading |
| 提交进行中 | 按钮进入 LOADING，所在卡片禁止二次提交（表单 `disabled`） |
| 悬停说明 | 图标按钮必须带 tooltip（延迟 300ms），文字按钮可省 |
| 复制成功 | 图标 400ms 内从复制态切换为对勾态，并 toast 提示 |
| 表单校验 | 失焦即校验，错误文案在字段下方，边框转 error 色 |
| 空态 | 插画 + 一句说明 + 主操作按钮，不留白板 |
| 网络中断 | 全局顶栏红条提示，恢复后自动消失并重连 SSE |

**规则**：不存在无反馈的交互。任何持续超过 300ms 的等待必须有 loading 或骨架；任何状态变化必须有过渡动画或颜色变化。

## 9. 图表约定

- 主序列 `#2563EB`，次序列 `#9333EA`，第三序列 `#F8FAFC` 描边 `#334155`。
- 面积图带渐变填充：`5%` 处 `opacity .2` → `95%` 处 `opacity 0`。
- 网格线 `#E2E8F0` + `dasharray 3 3` + `opacity .5`。
- 轴标签 `#64748B`，字号 10。
- 图表不放标题以外的图例装饰，tooltip 白底 1px 边框 圆角 8。

## 10. 暗色模式

当前只交付浅色主题，令牌结构预留 `data-theme="dark"`，不做半成品切换。
