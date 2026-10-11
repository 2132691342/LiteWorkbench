/** @wangeditor/editor-for-vue 未随包导出类型，这里补最小声明 */
declare module '@wangeditor/editor-for-vue' {
  import type { DefineComponent } from 'vue'

  /** 富文本编辑区 */
  export const Editor: DefineComponent<Record<string, unknown>, Record<string, unknown>, unknown>
  /** 工具栏，与 Editor 实例绑定 */
  export const Toolbar: DefineComponent<Record<string, unknown>, Record<string, unknown>, unknown>
}
