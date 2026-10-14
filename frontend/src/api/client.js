const BASE = '/api'
const TOKEN_KEY = 'assistant.token'

export function getToken() {
  return localStorage.getItem(TOKEN_KEY) || ''
}

export function setToken(token) {
  if (token) {
    localStorage.setItem(TOKEN_KEY, token)
  } else {
    localStorage.removeItem(TOKEN_KEY)
  }
}

/** 401 单独成型：调用方要能区分"没登录"和"请求出错"。 */
export class AuthError extends Error {
  constructor(message) {
    super(message)
    this.name = 'AuthError'
    this.status = 401
  }
}

async function request(path, options = {}) {
  const token = getToken()
  const headers = { 'Content-Type': 'application/json', ...(options.headers || {}) }
  if (token) {
    headers.Authorization = `Bearer ${token}`
  }
  const response = await fetch(BASE + path, {
    ...options,
    headers
  })
  const text = await response.text()
  let payload = null
  if (text) {
    try {
      payload = JSON.parse(text)
    } catch {
      throw new Error(`响应不是 JSON：${text.slice(0, 120)}`)
    }
  }
  if (response.status === 401) {
    setToken('')
    throw new AuthError(payload?.error || '未登录或会话已过期')
  }
  if (!response.ok) {
    throw new Error(payload?.error || `HTTP ${response.status}`)
  }
  return payload
}

export const authApi = {
  login: (username, password) =>
    request('/auth/login', { method: 'POST', body: JSON.stringify({ username, password }) }),
  logout: () => request('/auth/logout', { method: 'POST' }),
  me: () => request('/auth/me'),
  users: () => request('/auth/users'),
  changePassword: (oldPassword, newPassword) =>
    request('/auth/password', { method: 'POST', body: JSON.stringify({ oldPassword, newPassword }) })
}

/** 管理面（/api/admin/**，均限 admin）：用户与角色维护。 */
export const adminApi = {
  users: () => request('/admin/users'),
  roles: () => request('/admin/roles'),
  createUser: (payload) => request('/admin/users', { method: 'POST', body: JSON.stringify(payload) }),
  updateUser: (id, payload) => request(`/admin/users/${id}`, { method: 'PUT', body: JSON.stringify(payload) }),
  resetPassword: (id, newPassword) =>
    request(`/admin/users/${id}/password`, { method: 'POST', body: JSON.stringify({ newPassword }) }),
  domainAcl: (code) => request(`/admin/domains/${code}/acl`),
  saveDomainAcl: (code, rules) =>
    request(`/admin/domains/${code}/acl`, { method: 'PUT', body: JSON.stringify({ rules }) }),
  // 对外 API Key（创建响应里的 key 明文只出现一次）
  apiKeys: () => request('/admin/api-keys'),
  createApiKey: (payload) => request('/admin/api-keys', { method: 'POST', body: JSON.stringify(payload) }),
  setApiKeyEnabled: (id, enabled) =>
    request(`/admin/api-keys/${id}/enabled`, { method: 'POST', body: JSON.stringify({ enabled }) }),
  revokeApiKey: (id) => request(`/admin/api-keys/${id}`, { method: 'DELETE' })
}

/** 钉钉登录入口（免登段免鉴权）+ 组织同步（admin）。 */
export const dingtalkApi = {
  enabled: () => request('/auth/dingtalk/enabled'),
  config: () => request('/auth/dingtalk/config'),
  callback: (authCode) =>
    request('/auth/dingtalk/callback', { method: 'POST', body: JSON.stringify({ authCode }) }),
  sync: () => request('/admin/dingtalk/sync', { method: 'POST' })
}

export const settingsApi = {  list: () => request('/settings'),
  save: (code, values) =>
    request(`/settings/${code}`, { method: 'PUT', body: JSON.stringify({ values }) }),
  test: (code) => request(`/settings/${code}/test`, { method: 'POST' }),
  clear: (code, field) => request(`/settings/${code}/${field}`, { method: 'DELETE' })
}

export const domainsApi = {
  list: () => request('/domains'),
  selfCheck: () => request('/domains/selfcheck'),
  // 知识域管理（均限 admin）
  create: (payload) => request('/domains', { method: 'POST', body: JSON.stringify(payload) }),
  update: (code, payload) => request(`/domains/${code}`, { method: 'PUT', body: JSON.stringify(payload) }),
  remove: (code) => request(`/domains/${code}`, { method: 'DELETE' })
}

export const ingestApi = {
  documents: () => request('/documents'),
  scan: () => request('/ingest/scan', { method: 'POST' }),
  tasks: (limit = 50) => request(`/ingest/tasks?limit=${limit}`),
  retry: (id) => request(`/ingest/tasks/${id}/retry`, { method: 'POST' }),
  retryFailed: () => request('/ingest/retry-failed', { method: 'POST' }),
  /** 解析预览（P1）：把某份文档解析出的块列出来人工确认。 */
  parsePreview: (id) =>
    request('/ingest/parse-preview', { method: 'POST', body: JSON.stringify({ documentId: id }) }),

  /** 浏览器上传（P1）：multipart 表单，不能用 request() 的 JSON 头——交给浏览器自己带 boundary。 */
  upload: async (formData) => {
    const token = getToken()
    const headers = {}
    if (token) headers.Authorization = `Bearer ${token}`
    const response = await fetch(`${BASE}/ingest/upload`, { method: 'POST', headers, body: formData })
    if (response.status === 401) {
      setToken('')
      throw new AuthError('未登录或会话已过期')
    }
    const text = await response.text()
    let payload = null
    if (text) {
      try {
        payload = JSON.parse(text)
      } catch {
        throw new Error(`响应不是 JSON：${text.slice(0, 120)}`)
      }
    }
    if (!response.ok) throw new Error(payload?.error || `HTTP ${response.status}`)
    return payload
  },

  /** 文档版本状态切换（现行 / 已废止 / 草案·未生效），admin。 */
  setDocumentStatus: (id, docStatus) =>
    request(`/documents/${id}/status`, { method: 'PUT', body: JSON.stringify({ docStatus }) })
}

/** 从 Content-Disposition 里取文件名：优先 RFC 5987 的 filename*，其次普通 filename。 */
function fileNameOf(header) {
  if (!header) return 'document.pdf'
  const encoded = /filename\*=UTF-8''([^;]+)/i.exec(header)
  if (encoded) {
    try {
      return decodeURIComponent(encoded[1])
    } catch {
      // 编码坏了就退回下一分支
    }
  }
  const plain = /filename="?([^";]+)"?/i.exec(header)
  return plain ? plain[1] : 'document.pdf'
}

/**
 * 文档查看（T19~T22）。
 *
 * 预览与下载**不能**用 <iframe src> / <a href> 直链：那两个入口也需要 Bearer 令牌，
 * 而浏览器直链不会带上我们的 Authorization 头。所以统一用 fetch 取回 Blob，
 * 再交给 iframe（objectURL）或触发下载。
 */
export const documentViewApi = {
  content: (id) => request(`/documents/${id}/content`),

  pdf: async (id, { download = false } = {}) => {
    const token = getToken()
    const headers = {}
    if (token) headers.Authorization = `Bearer ${token}`
    const response = await fetch(`${BASE}/documents/${id}/${download ? 'download' : 'file'}`, { headers })
    if (response.status === 401) {
      setToken('')
      throw new AuthError('未登录或会话已过期')
    }
    if (!response.ok) {
      let message = `HTTP ${response.status}`
      try {
        const body = await response.json()
        if (body?.error) message = body.error
      } catch {
        // 非 JSON 错误体：保留状态码文案
      }
      const error = new Error(message)
      error.status = response.status
      throw error
    }
    return { blob: await response.blob(), fileName: fileNameOf(response.headers.get('Content-Disposition')) }
  }
}

/** 二进制取数（带 Bearer）。与文档预览同因：`<img src>` 不会带 Authorization 头。 */
async function blobRequest(path) {
  const token = getToken()
  const headers = {}
  if (token) headers.Authorization = `Bearer ${token}`
  const response = await fetch(BASE + path, { headers })
  if (response.status === 401) {
    setToken('')
    throw new AuthError('未登录或会话已过期')
  }
  if (!response.ok) {
    let message = `HTTP ${response.status}`
    try {
      const body = await response.json()
      if (body?.error) message = body.error
    } catch {
      // 非 JSON 错误体：保留状态码文案
    }
    const error = new Error(message)
    error.status = response.status
    throw error
  }
  return response.blob()
}

/** 找图：按描述检索图片素材；图片字节同样走 Blob + Authorization。 */
export const mediaApi = {
  search: (payload) => request('/media/search', { method: 'POST', body: JSON.stringify(payload) }),
  thumbnail: (id) => blobRequest(`/media/${id}/thumbnail`),
  raw: (id) => blobRequest(`/media/${id}/raw`),
  reindex: (force = false) => request(`/media/reindex?force=${force}`, { method: 'POST' })
}

/**
 * 一次问答的流式调用。
 *
 * EventSource 只能发 GET、且不能自定义请求头，而这里的问答是 POST + Bearer 令牌，
 * 所以只能自己用 fetch 读 body 流并解析 SSE。
 *
 * 事件顺序：evidences（证据先到，界面可以先画出来）→ delta（正文增量，可多次）→ verdict（校验结论）。
 * 出错时后端只能发 error 事件（响应一旦开始推送，状态码就已经是 200 了）。
 */
async function answerStream(path, payload, handlers = {}, signal) {
  const token = getToken()
  const headers = { 'Content-Type': 'application/json', Accept: 'text/event-stream' }
  if (token) {
    headers.Authorization = `Bearer ${token}`
  }
  const response = await fetch(`${BASE}${path}`, {
    method: 'POST',
    headers,
    signal,
    body: JSON.stringify(payload)
  })
  if (response.status === 401) {
    setToken('')
    throw new AuthError('未登录或会话已过期')
  }
  if (!response.ok) {
    // 还没开始推送时的错误仍是标准 HTTP 错误（例如参数校验 400）
    let message = `HTTP ${response.status}`
    try {
      const body = JSON.parse(await response.text())
      if (body?.error) {
        message = body.error
      }
    } catch {
      // 保持默认信息
    }
    throw new Error(message)
  }

  const reader = response.body.getReader()
  const decoder = new TextDecoder('utf-8')
  let buffer = ''
  let failure = null

  const dispatch = (block) => {
    let name = 'message'
    const data = []
    for (const rawLine of block.split('\n')) {
      if (!rawLine || rawLine.startsWith(':')) continue
      const colon = rawLine.indexOf(':')
      const field = colon < 0 ? rawLine : rawLine.slice(0, colon)
      const value = colon < 0 ? '' : rawLine.slice(colon + 1).replace(/^ /, '')
      if (field === 'event') name = value
      else if (field === 'data') data.push(value)
    }
    if (!data.length) return
    let body
    try {
      body = JSON.parse(data.join('\n'))
    } catch {
      return
    }
    if (name === 'error') {
      failure = new Error(body.error || '生成失败')
      return
    }
    if (typeof handlers[name] === 'function') handlers[name](body)
  }

  for (;;) {
    const { done, value } = await reader.read()
    if (done) break
    // \r 全部丢掉：SSE 允许 CRLF，而网络分片可能正好切在 \r 与 \n 之间
    buffer += decoder.decode(value, { stream: true }).split('\r').join('')
    let index
    while ((index = buffer.indexOf('\n\n')) >= 0) {
      dispatch(buffer.slice(0, index))
      buffer = buffer.slice(index + 2)
      if (failure) {
        await reader.cancel().catch(() => {})
        throw failure
      }
    }
  }
  if (failure) throw failure
}

export const generationApi = {
  /** 非流式：一次拿到完整结果。留着当对照基准——流式出问题时用它判断是链路问题还是模型问题。 */
  answer: (payload) => request('/generation/answer', { method: 'POST', body: JSON.stringify(payload) }),
  answerStream: (payload, handlers) => answerStream('/generation/answer/stream', payload, handlers)
}

/**
 * 深度分析（P3a）。
 *
 * 与快速问答的区别全在事件上：多了 plan（拆成了哪几条子问题）与 step（每一步查了什么、新增几条证据）。
 * 这几步必须显示出来——深度分析要几十秒，用户看不到它在做什么，只会以为卡死了。
 *
 * runId 由**前端**生成：服务端要到拆解完才回 runId，而用户可能在拆解阶段就想取消。
 * 取消走服务端接口（置一个标志位，在下个步骤边界停），不是简单掐断连接——
 * 掐连接只是我们这边不看了，模型那边照样在烧 token。
 */
export const deepApi = {
  answer: (payload) => request('/deep/answer', { method: 'POST', body: JSON.stringify(payload) }),
  answerStream: (payload, handlers, signal) => answerStream('/deep/answer/stream', payload, handlers, signal),
  cancel: (runId) => request('/deep/cancel', { method: 'POST', body: JSON.stringify({ runId }) })
}

/** 运行号：crypto.randomUUID 在非安全上下文（http 局域网访问）里可能没有，退化成时间戳+随机串。 */
export function newRunId() {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID()
  }
  return `run-${Date.now()}-${Math.random().toString(16).slice(2)}`
}

/** 运行记录（T10）。限管理员：账本里有全部人的提问原话与命中情况。 */
export const observeApi = {
  traces: ({ limit = 50, domainCode = '', refused = '' } = {}) => {
    const query = new URLSearchParams({ limit: String(limit) })
    if (domainCode) query.set('domainCode', domainCode)
    // 三态：不传 = 全部，传 true/false 才是筛选
    if (refused !== '') query.set('refused', String(refused))
    return request(`/observe/traces?${query.toString()}`)
  },
  detail: (id) => request(`/observe/traces/${id}`),
  price: () => request('/observe/price'),
  // 反馈：提交不限管理员（只能评自己的问答，后端按账本归属判），读汇总仍是管理员
  feedback: (traceId, payload) => request(`/observe/traces/${traceId}/feedback`, {
    method: 'POST',
    body: JSON.stringify(payload)
  }),
  feedbackReasons: () => request('/observe/feedback/reasons'),
  feedbackSummary: () => request('/observe/feedback/summary')
}

/** 评测（P3）。限管理员：跑一次要花模型钱，指标属运维面。 */
export const evalApi = {
  runs: ({ limit = 20, set = '' } = {}) => {
    const query = new URLSearchParams({ limit: String(limit) })
    if (set) query.set('set', set)
    return request(`/eval/runs?${query.toString()}`)
  },
  detail: (id) => request(`/eval/runs/${id}`),
  // 同步执行：retrieval 秒级，full 要几分钟（30 题 × 一道问答）
  run: (set, mode) => request('/eval/runs', {
    method: 'POST',
    body: JSON.stringify({ set, mode })
  })
}
