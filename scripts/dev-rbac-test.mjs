// 开发期 RBAC 守门验证：超管全通 / 越权提拔被拒 / 越权授菜单被拒 / 停用踢下线 / 重置密码进强制改密态
import crypto from 'node:crypto'

const BASE = process.env.LW_BASE || 'http://localhost:8080/api/v1'

function rsaEncrypt(publicKeyPem, plain) {
  return crypto.publicEncrypt(
    { key: publicKeyPem, padding: crypto.constants.RSA_PKCS1_PADDING },
    Buffer.from(plain, 'utf8'),
  ).toString('base64')
}

async function main() {
  const pkJson = await (await fetch(`${BASE}/auth/public-key`)).json()
  const pem = `-----BEGIN PUBLIC KEY-----\n${pkJson.data.publicKey}\n-----END PUBLIC KEY-----`
  const enc = (s) => rsaEncrypt(pem, s)
  const post = (path, token, body) => fetch(`${BASE}${path}`, {
    method: 'POST',
    headers: { Authorization: token, 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  }).then((r) => r.json())
  const login = async (username, password) => {
    const r = await post('/auth/login', undefined, { username, password: enc(password), keyId: pkJson.data.keyId })
    if (r.code !== '0') throw new Error(`${username} 登录失败: ${r.code} ${r.message}`)
    return r.data
  }
  const H = (token) => ({ Authorization: token, 'Content-Type': 'application/json' })

  const superAdmin = await login('admin', process.env.LW_PASSWORD || 'admin@123')
  const h = H(superAdmin.token)
  console.log('1. 超管登录 OK, 权限数:', superAdmin.perms.length)

  // 清理可能的历史测试账号
  const users0 = await (await fetch(`${BASE}/user/page?page=1&size=50`, { headers: h })).json()
  const stale = users0.data.list.find((u) => u.username === 'platadmin')
  if (stale) {
    await post(`/user/delete/${stale.userId}`, superAdmin.token, {})
    console.log('0. 清理历史测试账号')
  }
  const roles0 = await (await fetch(`${BASE}/role/list`, { headers: h })).json()
  const staleRole = roles0.data.find((r) => r.roleCode === 'test_role')
  if (staleRole) {
    await post(`/role/delete/${staleRole.roleId}`, superAdmin.token, {})
  }

  // 1 建平台管理员账号（首登强制改密）
  const created = await post('/user/create', superAdmin.token, {
    username: 'platadmin', nickname: '平台管理员测试', password: enc('Plat@2026x'), passwordKeyId: pkJson.data.keyId,
  })
  console.log('2. 创建平台管理员账号:', created.code, created.message)

  const users = await (await fetch(`${BASE}/user/page?page=1&size=50`, { headers: h })).json()
  const roles = await (await fetch(`${BASE}/role/list`, { headers: h })).json()
  const platAdminUser = users.data.list.find((u) => u.username === 'platadmin')
  const adminRole = roles.data.find((r) => r.roleCode === 'admin')
  const superRole = roles.data.find((r) => r.roleCode === 'super_admin')

  const assign = await post('/user/update', superAdmin.token,
    { userId: platAdminUser.userId, nickname: '平台管理员测试', roleIds: [adminRole.roleId] })
  console.log('3. 分配 admin 角色:', assign.code, assign.message)

  // 平台管理员先自助改密，解除强制改密态（新旧口令必须不同）
  const plat0 = await login('platadmin', 'Plat@2026x')
  const pw = await post('/auth/password', plat0.token,
    { oldPassword: enc('Plat@2026x'), newPassword: enc('Plat@2026z'), keyId: pkJson.data.keyId })
  console.log('4. 平台管理员自助改密:', pw.code, pw.message)
  const plat = await login('platadmin', 'Plat@2026z')
  const ph = H(plat.token)
  console.log('5. 平台管理员权限数:', plat.perms.length, '含角色管理权限:', plat.perms.some((p) => p.startsWith('system:role:')))

  // 6 平台管理员尝试提拔他人为 super_admin（必须被拒）
  const escalate = await post('/user/update', plat.token,
    { userId: platAdminUser.userId, nickname: '平台管理员测试', roleIds: [superRole.roleId] })
  console.log('6. 平台管理员提拔超管角色(应拒):', escalate.code, escalate.message)

  // 7 平台管理员尝试建角色（无 system:role:create，必须被拒）
  const customRoleTry = await post('/role/create', plat.token, { roleCode: 'test_role', roleName: '测试角色' })
  console.log('7. 平台管理员建角色(应拒):', customRoleTry.code, customRoleTry.message)

  // 8 超管建自定义角色；平台管理员尝试授角色管理菜单（必须被拒）
  const customRole = await post('/role/create', superAdmin.token, { roleCode: 'test_role', roleName: '测试角色' })
  console.log('8. 超管建自定义角色:', customRole.code, customRole.message)
  const menus = await (await fetch(`${BASE}/menu/tree`, { headers: ph })).json()
  const roleMenuIds = []
  const walk = (nodes) => (nodes || []).forEach((n) => {
    if (n.permCode && n.permCode.startsWith('system:role:')) roleMenuIds.push(n.menuId)
    if (n.children?.length) walk(n.children)
  })
  walk(menus.data)
  const grantEscalate = await post('/role/grant', plat.token, { roleId: customRole.data, menuIds: roleMenuIds })
  console.log('9. 平台管理员授角色管理菜单(应拒):', grantEscalate.code, grantEscalate.message)

  // 10 停用平台管理员 → 旧会话立即失效
  const disable = await post(`/user/toggle/${platAdminUser.userId}`, superAdmin.token, {})
  console.log('10. 停用平台管理员:', disable.code, disable.message)
  const afterDisable = await fetch(`${BASE}/menu/user-tree`, { headers: ph })
  console.log('11. 停用后旧会话请求(应401/403):', afterDisable.status)

  const relogin = await post('/auth/login', undefined, { username: 'platadmin', password: enc('Plat@2026z'), keyId: pkJson.data.keyId })
  console.log('12. 停用后重新登录(应拒):', relogin.code, relogin.message)

  // 幂等窗口内同用户重复 toggle 会被挡，等待窗口过期再启用
  await new Promise((r) => setTimeout(r, 11000))
  const enable = await post(`/user/toggle/${platAdminUser.userId}`, superAdmin.token, {})
  console.log('13. 重新启用:', enable.code, enable.message)

  // 14 重置密码 → 在线用户进入强制改密态
  const plat2 = await login('platadmin', 'Plat@2026z')
  const reset = await post('/user/reset-password', superAdmin.token,
    { userId: platAdminUser.userId, newPassword: enc('Plat@2026y'), keyId: pkJson.data.keyId })
  console.log('14. 重置平台管理员密码:', reset.code, reset.message)
  const blocked = await fetch(`${BASE}/menu/user-tree`, { headers: H(plat2.token) })
  const blockedJson = await blocked.json()
  console.log('15. 未改密访问业务接口(应2006):', blockedJson.code, blockedJson.message)

  // 清理
  const delUser = await post(`/user/delete/${platAdminUser.userId}`, superAdmin.token, {})
  console.log('16. 删除测试账号:', delUser.code, delUser.message)
  const delRole = await post(`/role/delete/${customRole.data}`, superAdmin.token, {})
  console.log('17. 删除测试角色:', delRole.code, delRole.message)
}

main().catch((e) => {
  console.error('RBAC 验证异常:', e)
  process.exit(1)
})
