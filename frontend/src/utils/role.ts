import type { Composer } from 'vue-i18n'
import { ROLE_ADMIN, ROLE_SUPER_ADMIN, ROLE_USER } from './const'

/**
 * 角色标签：取用户持有的最高特权内置角色。
 * 自定义角色没有内置翻译，显示「自定义角色」而不是裸显角色码——内部码不该出现在界面上。
 */
export function roleLabel(roleCodes: readonly string[] | undefined, t: Composer['t']): string {
  const codes = roleCodes ?? []
  if (codes.includes(ROLE_SUPER_ADMIN)) {
    return t('app.roleSuperAdmin')
  }
  if (codes.includes(ROLE_ADMIN)) {
    return t('app.roleAdmin')
  }
  if (codes.includes(ROLE_USER)) {
    return t('app.roleUser')
  }
  return t('app.roleCustom')
}
