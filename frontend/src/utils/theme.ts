/** 设计令牌读取：canvas 渲染拿不到 CSS 变量字符串，需运行时取色值 */

export function token(name: string): string {
  return getComputedStyle(document.documentElement).getPropertyValue(name).trim()
}

export function chartTooltip() {
  return {
    backgroundColor: token('--lw-surface-raised'),
    borderColor: token('--lw-border'),
    borderWidth: 1,
    borderRadius: 8,
    textStyle: { color: token('--lw-ink'), fontSize: 12 },
  }
}

export function chartAxis() {
  return {
    axisLine: { lineStyle: { color: token('--lw-border') } },
    axisTick: { show: false },
    axisLabel: { color: token('--lw-ink-muted'), fontSize: 10 },
  }
}
