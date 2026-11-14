export function knowledgeBaseId(value = 1) {
  const id = Number(value)
  if (!Number.isSafeInteger(id) || id <= 0) throw new Error('请选择有效的知识库')
  return id
}

export function captureUploadContext({ file, fileMd5, knowledgeBaseId: owner }) {
  if (!file || !fileMd5) throw new Error('请选择文件并等待 MD5 计算完成')
  return Object.freeze({ file, fileMd5, knowledgeBaseId: knowledgeBaseId(owner) })
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

export async function streamScopedReply({ fetchEventSource, token, message, chatId = null, onChunk }) {
  await fetchEventSource('/api/customer-service/chat/completion', {
    method: 'POST',
    openWhenHidden: true,
    signal: token.controller.signal,
    headers: { 'Content-Type': 'application/json', Accept: 'text/event-stream' },
    body: JSON.stringify({ message, chatId, knowledgeBaseId: token.knowledgeBaseId }),
    async onopen(response) {
      const contentType = response.headers.get('content-type') || ''
      if (!response.ok || !contentType.toLowerCase().startsWith('text/event-stream')) {
        throw new Error(`客服连接失败（HTTP ${response.status}），请稍后重试`)
      }
    },
    onmessage(event) {
      if (!token.isCurrent()) return
      if (event.event === 'close') {
        token.controller.abort()
        return
      }
      if (event.event && event.event !== 'message') return
      if (event.data === '[DONE]') return
      let chunk = event.data
      try {
        const parsed = JSON.parse(event.data)
        if (parsed && typeof parsed === 'object' && typeof parsed.v === 'string') chunk = parsed.v
      } catch {
        // The current Flux<String> endpoint sends plain text SSE data.
      }
      if (typeof chunk === 'string' && chunk) onChunk(chunk)
    },
    onerror(error) {
      // Throwing disables fetch-event-source's automatic infinite retry loop.
      throw error
    },
  })
}

export function uploadAvailability(file) {
  if (Number(file.status) === 4) return { kind: 'failed', text: '文件解析失败，尚不可检索，请检查文件后重新上传', status: 'exception' }
  if (Number(file.status) !== 3) return { kind: 'processing', text: '文件已接收，正在解析和向量化，请在列表查看处理状态', status: 'active' }
  if (file.indexStatus === 'FAILED') return { kind: 'indexFailed', text: '文件已解析，但索引同步失败，请重建索引后重试', status: 'exception' }
  if (file.indexStatus !== 'SYNCED') return { kind: 'indexing', text: '文件已解析，等待索引同步完成后可完整检索', status: 'active' }
  return { kind: 'ready', text: '文件和检索索引已就绪', status: 'success' }
}
