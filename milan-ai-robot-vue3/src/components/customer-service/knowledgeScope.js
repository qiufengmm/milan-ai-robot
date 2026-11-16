import { getCsrfHeader } from '../../auth/csrfState.js'

export function serverErrorDisplay(payload) {
  const code = typeof payload?.code === 'string' && /^[A-Z][A-Z0-9_]{0,63}$/.test(payload.code)
    ? payload.code
    : null
  return { message: '生成回答时遇到问题，请稍后重试。', code }
}

export function knowledgeBaseId(value) {
  const id = Number(value)
  if (!Number.isSafeInteger(id) || id <= 0) throw new Error('请选择有效的知识库')
  return id
}

export function captureUploadContext({ file, fileMd5, knowledgeBaseId: owner, documentId = null }) {
  if (!file || !fileMd5) throw new Error('请选择文件并等待 MD5 计算完成')
  const versionOwner = documentId == null || documentId === '' ? null : Number(documentId)
  if (versionOwner !== null && (!Number.isSafeInteger(versionOwner) || versionOwner <= 0)) throw new Error('替换资料标识无效')
  return Object.freeze({ file, fileMd5, knowledgeBaseId: knowledgeBaseId(owner), documentId: versionOwner })
}

// Each reply owns its AbortController and identity. Cancellation also invalidates
// callbacks already queued by the browser, even if abort arrives too late.
export function createReplyScope() {
  let active = null
  const cancel = () => {
    const previous = active
    active = null
    previous?.controller.abort()
  }
  return {
    cancel,
    begin(owner) {
      cancel()
      const token = {
        knowledgeBaseId: knowledgeBaseId(owner),
        controller: new AbortController(),
        isCurrent: () => active === token && !token.controller.signal.aborted,
      }
      active = token
      return token
    },
    finish(token) {
      if (active !== token) return false
      active = null
      return true
    },
  }
}

export async function streamScopedReply({ fetchEventSource, token, message, chatId, requestId, onMeta, onSources, onChunk, onServerError }) {
  let completed = false
  let serverFailed = false
  await fetchEventSource('/api/customer-service/chat/completion', {
    method: 'POST',
    openWhenHidden: true,
    credentials: 'same-origin',
    signal: token.controller.signal,
    headers: { 'Content-Type': 'application/json', Accept: 'text/event-stream', ...getCsrfHeader() },
    body: JSON.stringify({ message, chatId, requestId }),
    async onopen(response) {
      const contentType = response.headers.get('content-type') || ''
      if (response.status === 401) {
        window.location.hash = '/login?expired=1'
        throw new Error('登录状态已失效')
      }
      if (!response.ok || !contentType.toLowerCase().startsWith('text/event-stream')) {
        throw new Error(`客服连接失败（HTTP ${response.status}），请稍后重试`)
      }
    },
    onmessage(event) {
      if (!token.isCurrent()) return
      let payload
      try {
        payload = JSON.parse(event.data)
      } catch {
        throw new Error('回答流格式无效')
      }
      switch (event.event) {
        case 'meta': onMeta?.(payload); break
        case 'sources': onSources?.(Array.isArray(payload) ? payload : []); break
        case 'delta': if (typeof payload.text === 'string' && payload.text) onChunk?.(payload.text); break
        case 'done': completed = true; break
        case 'error':
          serverFailed = true
          onServerError?.(payload)
          throw new Error(payload.message || '回答生成失败')
        default: break
      }
    },
    onclose() {
      if (!completed && !serverFailed) throw new Error('回答流提前结束，服务端未确认保存')
    },
    onerror(error) {
      // Throwing disables reconnection and prevents a duplicate model request.
      throw error
    },
  })
  if (!completed && !serverFailed && token.isCurrent()) {
    throw new Error('回答流提前结束，服务端未确认保存')
  }
}

export function uploadAvailability(file) {
  if (Number(file.status) === 4) return { kind: 'failed', text: '文件解析失败，尚不可检索，请检查文件后重新上传', status: 'exception' }
  if (Number(file.status) !== 3) return { kind: 'processing', text: '文件已接收，正在解析和向量化，请在列表查看处理状态', status: 'active' }
  if (file.indexStatus === 'FAILED') return { kind: 'indexFailed', text: '文件已解析，但索引同步失败，请重建索引后重试', status: 'exception' }
  if (file.indexStatus !== 'SYNCED') return { kind: 'indexing', text: '文件已解析，等待索引同步完成后可完整检索', status: 'active' }
  return { kind: 'ready', text: '文件和检索索引已就绪', status: 'success' }
}
