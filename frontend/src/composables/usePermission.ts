import { useUserStore } from '@/stores/user'

/** 权限判定：模板中用 v-if="hasPerm('system:user:create')" 控制按钮可见性，权限码与后端 PermConst 一致 */
export function usePermission() {
  const store = useUserStore()

  /** 是否拥有某个权限标识，数组为任一命中 */
  function hasPerm(code: string | string[]): boolean {
    const codes = Array.isArray(code) ? code : [code]
    return codes.some((item) => store.perms.includes(item))
  }

  /** 是否拥有某个角色码，数组为任一命中 */
  function hasRole(code: string | string[]): boolean {
    const codes = Array.isArray(code) ? code : [code]
    return codes.some((item) => store.roleCodes.includes(item))
  }

  return { hasPerm, hasRole }
}
