/** 前端常量：与后端 constant 包保持一致 */

/** 超级管理员角色码 */
export const ROLE_SUPER_ADMIN = 'super_admin'
/** 平台管理员角色码 */
export const ROLE_ADMIN = 'admin'
/** 普通用户角色码 */
export const ROLE_USER = 'user'

/** 用户状态 */
export const USER_STATE = {
  NORMAL: 'normal',
  DISABLED: 'disabled',
  LOCKED: 'locked',
} as const

/** 菜单类型 */
export const MENU_TYPE = {
  DIR: 'dir',
  MENU: 'menu',
  BUTTON: 'button',
} as const

/** 时间戳格式化 */
export function formatTime(value?: number): string {
  if (!value) return '-'
  const date = new Date(value)
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}`
}
