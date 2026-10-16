import type { App, Component } from 'vue'
import {
  ElCheckbox,
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

/**
 * Element Plus 按需注册：全站只登记实际用到的组件，全量注册会把全部组件压进首屏 chunk。
 * 样式仍引全量 `element-plus/dist/index.css`：逐组件引样式漏一个就是白屏级事故，而 CSS 压缩后收益远小于 JS。
 */
const COMPONENTS: Record<string, Component> = {
  ElCheckbox,
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
