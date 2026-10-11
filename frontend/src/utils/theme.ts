/** 设计令牌读取：图表等 canvas 渲染拿不到 CSS 变量字符串，需要运行时取色值 */

/** 读取令牌运行值，如 token('--lw-primary') */
export function token(name: string): string {
  return getComputedStyle(document.documentElement).getPropertyValue(name).trim()
}

/** 图表 tooltip 统一样式，白底 1px 边框圆角 8，色值全部来自令牌 */
export function chartTooltip() {
  return {
    backgroundColor: token('--lw-surface-raised'),
    borderColor: token('--lw-border'),
    borderWidth: 1,
    borderRadius: 8,
    textStyle: { color: token('--lw-ink'), fontSize: 12 },
  }
}

/** 图表通用坐标轴与网格样式 */
export function chartAxis() {
  return {
    axisLine: { lineStyle: { color: token('--lw-border') } },
    axisTick: { show: false },
    axisLabel: { color: token('--lw-ink-muted'), fontSize: 10 },
  }
}
