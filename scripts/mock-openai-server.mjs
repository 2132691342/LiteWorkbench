// 开发期 mock OpenAI 兼容服务：首轮触发 python_exec 工具调用，次轮汇总结果，用于本地端到端验证 Agent 链路
import http from 'node:http'

const PORT = Number(process.env.MOCK_PORT || 11435)

/** 按请求内容决定行为：出现 tool 角色消息即进入第二轮，输出最终答复 */
function decideReply(messages) {
  const lastUser = [...messages].reverse().find((m) => m.role === 'user')
  const slow = lastUser && String(lastUser.content || '').includes('慢点说')
  if (slow) {
    return { slowText: '这是一段用来测试中断的慢速流式输出。' }
  }
  const hasToolResult = messages.some((m) => m.role === 'tool')
  if (hasToolResult) {
    return {
      choices: [{
        index: 0,
        message: { role: 'assistant', content: '计算完成：1 到 10 相加等于 55。这是 mock 模型的最终答复。' },
        finish_reason: 'stop',
      }],
      usage: { prompt_tokens: 128, completion_tokens: 32, total_tokens: 160 },
    }
  }
  return {
    choices: [{
      index: 0,
      message: {
        role: 'assistant',
        content: null,
        tool_calls: [{
          id: 'call_mock_001',
          type: 'function',
          function: {
            name: 'python_exec',
            arguments: JSON.stringify({ code: 'print(sum(range(1, 11)))' }),
          },
        }],
      },
      finish_reason: 'tool_calls',
    }],
    usage: { prompt_tokens: 96, completion_tokens: 24, total_tokens: 120 },
  }
}

const server = http.createServer((req, res) => {
  // 慢速模式：供中断链路测试，/slow 逐字延迟输出
  if (req.method === 'POST' && req.url.endsWith('/slow')) {
    let body = ''
    req.on('data', (chunk) => { body += chunk })
    req.on('end', async () => {
      res.writeHead(200, { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-cache' })
      const chunks = ['让', '我', '慢', '慢', '说', '。']
      for (const ch of chunks) {
        await new Promise((r) => setTimeout(r, 800))
        try {
          res.write(`data: ${JSON.stringify({ choices: [{ index: 0, delta: { content: ch }, finish_reason: null }] })}\n\n`)
        } catch {
          return // 客户端已断开
        }
      }
      try {
        res.write(`data: ${JSON.stringify({ choices: [{ index: 0, delta: {}, finish_reason: 'stop' }], usage: { prompt_tokens: 10, completion_tokens: 6, total_tokens: 16 } })}\n\n`)
        res.write('data: [DONE]\n\n')
        res.end()
      } catch { /* 已断开 */ }
    })
    return
  }
  if (req.method === 'POST' && req.url.endsWith('/chat/completions')) {
    let body = ''
    req.on('data', (chunk) => { body += chunk })
    req.on('end', async () => {
      const payload = JSON.parse(body || '{}')
      const reply = decideReply(payload.messages || [])
      if (reply.slowText) {
        res.writeHead(200, { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-cache' })
        for (const ch of reply.slowText) {
          await new Promise((r) => setTimeout(r, 500))
          try {
            res.write(`data: ${JSON.stringify({ choices: [{ index: 0, delta: { content: ch }, finish_reason: null }] })}\n\n`)
          } catch {
            return
          }
        }
        try {
          res.write(`data: ${JSON.stringify({ choices: [{ index: 0, delta: {}, finish_reason: 'stop' }], usage: { prompt_tokens: 10, completion_tokens: 12, total_tokens: 22 } })}\n\n`)
          res.write('data: [DONE]\n\n')
          res.end()
        } catch { /* 已断开 */ }
        return
      }
      if (payload.stream) {
        res.writeHead(200, { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-cache' })
        const msg = reply.choices[0].message
        const toolCalls = msg.tool_calls
        if (toolCalls) {
            // 工具调用增量：id 与函数名一帧，参数一帧
            const first = {
              choices: [{ index: 0, delta: { role: 'assistant', tool_calls: [{ index: 0, id: toolCalls[0].id, type: 'function', function: { name: toolCalls[0].function.name, arguments: '' } }] }, finish_reason: null }],
            }
            const second = {
              choices: [{ index: 0, delta: { tool_calls: [{ index: 0, function: { arguments: toolCalls[0].function.arguments } }] }, finish_reason: null }],
            }
            res.write(`data: ${JSON.stringify(first)}\n\n`)
            res.write(`data: ${JSON.stringify(second)}\n\n`)
            res.write(`data: ${JSON.stringify({ choices: [{ index: 0, delta: {}, finish_reason: 'tool_calls' }], usage: reply.usage })}\n\n`)
        } else {
            const text = msg.content
            // 逐字分片流式输出
            for (const ch of text) {
              res.write(`data: ${JSON.stringify({ choices: [{ index: 0, delta: { content: ch }, finish_reason: null }] })}\n\n`)
            }
            res.write(`data: ${JSON.stringify({ choices: [{ index: 0, delta: {}, finish_reason: 'stop' }], usage: reply.usage })}\n\n`)
        }
        res.write('data: [DONE]\n\n')
        res.end()
        return
      }
      res.writeHead(200, { 'Content-Type': 'application/json' })
      res.end(JSON.stringify(reply))
    })
    return
  }
  res.writeHead(404, { 'Content-Type': 'application/json' })
  res.end(JSON.stringify({ error: 'not found' }))
})

server.listen(PORT, '127.0.0.1', () => {
  console.log(`mock openai server listening on http://127.0.0.1:${PORT}`)
})
