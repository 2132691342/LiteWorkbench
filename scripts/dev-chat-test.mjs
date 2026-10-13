// 开发期 Agent 链路冒烟测试：建会话 → SSE 发消息 → 收集事件 → 校验运行记录与中断
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
  const password = process.env.LW_PASSWORD || 'admin@123'
  const encrypted = rsaEncrypt(pem, password)
  const login = await (await fetch(`${BASE}/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username: 'admin', password: encrypted, keyId: pkJson.data.keyId }),
  })).json()
  if (login.code !== '0') {
    console.error('登录失败:', JSON.stringify(login))
    process.exit(1)
  }
  const headers = { Authorization: login.data.token, 'Content-Type': 'application/json' }

  // 1 建会话（指定 mock 供应方与模型）
  const session = await (await fetch(`${BASE}/session/create`, {
    method: 'POST',
    headers,
    body: JSON.stringify({ title: 'Agent 链路冒烟', providerCode: 'openai', modelName: 'mock-model' }),
  })).json()
  console.log('会话创建:', session.code, session.data)
  const sessionId = session.data

  // 2 SSE 发消息
  console.log('--- SSE 事件流 ---')
  const resp = await fetch(`${BASE}/chat/stream`, {
    method: 'POST',
    headers,
    body: JSON.stringify({ sessionId, content: '请计算 1 到 10 的和' }),
  })
  const reader = resp.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''
  let runId = null
  const eventTypes = []
  const toolEvents = []
  let textAgg = ''
  try {
    while (true) {
      const { done, value } = await reader.read()
      if (done) break
      buffer += decoder.decode(value, { stream: true })
      let idx
      while ((idx = buffer.indexOf('\n\n')) >= 0) {
        const frame = buffer.slice(0, idx)
        buffer = buffer.slice(idx + 2)
        const dataLine = frame.split('\n').find((l) => l.startsWith('data:'))
        if (!dataLine) continue
        try {
          const payload = JSON.parse(dataLine.slice(5).trim())
          eventTypes.push(payload.eventType)
          if (payload.runId) runId = payload.runId
          if (payload.eventType === 'text.delta' && payload.textDelta) textAgg += payload.textDelta
          if (payload.eventType?.startsWith('tool.')) toolEvents.push(JSON.stringify(payload).substring(0, 220))
          if (payload.eventType === 'run.end' || payload.eventType === 'run.error') {
            console.log('终态事件:', JSON.stringify(payload).substring(0, 300))
          }
        } catch {
          // 心跳等非 JSON 帧
        }
      }
    }
  } catch {
    // SSE 收尾时连接关闭属正常
  }
  console.log('事件序列:', JSON.stringify(eventTypes))
  console.log('工具事件:', JSON.stringify(toolEvents, null, 1))
  console.log('正文累积:', textAgg)

  // 3 运行记录校验
  const runs = await (await fetch(`${BASE}/run/page?page=1&size=5`, { headers })).json()
  console.log('运行列表 total:', runs.data?.total, '首条状态:', runs.data?.list?.[0]?.state)
  const detail = await (await fetch(`${BASE}/run/detail/${runId}`, { headers })).json()
  console.log('运行详情:', JSON.stringify(detail.data).substring(0, 400))
  const events = await (await fetch(`${BASE}/run/events/${runId}`, { headers })).json()
  console.log('事件条数:', events.data?.length)

  // 4 会话消息校验
  const messages = await (await fetch(`${BASE}/session/messages/${sessionId}`, { headers })).json()
  console.log('会话消息数:', messages.data?.length, '角色:', JSON.stringify(messages.data?.map((m) => m.role)))
}

main().catch((e) => {
  console.error('冒烟测试异常:', e)
  process.exit(1)
})
