<script setup lang="ts">
import { computed, nextTick, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElIcon } from 'element-plus'
import { ArrowUpBold, Collection, Loading, Promotion, Tools } from '@element-plus/icons-vue'
import LwButton from '@/components/ui/LwButton.vue'
import { docApi, fileApi, kbApi, skillApi } from '@/api'
import { usePermission } from '@/composables/usePermission'
import type { RunRefREQ, SkillVO, DocVO, FileVO, KbVO } from '@/types/api'

/** 工作台输入框：自增高输入 + / 技能与 @ 引用面板；选中项落成 chip，结构化字段单独随请求提交 */
const { t } = useI18n()
const { hasPerm } = usePermission()

const draft = defineModel<string>({ required: true })
const props = defineProps<{
  streaming: boolean
  replaying: boolean
  /** 上一条用户消息，供 ↑ 回填；宿主按当前会话的时间线给出 */
  lastUserMessage?: string
}>()
const emit = defineEmits<{
  send: [{ content: string; skillCodes: string[]; refs: RunRefREQ[] }]
  abort: []
  newSession: []
}>()

/** 面板条目：命令 / 技能 / 引用统一成一种可选项 */
interface PaletteItem {
  key: string
  kind: 'command' | 'skill' | 'ref'
  label: string
  hint?: string
  /** 命令类不产生 chip，选中即执行动作 */
  command?: 'new'
  skillCode?: string
  ref?: RunRefREQ
}

/** 已选中的技能与引用 chip */
interface Chip {
  key: string
  kind: 'skill' | 'ref'
  label: string
  skillCode?: string
  ref?: RunRefREQ
}

/** 输入区自增高上限（px）与正文长度上限（字符） */
const MAX_HEIGHT = 200
const MAX_LENGTH = 4000
/** 面板最多展示条数：超过后即使有匹配也不再罗列，靠关键字继续收敛 */
const MAX_OPTIONS = 8

const textareaEl = ref<HTMLTextAreaElement | null>(null)
const paletteOpen = ref(false)
const paletteKind = ref<'skill' | 'ref'>('skill')
const activeIndex = ref(0)
const loadingPalette = ref(false)
const loaded = ref(false)
const chips = ref<Chip[]>([])
const query = ref('')
const locked = computed(() => props.streaming || props.replaying)
const canSend = computed(() => !locked.value && (draft.value.trim().length > 0 || chips.value.length > 0))
const charCount = computed(() => draft.value.length)
const nearLimit = computed(() => charCount.value >= MAX_LENGTH * 0.9)

/** 把标签按关键字切成三段，供模板对命中片段加粗；无关键字时整段原样返回 */
function highlight(label: string): { text: string; hit: boolean }[] {
  const keyword = query.value.trim()
  if (!keyword) return [{ text: label, hit: false }]
  const at = label.toLowerCase().indexOf(keyword.toLowerCase())
  if (at < 0) return [{ text: label, hit: false }]
  return [
    { text: label.slice(0, at), hit: false },
    { text: label.slice(at, at + keyword.length), hit: true },
    { text: label.slice(at + keyword.length), hit: false },
  ].filter((part) => part.text.length > 0)
}

const skillItems = ref<PaletteItem[]>([])
const refItems = ref<PaletteItem[]>([])

const commands = computed<PaletteItem[]>(() => [
  { key: 'cmd:new', kind: 'command', label: t('console.cmdNew'), hint: t('console.cmdNewHint'), command: 'new' },
])

/** 面板候选项：先按 kind 分组过滤，再按「前缀命中 > 类型序 > 字典序」排序，最后截断 */
const filtered = computed<PaletteItem[]>(() => {
  const keyword = query.value.trim().toLowerCase()
  const pool = paletteKind.value === 'skill' ? [...commands.value, ...skillItems.value] : refItems.value
  const matched = keyword
    ? pool.filter((item) => `${item.label} ${item.hint ?? ''}`.toLowerCase().includes(keyword))
    : pool
  const rank: Record<PaletteItem['kind'], number> = { command: 0, skill: 1, ref: 2 }
  const prefixBoost = (item: PaletteItem): number =>
    keyword && item.label.toLowerCase().startsWith(keyword) ? 0 : 1
  return [...matched]
    .sort((a, b) => prefixBoost(a) - prefixBoost(b) || rank[a.kind] - rank[b.kind] || a.label.localeCompare(b.label))
    .slice(0, MAX_OPTIONS)
})

/** 面板打开时才拉数据，且只拉有权限的分区；不按 / 与 @ 就不产生请求 */
async function ensureData(): Promise<void> {
  if (loaded.value) return
  loadingPalette.value = true
  try {
    const [skillPage, kbPage, filePage, docPage] = await Promise.all([
      hasPerm('wb:skill:list') ? skillApi.page({ page: 1, size: 50 }) : null,
      hasPerm('wb:kb:list') ? kbApi.page({ page: 1, size: 30 }) : null,
      fileApi.page({ page: 1, size: 30 }),
      hasPerm('wb:doc:list') ? docApi.page({ page: 1, size: 30 }) : null,
    ])
    skillItems.value = (skillPage?.list ?? [])
      .filter((item: SkillVO) => item.enabled)
      .map((item: SkillVO) => ({
        key: `skill:${item.skillCode}`,
        kind: 'skill' as const,
        label: item.skillName,
        hint: `/${item.skillCode}${item.summary ? ` · ${item.summary}` : ''}`,
        skillCode: item.skillCode,
      }))
    refItems.value = [
      ...(kbPage?.list ?? []).map((item: KbVO) => ({
        key: `kb:${item.kbId}`,
        kind: 'ref' as const,
        label: item.kbName,
        hint: t('console.refKb'),
        ref: { type: 'kb' as const, id: item.kbId, label: item.kbName },
      })),
      ...(filePage?.list ?? []).map((item: FileVO) => ({
        key: `file:${item.fileId}`,
        kind: 'ref' as const,
        label: item.fileName,
        hint: t('console.refFile'),
        ref: { type: 'file' as const, id: item.fileId, label: item.fileName },
      })),
      ...(docPage?.list ?? []).map((item: DocVO) => ({
        key: `doc:${item.docId}`,
        kind: 'ref' as const,
        label: item.docTitle,
        hint: t('console.refDoc'),
        ref: { type: 'doc' as const, id: item.docId, label: item.docTitle },
      })),
    ]
    loaded.value = true
  } finally {
    loadingPalette.value = false
  }
}

/** 取光标前的触发标记：/ 或 @ 必须紧跟行首或空白，查询串内不含空格 */
function tokenBeforeCaret(): { kind: 'skill' | 'ref'; query: string; start: number } | null {
  const el = textareaEl.value
  if (!el) return null
  const caret = el.selectionStart ?? 0
  const match = /(?:^|\s)([/@])([^\s/@]*)$/.exec(draft.value.slice(0, caret))
  if (!match) return null
  return { kind: match[1] === '/' ? 'skill' : 'ref', query: match[2], start: caret - match[2].length - 1 }
}

function syncPalette(): void {
  const token = tokenBeforeCaret()
  if (!token) {
    closePalette()
    return
  }
  paletteKind.value = token.kind
  query.value = token.query
  if (!paletteOpen.value) {
    paletteOpen.value = true
    void ensureData()
  }
  activeIndex.value = 0
}

function closePalette(): void {
  paletteOpen.value = false
  query.value = ''
}

/** 光标移动：只在面板已开时重算，避免每次点选都跑一遍正则 */
function onCaretMove(): void {
  if (paletteOpen.value) syncPalette()
}

/** 胶囊入口：等价于在光标处敲下 / 或 @，与键盘走同一条路径 */
function triggerPalette(kind: 'skill' | 'ref'): void {
  const el = textareaEl.value
  if (!el || locked.value) return
  const caret = el.selectionStart ?? draft.value.length
  const mark = kind === 'skill' ? '/' : '@'
  const needSpace = caret > 0 && !/\s/.test(draft.value[caret - 1] ?? '')
  const text = `${needSpace ? ' ' : ''}${mark}`
  draft.value = draft.value.slice(0, caret) + text + draft.value.slice(caret)
  const pos = caret + text.length
  void nextTick(() => {
    autoGrow()
    el.focus()
    el.setSelectionRange(pos, pos)
    syncPalette()
  })
}

/** 选中即移除正文里的标记：chip 是唯一事实来源，正文只留用户真正想说的话 */
function pick(item: PaletteItem | undefined): void {
  if (!item) return
  const token = tokenBeforeCaret()
  if (!token) {
    closePalette()
    return
  }
  const el = textareaEl.value
  const caret = el?.selectionStart ?? draft.value.length
  draft.value = draft.value.slice(0, token.start) + draft.value.slice(caret)

  if (item.kind === 'command') {
    closePalette()
    void nextTick(() => {
      autoGrow()
      el?.focus()
    })
    if (item.command === 'new') emit('newSession')
    return
  }
  if (item.skillCode && !chips.value.some((chip) => chip.skillCode === item.skillCode)) {
    chips.value.push({ key: `skill:${item.skillCode}`, kind: 'skill', label: item.label, skillCode: item.skillCode })
  }
  const ref = item.ref
  if (ref && !chips.value.some((chip) => chip.ref?.type === ref.type && chip.ref.id === ref.id)) {
    chips.value.push({ key: `${ref.type}:${ref.id}`, kind: 'ref', label: ref.label ?? item.label, ref })
  }
  closePalette()
  void nextTick(() => {
    autoGrow()
    el?.focus()
    el?.setSelectionRange(token.start, token.start)
  })
}

function removeChip(chip: Chip): void {
  chips.value = chips.value.filter((item) => item.key !== chip.key)
}

/** 输入区自增高：内容驱动高度，超过上限后内部滚动 */
function autoGrow(): void {
  const el = textareaEl.value
  if (!el) return
  el.style.height = 'auto'
  el.style.height = `${Math.min(el.scrollHeight, MAX_HEIGHT)}px`
}

function onInput(): void {
  autoGrow()
  void nextTick(syncPalette)
}

/** 空正文时 ↑ 回填上一条用户消息：改几个字再发一轮是最高频的续写动作 */
function recallLast(): void {
  const source = props.lastUserMessage
  if (!source) return
  draft.value = source
  void nextTick(() => {
    autoGrow()
    const end = draft.value.length
    const el = textareaEl.value
    el?.focus()
    el?.setSelectionRange(end, end)
  })
}

/** 组词判定：isComposing 之外再挡一次 keyCode 229，覆盖部分输入法不上报 composing 的情况 */
function isComposing(event: KeyboardEvent): boolean {
  return event.isComposing || event.keyCode === 229
}

function onKeydown(event: KeyboardEvent): void {
  if (isComposing(event)) return
  const el = textareaEl.value
  const caret = el?.selectionStart ?? 0

  if (paletteOpen.value && filtered.value.length) {
    if (event.key === 'ArrowDown') {
      event.preventDefault()
      activeIndex.value = (activeIndex.value + 1) % filtered.value.length
      return
    }
    if (event.key === 'ArrowUp') {
      event.preventDefault()
      activeIndex.value = (activeIndex.value - 1 + filtered.value.length) % filtered.value.length
      return
    }
    if (event.key === 'Enter' || event.key === 'Tab') {
      event.preventDefault()
      pick(filtered.value[activeIndex.value])
      return
    }
  }
  if (event.key === 'Escape') {
    event.preventDefault()
    // 面板优先；面板关着且正在流式输出时，Esc 就是中断
    if (paletteOpen.value) closePalette()
    else if (props.streaming) emit('abort')
    return
  }
  if (event.key === 'ArrowUp' && !paletteOpen.value && !draft.value.trim() && caret === 0) {
    event.preventDefault()
    recallLast()
    return
  }
  if (event.key === 'Enter' && !event.shiftKey) {
    event.preventDefault()
    submit()
  }
}

/** 输入法确认后光标已落到新位置，需重算高度与面板状态 */
function onCompositionEnd(): void {
  autoGrow()
  void nextTick(syncPalette)
}

function submit(): void {
  if (!canSend.value) return
  const content = draft.value.trim()
  emit('send', {
    content,
    skillCodes: chips.value.filter((chip) => chip.skillCode).map((chip) => chip.skillCode as string),
    refs: chips.value.filter((chip) => chip.ref).map((chip) => chip.ref as RunRefREQ),
  })
}

/** 发送成功后由宿主调用：正文与 chip 一起清空，输入区回到一行高度 */
function clear(): void {
  draft.value = ''
  chips.value = []
  closePalette()
  void nextTick(autoGrow)
}

defineExpose({ focus: () => textareaEl.value?.focus(), clear })
</script>

<template>
  <div class="lw-composer">
    <Transition name="lw-rise">
      <div v-if="paletteOpen" class="lw-composer__palette" role="listbox" :aria-label="t('console.paletteTitle')">
        <div class="lw-composer__head">
          <span class="lw-label">{{ paletteKind === 'skill' ? t('console.paletteSkill') : t('console.paletteRef') }}</span>
          <span class="text-[11px]" style="color: var(--lw-ink-muted)">{{ t('console.paletteNavHint') }}</span>
        </div>
        <div v-if="loadingPalette" class="lw-composer__empty">
          <el-icon class="is-loading"><Loading /></el-icon>
          {{ t('common.loading') }}
        </div>
        <div v-else-if="!filtered.length" class="lw-composer__empty">
          {{ paletteKind === 'skill' ? t('console.paletteNoSkill') : t('console.paletteNoRef') }}
        </div>
        <template v-else>
          <div
            v-for="(item, index) in filtered"
            :id="`lw-palette-opt-${index}`"
            :key="item.key"
            class="lw-composer__item"
            :class="{ 'is-active': index === activeIndex }"
            role="option"
            :aria-selected="index === activeIndex"
            @mouseenter="activeIndex = index"
            @mousedown.prevent="pick(item)"
          >
            <span class="lw-composer__icon" :class="`is-${item.kind}`">
              <el-icon :size="12">
                <ArrowUpBold v-if="item.kind === 'command'" />
                <Tools v-else-if="item.kind === 'skill'" />
                <Collection v-else />
              </el-icon>
            </span>
            <span class="text-[13px] font-semibold shrink-0"><span v-for="(part, partIndex) in highlight(item.label)" :key="partIndex" :class="{ 'is-hit': part.hit }">{{ part.text }}</span></span>
            <span class="text-xs truncate" style="color: var(--lw-ink-muted)">{{ item.hint }}</span>
          </div>
        </template>
      </div>
    </Transition>

    <div
      class="lw-composer__box"
      :class="{ 'is-open': paletteOpen, 'is-locked': locked }"
      @keydown="onKeydown"
    >
      <TransitionGroup v-if="chips.length" name="lw-chip" tag="div" class="lw-composer__chips">
        <span
          v-for="chip in chips"
          :key="chip.key"
          class="lw-composer__chip"
          :class="`is-${chip.kind}`"
        >
          <el-icon :size="12"><Tools v-if="chip.kind === 'skill'" /><Collection v-else /></el-icon>
          <span class="max-w-[180px] truncate">{{ chip.label }}</span>
          <button
            type="button"
            class="lw-composer__chip-x"
            :aria-label="t('common.delete')"
            @click="removeChip(chip)"
          >
            <svg width="10" height="10" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="3"
                 stroke-linecap="round">
              <path d="M18 6 6 18M6 6l12 12" />
            </svg>
          </button>
        </span>
      </TransitionGroup>

      <textarea
        ref="textareaEl"
        v-model="draft"
        rows="1"
        class="lw-composer__input"
        :placeholder="t('console.inputPlaceholder')"
        :maxlength="MAX_LENGTH"
        :aria-label="t('console.inputPlaceholder')"
        :aria-expanded="paletteOpen"
        :aria-controls="paletteOpen ? 'lw-composer-palette' : undefined"
        :aria-activedescendant="paletteOpen ? `lw-palette-opt-${activeIndex}` : undefined"
        :aria-autocomplete="paletteOpen ? 'list' : undefined"
        @input="onInput"
        @compositionend="onCompositionEnd"
        @keyup="onCaretMove"
        @click="onCaretMove"
        @focus="onCaretMove"
        @blur="closePalette"
      />

      <div class="lw-composer__bar">
        <div class="flex items-center gap-1.5 min-w-0">
          <button
            type="button"
            class="lw-composer__pill"
            :disabled="locked"
            :title="t('console.hintSlashTitle')"
            @mousedown.prevent="triggerPalette('skill')"
          >
            <span class="lw-composer__pill-key">/</span>{{ t('console.hintSlash') }}
          </button>
          <button
            type="button"
            class="lw-composer__pill"
            :disabled="locked"
            :title="t('console.hintAtTitle')"
            @mousedown.prevent="triggerPalette('ref')"
          >
            <span class="lw-composer__pill-key">@</span>{{ t('console.hintAt') }}
          </button>
          <span class="lw-composer__hint">{{ locked ? t('console.escHint') : t('console.sendHint') }}</span>
        </div>

        <div class="flex items-center gap-3 shrink-0">
          <span
            class="text-[11px] font-mono tabular-nums transition-colors duration-200"
            :style="{ color: nearLimit ? 'var(--lw-warning-ink)' : 'var(--lw-ink-disabled)' }"
          >
            {{ charCount }}/{{ MAX_LENGTH }}
          </span>
          <LwButton
            v-if="!props.streaming"
            size="sm"
            :disabled="!canSend"
            :aria-label="t('console.send')"
            @click="submit"
          >
            <template #icon><el-icon><Promotion /></el-icon></template>
            {{ t('console.send') }}
          </LwButton>
          <LwButton v-else size="sm" variant="secondary" loading @click="emit('abort')">
            {{ t('console.abort') }}
          </LwButton>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.lw-composer {
  position: relative;
}
.lw-composer__box {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 10px 12px;
  border-radius: var(--lw-radius-card);
  border: var(--lw-field-border);
  background: var(--lw-surface-raised);
  box-shadow: var(--lw-shadow-sm);
  transition: border-color var(--lw-dur) var(--lw-ease), box-shadow var(--lw-dur) var(--lw-ease);
}
.lw-composer__box:hover {
  border: var(--lw-field-border-hover);
}
.lw-composer__box:focus-within,
.lw-composer__box.is-open {
  border: var(--lw-field-border-focus);
  box-shadow: var(--lw-field-focus-ring);
}
.lw-composer__chips {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.lw-composer__chip {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  height: 26px;
  padding: 0 4px 0 10px;
  border-radius: var(--lw-radius-pill);
  font-size: 12px;
  font-weight: 600;
  color: var(--lw-primary-ink);
  max-width: 100%;
  transition: background var(--lw-dur) var(--lw-ease);
}
.lw-composer__chip.is-skill {
  background: var(--lw-surface-subtle);
}
.lw-composer__chip.is-ref {
  background: var(--lw-secondary-tint);
}
.lw-composer__chip-x {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 18px;
  height: 18px;
  border: none;
  border-radius: var(--lw-radius-pill);
  background: transparent;
  color: inherit;
  cursor: pointer;
  opacity: 0.55;
  transition: opacity var(--lw-dur-fast) var(--lw-ease), color var(--lw-dur-fast) var(--lw-ease),
    transform var(--lw-dur-fast) var(--lw-ease);
}
.lw-composer__chip-x:hover {
  opacity: 1;
  color: var(--lw-error-ink);
  transform: rotate(90deg);
}
.lw-composer__chip-x:active {
  transform: rotate(90deg) scale(0.9);
}
.lw-composer__chip-x:focus-visible {
  outline: 2px solid var(--lw-primary);
  outline-offset: 1px;
}
/* 复合选择器而非单类：styles/index.css 的裸 textarea 兜底（min-height 80px + 8/12 padding）
   特异度是 (0,2,2)，单类 (0,2,0) 会被它压住；外层框已经把边框与焦点态画完，内层必须纯透明无内距 */
.lw-composer__box .lw-composer__input {
  width: 100%;
  min-height: 48px;
  max-height: 200px;
  padding: 0;
  resize: none;
  border: none;
  outline: none;
  background: transparent;
  font-size: 14px;
  line-height: 1.6;
  color: var(--lw-ink);
  overflow-y: auto;
}
.lw-composer__box .lw-composer__input::placeholder {
  color: var(--lw-ink-disabled);
}
.lw-composer__bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}
.lw-composer__pill {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 3px 9px;
  border-radius: var(--lw-radius-pill);
  border: 1px solid var(--lw-border);
  background: var(--lw-surface);
  color: var(--lw-ink-muted);
  font-size: 12px;
  cursor: pointer;
  transition: color var(--lw-dur-fast) var(--lw-ease), border-color var(--lw-dur-fast) var(--lw-ease),
    background var(--lw-dur-fast) var(--lw-ease), transform var(--lw-dur-fast) var(--lw-ease);
}
.lw-composer__pill:hover:not(:disabled) {
  color: var(--lw-primary-ink);
  border-color: var(--lw-secondary);
  background: var(--lw-primary-tint);
}
.lw-composer__pill:active:not(:disabled) {
  transform: scale(0.95);
}
.lw-composer__pill:disabled {
  cursor: not-allowed;
  opacity: 0.5;
}
.lw-composer__pill:focus-visible {
  outline: 2px solid var(--lw-primary);
  outline-offset: 2px;
}
.lw-composer__pill-key {
  font-family: ui-monospace, 'Cascadia Code', Consolas, monospace;
  font-weight: 700;
  color: var(--lw-primary-ink);
}
.lw-composer__hint {
  font-size: 12px;
  color: var(--lw-ink-disabled);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.lw-composer__palette {
  position: absolute;
  bottom: calc(100% + 8px);
  left: 0;
  right: 0;
  max-height: 300px;
  overflow: auto;
  padding: 6px;
  border-radius: var(--lw-radius-card);
  border: 1px solid var(--lw-border);
  background: var(--lw-surface-raised);
  box-shadow: var(--lw-shadow-md);
  z-index: 20;
}
.lw-composer__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 6px 8px;
  border-bottom: 1px solid var(--lw-border);
}
.lw-composer__empty {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  padding: 18px;
  font-size: 13px;
  color: var(--lw-ink-muted);
}
.lw-composer__item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 10px;
  border-radius: var(--lw-radius-control);
  cursor: pointer;
  transition: background var(--lw-dur-fast) var(--lw-ease), color var(--lw-dur-fast) var(--lw-ease);
}
.lw-composer__item.is-active {
  background: var(--lw-primary-tint);
  color: var(--lw-primary-ink);
}
.lw-composer__icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 22px;
  height: 22px;
  border-radius: 6px;
  flex: none;
  background: var(--lw-muted-tint);
  color: var(--lw-ink-muted);
}
.lw-composer__icon.is-command {
  background: var(--lw-primary-tint);
  color: var(--lw-primary-ink);
}
.is-hit {
  color: var(--lw-primary-ink);
  font-weight: 800;
}
.lw-composer__icon.is-ref {
  background: var(--lw-secondary-tint);
  color: var(--lw-primary-ink);
}
.lw-chip-enter-active {
  transition: opacity var(--lw-dur) var(--lw-ease-out), transform var(--lw-dur) var(--lw-ease-out);
}
.lw-chip-leave-active {
  transition: opacity var(--lw-dur-fast) var(--lw-ease), transform var(--lw-dur-fast) var(--lw-ease);
}
.lw-chip-enter-from,
.lw-chip-leave-to {
  opacity: 0;
  transform: translateY(4px) scale(0.96);
}
.lw-rise-enter-active {
  transition: opacity var(--lw-dur) var(--lw-ease-out), transform var(--lw-dur) var(--lw-ease-out);
}
.lw-rise-leave-active {
  transition: opacity var(--lw-dur-fast) var(--lw-ease), transform var(--lw-dur-fast) var(--lw-ease);
}
.lw-rise-enter-from,
.lw-rise-leave-to {
  opacity: 0;
  transform: translateY(6px);
}
@media (prefers-reduced-motion: reduce) {
  .lw-composer__pill:active:not(:disabled) {
    transform: none;
  }
  .lw-chip-enter-from,
  .lw-chip-leave-to,
  .lw-rise-enter-from,
  .lw-rise-leave-to {
    transform: none;
  }
}
</style>
