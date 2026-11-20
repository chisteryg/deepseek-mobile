/**
 * Mobile bridge: a pairing-protected HTTP/SSE surface over the live DSH Host.
 *
 * The Android client pairs once with a short code, keeps the issued device
 * token, and then talks plain JSON to this server. Every DSH call happens
 * in-process through `ctx.sessionController`, so the phone sees exactly the
 * sessions this desktop owns.
 */
import http from 'node:http'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { randomBytes, randomInt, randomUUID, timingSafeEqual } from 'node:crypto'

export const name = 'mobile-bridge-logic'

const PROTOCOL_VERSION = 1
const PAIR_TTL_MS = 10 * 60 * 1000
const PAIR_MAX_ATTEMPTS = 12
const APPROVAL_TIMEOUT_MS = 90 * 1000
const MAX_BODY_BYTES = 1024 * 1024
const SNAPSHOT_MAX_EVENTS = 150
const TRIM_TEXT = 2000

/** Event types the phone renders; everything else is log noise for a transcript. */
const KEEP_EVENTS = new Set([
  'user/message',
  'assistant/message',
  'tool/call',
  'tool/result',
  'turn/end',
  'session/title',
  'approval/policy',
])

function trimBlocks(blocks) {
  if (!Array.isArray(blocks)) return blocks
  return blocks.map((block) => {
    if (!block || typeof block !== 'object') return block
    if (block.type === 'text' && typeof block.text === 'string' && block.text.length > TRIM_TEXT) {
      return { ...block, text: block.text.slice(0, TRIM_TEXT) + '\n…（已截断）' }
    }
    if (block.type === 'thinking' && typeof block.thinking === 'string' && block.thinking.length > TRIM_TEXT) {
      return { ...block, thinking: block.thinking.slice(0, TRIM_TEXT) + '\n…（已截断）' }
    }
    if (block.type === 'tool_call' && typeof block.arguments === 'string' && block.arguments.length > TRIM_TEXT) {
      return { ...block, arguments: block.arguments.slice(0, TRIM_TEXT) }
    }
    if (block.type === 'image') return { type: 'text', text: '（图片）' }
    return block
  })
}

/** Shrink one durable session event into what a phone transcript needs. */
function compactEvent(event) {
  if (!event || !KEEP_EVENTS.has(event.type)) return null
  const data = event.data && typeof event.data === 'object' ? { ...event.data } : {}
  if (event.type === 'assistant/message') {
    delete data.stream
    if (data.message && typeof data.message === 'object') {
      data.message = { ...data.message, content: trimBlocks(data.message.content) }
    }
  }
  if (event.type === 'tool/result') {
    if (data.message && typeof data.message === 'object') {
      data.message = { ...data.message, content: trimBlocks(data.message.content) }
    }
  }
  if (event.type === 'user/message' && Array.isArray(data.content)) {
    data.content = trimBlocks(data.content)
  }
  if (event.type === 'tool/call' && typeof data.arguments === 'string' && data.arguments.length > TRIM_TEXT) {
    data.arguments = data.arguments.slice(0, TRIM_TEXT)
  }
  return { type: event.type, seq: event.seq, time: event.time, data }
}

function compactRecords(records) {
  const kept = []
  for (const record of records || []) {
    const event = record?.type === 'event' ? record.event : record
    const compact = compactEvent(event)
    if (compact) kept.push(compact)
  }
  return kept.slice(-SNAPSHOT_MAX_EVENTS)
}


function homeDir() {
  return process.env.DSH_HOME || path.join(os.homedir(), '.dsh')
}

function statePath() {
  return path.join(homeDir(), 'mobile-bridge.json')
}

function readState() {
  try {
    return JSON.parse(fs.readFileSync(statePath(), 'utf8'))
  } catch {
    return {}
  }
}

function writeState(next) {
  const file = statePath()
  fs.mkdirSync(path.dirname(file), { recursive: true })
  fs.writeFileSync(file, JSON.stringify(next, null, 2), 'utf8')
}

function lanAddress() {
  const interfaces = os.networkInterfaces()
  for (const entries of Object.values(interfaces)) {
    for (const entry of entries || []) {
      if (entry && entry.family === 'IPv4' && !entry.internal) return entry.address
    }
  }
  return '127.0.0.1'
}

function isLoopback(req) {
  const address = req.socket?.remoteAddress || ''
  return address === '127.0.0.1' || address === '::1' || address === '::ffff:127.0.0.1'
}

function json(res, status, body) {
  const payload = JSON.stringify(body)
  res.writeHead(status, {
    'content-type': 'application/json; charset=utf-8',
    'content-length': Buffer.byteLength(payload),
    'cache-control': 'no-store',
  })
  res.end(payload)
}

function text(res, status, body, contentType = 'text/plain; charset=utf-8') {
  res.writeHead(status, { 'content-type': contentType, 'cache-control': 'no-store' })
  res.end(body)
}

function readBody(req) {
  return new Promise((resolve, reject) => {
    const chunks = []
    let size = 0
    req.on('data', (chunk) => {
      size += chunk.length
      if (size > MAX_BODY_BYTES) {
        reject(new Error('请求体过大'))
        req.destroy()
        return
      }
      chunks.push(chunk)
    })
    req.on('end', () => {
      const raw = Buffer.concat(chunks).toString('utf8')
      if (!raw) return resolve({})
      try {
        resolve(JSON.parse(raw))
      } catch {
        reject(new Error('请求体不是合法 JSON'))
      }
    })
    req.on('error', reject)
  })
}

function sessionTitle(summary) {
  const values = summary?.projections?.values ?? {}
  const title = values.title
  if (typeof title === 'string' && title.trim()) return title.trim()
  if (title && typeof title === 'object' && typeof title.text === 'string' && title.text.trim()) {
    return title.text.trim()
  }
  const cwd = summary?.cwd
  if (typeof cwd === 'string' && cwd) return path.basename(cwd)
  return '未命名会话'
}

export function createBridge(ctx, config = {}) {
  const port = Number(config.port ?? 4318)
  const host = String(config.host ?? '0.0.0.0')
  const relayApprovals = config.relayApprovals !== false

  let state = readState()
  if (!state.token) {
    state.token = randomBytes(24).toString('hex')
    state.devices = []
  }
  let pairCode = String(randomInt(100000, 999999))
  let pairExpiresAt = Date.now() + PAIR_TTL_MS
  let pairAttempts = 0
  let lastClientSeen = 0
  const approvalWaiters = new Map()

  function refreshPairCode() {
    pairCode = String(randomInt(100000, 999999))
    pairExpiresAt = Date.now() + PAIR_TTL_MS
    pairAttempts = 0
  }

  function saveState() {
    writeState(state)
  }

  saveState()

  function authorized(req, url) {
    const header = req.headers.authorization || ''
    let token = header.startsWith('Bearer ') ? header.slice(7).trim() : ''
    if (!token) token = url.searchParams.get('token') || ''
    if (!token || !state.token) return false
    const a = Buffer.from(token)
    const b = Buffer.from(state.token)
    if (a.length !== b.length) return false
    const ok = timingSafeEqual(a, b)
    if (ok) lastClientSeen = Date.now()
    return ok
  }

  function controller() {
    return ctx.get('sessionController')
  }

  async function listSessions(signal) {
    const svc = controller()
    if (!svc) throw new Error('sessionController 尚未挂载')
    const value = await svc.list({}, signal)
    const items = Array.isArray(value?.items) ? value.items : []
    const sessions = []
    for (const item of items.slice(0, 60)) {
      let pending = []
      if (item.running) {
        try {
          const current = await svc.state({ sessionId: item.sessionId }, signal)
          pending = current?.pendingInput ?? []
        } catch {
          pending = []
        }
      }
      sessions.push({
        id: item.sessionId,
        title: sessionTitle(item),
        running: Boolean(item.running),
        pending,
        updatedAt: Number(item.updatedAt ?? 0),
        cwd: item.cwd ?? '',
        agentAvailable: Boolean(item.agentAvailable),
        blank: Boolean(item.blank),
      })
    }
    sessions.sort((a, b) => b.updatedAt - a.updatedAt)
    return sessions
  }

  async function sessionState(sessionId, signal) {
    const svc = controller()
    if (!svc) throw new Error('sessionController 尚未挂载')
    return svc.state({ sessionId }, signal)
  }

  async function sessionFollowFrames(sessionId, signal) {
    const svc = controller()
    if (!svc) throw new Error('sessionController 尚未挂载')
    return svc.follow(
      {
        address: { kind: 'session', sessionId },
        activate: true,
        assistantStream: true,
        maxMessages: 120,
      },
      signal,
    )
  }

  async function prompt(sessionId, body) {
    const svc = controller()
    if (!svc) throw new Error('sessionController 尚未挂载')
    const value = String(body?.text ?? '')
    const content = []
    if (Array.isArray(body?.files)) {
      for (const receiptId of body.files) {
        if (receiptId) content.push({ type: 'file', receiptId: String(receiptId) })
      }
    }
    if (Array.isArray(body?.images)) {
      for (const image of body.images) {
        if (image && image.data) {
          content.push({
            type: 'image',
            mediaType: String(image.mediaType || 'image/jpeg'),
            data: String(image.data),
          })
        }
      }
    }
    if (value.trim()) content.push({ type: 'text', text: value })
    if (content.length === 0) throw new Error('消息不能为空')
    return svc.prompt(
      {
        requestId: randomUUID(),
        sessionId,
        mode: body?.mode === 'steer' ? 'steer' : 'queue',
        content,
        clientTimeZone: Intl.DateTimeFormat().resolvedOptions().timeZone,
      },
      new AbortController().signal,
    )
  }

  function cancel(sessionId) {
    const svc = controller()
    if (!svc) throw new Error('sessionController 尚未挂载')
    return svc.cancel({ sessionId })
  }

  async function modelCatalog() {
    const svc = controller()
    if (!svc) throw new Error('sessionController 尚未挂载')
    if (typeof svc.modelCatalog !== 'function') throw new Error('当前 DSH 版本不支持模型目录')
    return svc.modelCatalog()
  }

  async function selectModel(sessionId, body) {
    const svc = controller()
    if (!svc) throw new Error('sessionController 尚未挂载')
    const provider = String(body?.provider ?? '')
    const model = String(body?.model ?? '')
    if (!provider || !model) throw new Error('缺少 provider 或 model')
    const request = { sessionId, provider, model }
    if (body?.reasoningEffort) request.reasoningEffort = String(body.reasoningEffort)
    if (typeof svc.selectModel !== 'function') throw new Error('当前 DSH 版本不支持模型切换')
    return svc.selectModel(request)
  }

  async function uploadDocument(sessionId, body) {
    const uploads = ctx.get('fileUploads')
    if (!uploads) throw new Error('当前 DSH 版本不支持文件上传')
    const encoded = String(body?.data ?? '')
    if (!encoded) throw new Error('缺少文件内容')
    const bytes = Buffer.from(encoded, 'base64')
    const name = String(body?.name ?? 'file')
    const chunks = (async function* () {
      yield bytes
    })()
    const value = await uploads.uploadStream({ sessionId, data: chunks, name })
    return {
      receiptId: value.receiptId,
      name: value.file?.name ?? name,
      bytes: value.file?.bytes ?? bytes.length,
    }
  }

  function sessionById(sessionId) {
    const sessions = ctx.get('sessions')
    if (!sessions) return null
    return (sessions.list() || []).find((session) => session.id === sessionId) || null
  }

  async function readPermissions(sessionId) {
    const session = sessionById(sessionId)
    const presets = ctx.get('permissionPresets')
    const approval = ctx.get('approval')
    return {
      preset: session && presets ? presets.current(session) : null,
      approvalPolicy: session && approval ? approval.overrideOf(session) ?? null : null,
      catalog: presets ? presets.catalog() : null,
    }
  }

  async function writePermissions(sessionId, body) {
    const session = sessionById(sessionId)
    if (!session) throw new Error('会话不存在')
    const presets = ctx.get('permissionPresets')
    if (body?.preset && presets) presets.set(session, String(body.preset))
    if (body?.approvalPolicy) {
      const approval = ctx.get('approval')
      const controller = ctx.get('sessionController')
      if (!approval || !controller) throw new Error('当前 DSH 版本不支持审批策略')
      const agent = await controller.resolveAgent(sessionId)
      approval.setPolicy(agent, body.approvalPolicy === 'never' ? 'never' : 'ask')
    }
    return readPermissions(sessionId)
  }

  function interactions() {
    const items = []
    for (const [id, waiter] of approvalWaiters) {
      items.push({
        id,
        kind: 'approval',
        sessionId: waiter.sessionId,
        title: `需要批准：${waiter.toolName}`,
        detail: waiter.reason || '',
        createdAt: waiter.createdAt,
      })
    }
    return { items }
  }

  function resolveApproval(id, decision) {
    const waiter = approvalWaiters.get(id)
    if (!waiter) return false
    approvalWaiters.delete(id)
    clearTimeout(waiter.timer)
    waiter.resolve(decision === 'allow' ? 'allowed-once' : 'rejected')
    return true
  }

  if (relayApprovals) {
    ctx.on('approval/request', async (req, next) => {
      const phonePresent = Date.now() - lastClientSeen < 60_000
      if (!phonePresent) return next()
      const id = randomBytes(8).toString('hex')
      const sessionId = req?.agent?.id
      if (!sessionId) return next()
      try {
        const outcome = await new Promise((resolve) => {
          const timer = setTimeout(() => {
            approvalWaiters.delete(id)
            resolve(null)
          }, APPROVAL_TIMEOUT_MS)
          approvalWaiters.set(id, {
            sessionId,
            toolName: req.toolName ?? '工具',
            reason: req.reason ?? '',
            createdAt: Date.now(),
            resolve,
            timer,
          })
          req.signal?.addEventListener('abort', () => {
            approvalWaiters.delete(id)
            clearTimeout(timer)
            resolve('cancelled')
          })
        })
        if (outcome) return outcome
        return next()
      } catch {
        return next()
      }
    })
  }

  function sseStart(res) {
    res.writeHead(200, {
      'content-type': 'text/event-stream; charset=utf-8',
      'cache-control': 'no-store',
      connection: 'keep-alive',
      'x-accel-buffering': 'no',
    })
    res.write(': connected\n\n')
  }

  function sseSend(res, event, data) {
    res.write(`event: ${event}\ndata: ${JSON.stringify(data)}\n\n`)
  }

  async function streamSession(req, res, sessionId) {
    sseStart(res)
    const abort = new AbortController()
    req.on('close', () => abort.abort())
    const ping = setInterval(() => {
      try {
        res.write(': ping\n\n')
      } catch {
        // socket already gone; the close handler aborts the iteration
      }
    }, 15_000)
    let stateTimer = null
    const pushState = () => {
      if (stateTimer) return
      stateTimer = setTimeout(async () => {
        stateTimer = null
        try {
          const current = await sessionState(sessionId, abort.signal)
          sseSend(res, 'state', current)
        } catch {
          // a state read failure is not fatal for the stream
        }
      }, 400)
    }
    try {
      const initial = await sessionState(sessionId, abort.signal).catch(() => null)
      if (initial) sseSend(res, 'state', initial)
      const frames = await sessionFollowFrames(sessionId, abort.signal)
      for await (const frame of frames) {
        if (abort.signal.aborted) break
        if (frame?.type === 'snapshot') {
          sseSend(res, 'snapshot', {
            header: frame.header,
            cursor: frame.cursor,
            records: compactRecords(frame.records),
            hasMore: frame.hasMore,
            projections: frame.projections,
          })
        } else if (frame?.type === 'event') {
          const compact = compactEvent(frame.event)
          if (compact) {
            sseSend(res, 'event', compact)
            pushState()
          }
        } else if (frame?.type === 'assistant-stream') {
          sseSend(res, 'assistant', frame.frame)
        }
      }
      sseSend(res, 'closed', { reason: 'follow-ended' })
    } catch (error) {
      if (!abort.signal.aborted) {
        sseSend(res, 'failure', { message: error?.message ?? String(error) })
      }
    } finally {
      clearInterval(ping)
      if (stateTimer) clearTimeout(stateTimer)
      try {
        res.end()
      } catch {
        // already ended
      }
    }
  }

  function landingPage(req, res) {
    const address = lanAddress()
    const base = `http://${address}:${port}`
    const loopback = isLoopback(req)
    const codeBlock = loopback
      ? `<div class="code">${pairCode}</div><p class="hint">10 分钟内有效，手机上输入这个配对码。</p>`
      : `<p class="hint">为安全起见，请在电脑上打开 <code>http://127.0.0.1:${port}/pair</code> 查看配对码。</p>`
    const html = `<!doctype html>
<html lang="zh-CN"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>DSH 手机桥接</title>
<style>
 body{margin:0;font:16px/1.6 system-ui,-apple-system,"Segoe UI",sans-serif;background:#0e1016;color:#e9ebf2;padding:32px}
 .card{max-width:520px;margin:0 auto;background:#151822;border-radius:16px;padding:24px}
 h1{font-size:20px;margin:0 0 4px} .sub{color:#98a0b5;font-size:13px;margin-bottom:20px}
 .row{margin:14px 0} .label{color:#98a0b5;font-size:12px;letter-spacing:.04em;text-transform:uppercase}
 .value{font-family:ui-monospace,Consolas,monospace;font-size:15px;word-break:break-all}
 .code{font-family:ui-monospace,Consolas,monospace;font-size:34px;letter-spacing:10px;color:#8fa5ff;margin:10px 0}
 .hint{color:#98a0b5;font-size:13px} code{background:#1e2230;padding:2px 6px;border-radius:6px}
</style></head><body><div class="card">
<h1>DSH 手机桥接</h1>
<div class="sub">在「DeepSeek」安卓 App 的 Harness 模式里填写下面的地址和配对码。</div>
<div class="row"><div class="label">服务地址</div><div class="value">${base}</div></div>
<div class="row"><div class="label">配对码</div>${codeBlock}</div>
<div class="row"><div class="label">会话数</div><div class="value">${state.lastSessionCount ?? '—'}</div></div>
</div></body></html>`
    text(res, 200, html, 'text/html; charset=utf-8')
  }

  async function handle(req, res) {
    const url = new URL(req.url || '/', `http://${req.headers.host || 'localhost'}`)
    const segments = url.pathname.split('/').filter(Boolean)

    if (req.method === 'GET' && (url.pathname === '/' || url.pathname === '/pair')) {
      return landingPage(req, res)
    }

    if (req.method === 'POST' && url.pathname === '/api/pair') {
      const body = await readBody(req)
      const code = String(body?.code ?? '').trim()
      pairAttempts += 1
      if (Date.now() > pairExpiresAt) {
        refreshPairCode()
        return json(res, 410, { error: '配对码已过期，请在电脑上刷新页面获取新配对码' })
      }
      if (pairAttempts > PAIR_MAX_ATTEMPTS) {
        refreshPairCode()
        return json(res, 429, { error: '尝试次数过多，配对码已重置' })
      }
      if (code !== pairCode) {
        return json(res, 401, { error: '配对码不正确' })
      }
      state.devices = Array.isArray(state.devices) ? state.devices : []
      state.devices.push({
        name: String(body?.name ?? '').slice(0, 80) || 'Android 设备',
        pairedAt: new Date().toISOString(),
      })
      saveState()
      refreshPairCode()
      return json(res, 200, {
        token: state.token,
        host: lanAddress(),
        port,
        version: PROTOCOL_VERSION,
        name: os.hostname(),
      })
    }

    if (!url.pathname.startsWith('/api/')) {
      return json(res, 404, { error: 'not found' })
    }
    if (!authorized(req, url)) {
      return json(res, 401, { error: '未授权：请先配对' })
    }

    if (req.method === 'GET' && url.pathname === '/api/health') {
      const sessions = await listSessions(new AbortController().signal).catch(() => [])
      state.lastSessionCount = sessions.length
      saveState()
      return json(res, 200, {
        ok: true,
        name: os.hostname(),
        version: PROTOCOL_VERSION,
        sessions: sessions.length,
        running: sessions.filter((session) => session.running).length,
        time: Date.now(),
      })
    }

    if (req.method === 'GET' && url.pathname === '/api/sessions') {
      const sessions = await listSessions(new AbortController().signal)
      return json(res, 200, { sessions })
    }

    if (req.method === 'GET' && url.pathname === '/api/interactions') {
      return json(res, 200, interactions())
    }

    if (req.method === 'GET' && url.pathname === '/api/models') {
      const catalog = await modelCatalog()
      return json(res, 200, catalog ?? {})
    }

    if (req.method === 'POST' && segments[1] === 'interactions' && segments[2]) {
      const body = await readBody(req)
      const ok = resolveApproval(segments[2], String(body?.decision ?? ''))
      return json(res, ok ? 200 : 404, ok ? { ok: true } : { error: '这条审批已失效' })
    }

    if (segments[1] === 'sessions' && segments[2]) {
      const sessionId = decodeURIComponent(segments[2])
      if (req.method === 'GET' && segments.length === 3) {
        const signal = new AbortController().signal
        const [sessions, current] = await Promise.all([
          listSessions(signal).catch(() => []),
          sessionState(sessionId, signal).catch(() => null),
        ])
        const summary = sessions.find((session) => session.id === sessionId) ?? null
        return json(res, 200, { session: summary, state: current })
      }
      if (req.method === 'GET' && segments[3] === 'stream') {
        return streamSession(req, res, sessionId)
      }
      if (req.method === 'POST' && segments[3] === 'prompt') {
        const body = await readBody(req)
        const value = await prompt(sessionId, body)
        return json(res, 200, value ?? { accepted: true })
      }
      if (req.method === 'POST' && segments[3] === 'cancel') {
        const value = cancel(sessionId)
        return json(res, 200, value ?? { accepted: true })
      }
      if (req.method === 'POST' && segments[3] === 'model') {
        const body = await readBody(req)
        const value = await selectModel(sessionId, body)
        return json(res, 200, value ?? { accepted: true })
      }
      if (req.method === 'POST' && segments[3] === 'upload') {
        const body = await readBody(req)
        const value = await uploadDocument(sessionId, body)
        return json(res, 200, value)
      }
      if (req.method === 'GET' && segments[3] === 'permissions') {
        const value = await readPermissions(sessionId)
        return json(res, 200, value)
      }
      if (req.method === 'POST' && segments[3] === 'permissions') {
        const body = await readBody(req)
        const value = await writePermissions(sessionId, body)
        return json(res, 200, value)
      }
    }

    return json(res, 404, { error: 'not found' })
  }

  const server = http.createServer((req, res) => {
    handle(req, res).catch((error) => {
      const message = error?.message ?? String(error)
      try {
        json(res, 500, { error: message })
      } catch {
        // response already started (SSE); nothing else to do
      }
    })
  })
  server.on('clientError', (_error, socket) => {
    try {
      socket.end('HTTP/1.1 400 Bad Request\r\n\r\n')
    } catch {
      // ignore
    }
  })
  server.on('error', (error) => {
    console.error('[mobile-bridge] 监听失败：', error?.message ?? error)
  })

  server.listen(port, host, () => {
    const address = lanAddress()
    console.log(`[mobile-bridge] 手机桥接已启动：http://${address}:${port}`)
    console.log(`[mobile-bridge] 配对码 ${pairCode}（10 分钟内有效，也可在电脑上打开 http://127.0.0.1:${port}/pair 查看）`)
  })

  return () => {
    for (const waiter of approvalWaiters.values()) {
      clearTimeout(waiter.timer)
      waiter.resolve('unavailable')
    }
    approvalWaiters.clear()
    server.close()
  }
}
