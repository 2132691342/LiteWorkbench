// 开发期登录冒烟测试：取公钥 → RSA(PKCS1) 加密 → 登录 → 拉当前用户/菜单/健康/日志/运行时
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
  const spki = pkJson.data.publicKey
  const keyId = pkJson.data.keyId
  const pem = `-----BEGIN PUBLIC KEY-----\n${spki}\n-----END PUBLIC KEY-----`
  const password = process.env.LW_PASSWORD || 'admin@123'
  const encrypted = rsaEncrypt(pem, password)

  const loginJson = await (await fetch(`${BASE}/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username: 'admin', password: encrypted, keyId }),
  })).json()
  if (loginJson.code !== '0') {
    console.error('登录失败:', JSON.stringify(loginJson))
    process.exit(1)
  }
  const token = loginJson.data.token
  console.log('登录成功, pwdResetRequired =', loginJson.data.pwdResetRequired)

  const headers = { Authorization: token }
  const me = await (await fetch(`${BASE}/auth/current`, { headers })).json()
  console.log('当前用户:', me.data?.user?.username, '角色:', JSON.stringify(me.data?.roleCodes), '权限数:', me.data?.perms?.length)

  const menus = await (await fetch(`${BASE}/menu/user-tree`, { headers })).json()
  const roots = menus.data ?? []
  console.log('菜单树根节点:', JSON.stringify(roots.map((m) => `${m.menuName}(${m.children?.length ?? 0})`)))

  const health = await (await fetch(`${BASE}/monitor/health`, { headers })).json()
  console.log('健康状态:', health.data?.status, JSON.stringify(health.data?.components?.map((c) => `${c.name}:${c.status}`)))

  const overview = await (await fetch(`${BASE}/monitor/overview`, { headers })).json()
  console.log('仪表盘 runCount:', overview.data?.runCount)

  const logs = await (await fetch(`${BASE}/log/search?page=1&size=3`, { headers })).json()
  console.log('日志检索 total:', logs.data?.total, 'scope:', logs.data?.scope, '首条:', logs.data?.list?.[0]?.message?.substring(0, 60))

  const stats = await (await fetch(`${BASE}/log/stats`, { headers })).json()
  console.log('日志统计 levels:', JSON.stringify(stats.data?.levels))

  const runtime = await (await fetch(`${BASE}/runtime/python`, { headers })).json()
  console.log('Python 运行时:', JSON.stringify(runtime.data))
}

main().catch((e) => {
  console.error('冒烟测试异常:', e)
  process.exit(1)
})
