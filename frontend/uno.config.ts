import { defineConfig, presetAttributify, presetUno } from 'unocss'

/** UnoCSS 配置：主题色只映射设计令牌，组件禁止硬编码色值 */
export default defineConfig({
  presets: [presetUno(), presetAttributify()],
  theme: {
    colors: {
      lw: {
        primary: 'var(--lw-primary)',
        primaryHover: 'var(--lw-primary-hover)',
        primaryActive: 'var(--lw-primary-active)',
        primaryTint: 'var(--lw-primary-tint)',
        secondary: 'var(--lw-secondary)',
        secondaryHover: 'var(--lw-secondary-hover)',
        secondaryTint: 'var(--lw-secondary-tint)',
        secondarySelected: 'var(--lw-secondary-selected)',
        accentSoft: 'var(--lw-accent-soft)',
        subtle: 'var(--lw-surface-subtle)',
        onSecondary: 'var(--lw-on-secondary)',
        surface: 'var(--lw-surface)',
        raised: 'var(--lw-surface-raised)',
        border: 'var(--lw-border)',
        borderStrong: 'var(--lw-border-strong)',
        ink: 'var(--lw-ink)',
        muted: 'var(--lw-ink-muted)',
        disabled: 'var(--lw-ink-disabled)',
        success: 'var(--lw-success)',
        successInk: 'var(--lw-success-ink)',
        warning: 'var(--lw-warning)',
        warningInk: 'var(--lw-warning-ink)',
        error: 'var(--lw-error)',
        errorInk: 'var(--lw-error-ink)',
        info: 'var(--lw-info)',
        onPrimary: 'var(--lw-on-primary)',
      },
    },
    borderRadius: {
      card: '12px',
      control: '8px',
      tag: '6px',
    },
  },
  rules: [
    ['lw-shadow-sm', { 'box-shadow': 'var(--lw-shadow-sm)' }],
    ['lw-shadow-md', { 'box-shadow': 'var(--lw-shadow-md)' }],
  ],
  shortcuts: {
    'lw-card': 'bg-lw-raised rounded-card border border-lw-border lw-shadow-sm',
    'lw-panel': 'lw-card p-5 flex flex-col gap-4',
    'lw-page': 'p-6 flex flex-col gap-6 min-h-full',
    'lw-page-head': 'flex flex-wrap justify-between items-center gap-4',
    'lw-title': 'text-2xl font-bold tracking-tight text-lw-ink',
    'lw-subtitle': 'text-sm text-lw-muted',
    'lw-label': 'text-xs font-bold text-lw-muted',
    // 输入框锁定 h-10（40px）：与 LwButton md、el-input、LwSegmented 同高，同行控件天然对齐；
    // 边框用强调紫而非浅灰——白底上浅灰 1px 几乎不可见
    'lw-input':
      'w-full h-10 px-3 rounded-control border border-lw-borderStrong bg-transparent text-sm text-lw-ink outline-none transition-all duration-200 focus:border-lw-primary',
    // 表格统一圆角内收，避免直角贴着卡片边框；表头 / 行高主题见 styles/index.css
    'lw-table': 'w-full overflow-hidden rounded-control',
    'lw-center': 'flex items-center justify-center',
  },
})
