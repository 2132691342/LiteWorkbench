import type { App, Component } from 'vue'
import { computed } from 'vue'
import {
  ElCheckbox,
  ElConfigProvider,
  ElDatePicker,
  ElDialog,
  ElDrawer,
  ElDropdown,
  ElDropdownItem,
  ElDropdownMenu,
  ElForm,
  ElFormItem,
  ElIcon,
  ElInput,
  ElInputNumber,
  ElOption,
  ElPagination,
  ElRadio,
  ElRadioGroup,
  ElSelect,
  ElSwitch,
  ElTable,
  ElTableColumn,
  ElTooltip,
  ElTree,
} from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import en from 'element-plus/es/locale/lang/en'
import { useAppStore } from '@/stores/app'

/**
 * Element Plus 按需注册：全站只登记实际用到的组件，全量注册会把全部组件压进首屏 chunk。
 * 样式仍引全量 `element-plus/dist/index.css`：逐组件引样式漏一个就是白屏级事故，而 CSS 压缩后收益远小于 JS。
 */
const COMPONENTS: Record<string, Component> = {
  ElCheckbox,
  ElConfigProvider,
  ElDatePicker,
  ElDialog,
  ElDrawer,
  ElDropdown,
  ElDropdownItem,
  ElDropdownMenu,
  ElForm,
  ElFormItem,
  ElIcon,
  ElInput,
  ElInputNumber,
  ElOption,
  ElPagination,
  ElRadio,
  ElRadioGroup,
  ElSelect,
  ElSwitch,
  ElTable,
  ElTableColumn,
  ElTooltip,
  ElTree,
}

/** 注册按需组件；新增 Element Plus 组件时在此登记一行 */
export function setupElementPlus(app: App): void {
  Object.entries(COMPONENTS).forEach(([name, component]) => {
    app.component(name, component)
  })
}

/**
 * Element Plus 自带文案的语言：分页「前往 / 共 N 条」、日期选择「选择日期」、
 * 下拉「无数据」、表格「暂无数据」、弹窗确认取消按钮。
 * 不接上它，应用语言切成中文后这些内置文案仍是英文，表现为「一会中文一会英文」。
 */
export function useElementPlusLocale() {
  const app = useAppStore()
  const locale = computed(() => (app.locale === 'en-US' ? en : zhCn))
  return { locale }
}
