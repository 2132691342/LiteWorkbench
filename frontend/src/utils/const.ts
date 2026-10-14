/** 前端常量：与后端 constant 包保持一致 */

export const ROLE_SUPER_ADMIN = 'super_admin'
export const ROLE_ADMIN = 'admin'
export const ROLE_USER = 'user'

export const USER_STATE = {
  NORMAL: 'normal',
  DISABLED: 'disabled',
  LOCKED: 'locked',
} as const

export const MENU_TYPE = {
  DIR: 'dir',
  MENU: 'menu',
  BUTTON: 'button',
} as const

export function formatTime(value?: number): string {
  if (!value) return '-'
  const date = new Date(value)
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}`
}
