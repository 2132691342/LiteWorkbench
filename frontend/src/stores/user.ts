import { defineStore } from 'pinia'
import { computed, ref, shallowRef } from 'vue'
import { authApi, clearToken, getToken, setToken } from '@/api'
import type { LoginREQ, LoginRESP, MenuVO, UserVO } from '@/types/api'
import { ROLE_SUPER_ADMIN } from '@/utils/const'

/** 用户会话：令牌、身份、权限标识与菜单树 */
export const useUserStore = defineStore('user', () => {
  const token = ref(getToken())
  const user = shallowRef<UserVO | null>(null)
  const roleCodes = ref<string[]>([])
  const perms = ref<string[]>([])
  const menus = shallowRef<MenuVO[]>([])
  const loaded = ref(false)

  const isLogin = computed(() => Boolean(token.value))
  const isSuperAdmin = computed(() => roleCodes.value.includes(ROLE_SUPER_ADMIN))
  const nickname = computed(() => user.value?.nickname ?? '')

  /** 登录并落库令牌 */
  async function login(payload: LoginREQ): Promise<void> {
    const resp = await authApi.login(payload)
    if (resp.token) {
      token.value = resp.token
      setToken(resp.token)
    }
    apply(resp)
  }

  /** 拉取当前用户，用于刷新后恢复会话 */
  async function loadCurrent(): Promise<void> {
    const resp = await authApi.current()
    apply(resp)
  }

  /** 登出并清空本地状态 */
  async function logout(): Promise<void> {
    try {
      await authApi.logout()
    } finally {
      reset()
    }
  }

  function apply(resp: LoginRESP): void {
    user.value = resp.user
    roleCodes.value = resp.roleCodes ?? []
    perms.value = resp.perms ?? []
    menus.value = resp.menus ?? []
    loaded.value = true
  }

  function reset(): void {
    token.value = ''
    clearToken()
    user.value = null
    roleCodes.value = []
    perms.value = []
    menus.value = []
    loaded.value = false
  }

  return {
    token,
    user,
    roleCodes,
    perms,
    menus,
    loaded,
    isLogin,
    isSuperAdmin,
    nickname,
    login,
    loadCurrent,
    logout,
    reset,
  }
})
