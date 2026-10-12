import { i18n } from '@/locales'

/** 菜单标签渲染：命中 i18n 用译文，未命中把 key 末段拆驼峰为可读词，绝不裸显原始 key */
export function menuLabel(name: string | undefined | null): string {
  if (!name) {
    return ''
  }
  const translated = i18n.global.t(name)
  // vue-i18n 未命中时会回显 key 本身，据此判定缺翻译
  if (translated !== name) {
    return translated
  }
  const tail = name.includes('.') ? name.slice(name.lastIndexOf('.') + 1) : name
  return splitCamel(tail)
}

/** 驼峰拆词并首字母大写：userManageCreate -> User Manage Create */
function splitCamel(value: string): string {
  const words = value.replace(/([a-z0-9])([A-Z])/g, '$1 $2').split(/[\s_-]+/)
  return words
    .filter((word) => word.length > 0)
    .map((word) => word.charAt(0).toUpperCase() + word.slice(1))
    .join(' ')
}
