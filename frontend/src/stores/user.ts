import { defineStore } from 'pinia'
import { computed, ref, shallowRef } from 'vue'
import { authApi, clearToken, getToken, setToken } from '@/api'
import type { LoginRESP, MenuVO, UserVO } from '@/types/api'
import { encryptSecret } from '@/utils/crypto'
import { ROLE_SUPER_ADMIN } from '@/utils/const'

/** 用户会话：令牌、身份、权限标识与菜单树 */
export const useUserStore = defineStore('user', () => {
  const token = ref(getToken())
  const user = shallowRef<UserVO | null>(null)
  const roleCodes = ref<string[]>([])
  const perms = ref<string[]>([])
  const menus = shallowRef<MenuVO[]>([])
  const pwdResetRequired = ref(false)
  const loaded = ref(false)

  const isLogin = computed(() => Boolean(token.value))
  const isSuperAdmin = computed(() => roleCodes.value.includes(ROLE_SUPER_ADMIN))
  const nickname = computed(() => user.value?.nickname ?? '')

  /** 登录：密码本地加密后再提交，明文不出浏览器 */
  async function login(payload: { username: string; password: string }): Promise<void> {
    const { keyId, cipher } = await encryptSecret(payload.password)
    const resp = await authApi.login({ username: payload.username, password: cipher, keyId })
    if (resp.token) {
      token.value = resp.token
      setToken(resp.token)
    }
    apply(resp)
  }

  async function loadCurrent(): Promise<void> {
    const resp = await authApi.current()
    apply(resp)
  }

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
    pwdResetRequired.value = Boolean(resp.pwdResetRequired)
    loaded.value = true
  }

  function reset(): void {
    token.value = ''
    clearToken()
    user.value = null
    roleCodes.value = []
    perms.value = []
    menus.value = []
    pwdResetRequired.value = false
    loaded.value = false
  }

  return {
    token,
    user,
    roleCodes,
    perms,
    menus,
    pwdResetRequired,
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
