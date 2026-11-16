import test from 'node:test'
import assert from 'node:assert/strict'
import { captureUploadContext, createReplyScope, streamScopedReply, uploadAvailability } from '../src/components/customer-service/knowledgeScope.js'

test('an upload captures immutable library and optional document version target', () => {
  const file = { name: 'policy.md', size: 42 }
  const upload = captureUploadContext({ file, fileMd5: 'a'.repeat(32), knowledgeBaseId: 2, documentId: 9 })
  assert.deepEqual(upload, { file, fileMd5: 'a'.repeat(32), knowledgeBaseId: 2, documentId: 9 })
  assert.equal(Object.isFrozen(upload), true)
  assert.throws(() => captureUploadContext({ file, fileMd5: 'a'.repeat(32), knowledgeBaseId: 0 }))
  assert.throws(() => captureUploadContext({ file, fileMd5: 'a'.repeat(32), knowledgeBaseId: 2, documentId: -3 }))
  assert.throws(() => captureUploadContext({ file, fileMd5: 'a'.repeat(32) }), /知识库/)
})

test('switch cancellation aborts the old reply and prevents late frames entering another library', async () => {
  const scope = createReplyScope()
  const old = scope.begin(2)
  const chunks = []
  let deliver
  const pending = streamScopedReply({
    token: old, message: '旧库问题', chatId: 'chat-1', requestId: 'req-1', onChunk: chunk => chunks.push(chunk),
    fetchEventSource: async (_url, options) => {
      await options.onopen(new Response('', { headers: { 'content-type': 'text/event-stream' } }))
      assert.deepEqual(JSON.parse(options.body), { message: '旧库问题', chatId: 'chat-1', requestId: 'req-1' })
      deliver = event => options.onmessage(event)
      deliver({ event: 'delta', data: '{"text":"第一段"}' })
      scope.cancel()
      deliver({ event: 'delta', data: '{"text":"迟到内容"}' })
    },
  })
  await pending
  assert.deepEqual(chunks, ['第一段'])
  assert.equal(old.controller.signal.aborted, true)
  assert.equal(old.isCurrent(), false)
})

test('SSE dispatches protocol events and resolves only after the persisted done event', async () => {
  const token = createReplyScope().begin(4)
  const received = { meta: null, sources: null, chunks: [], serverError: null }
  await streamScopedReply({
    token,
    message: 'VPN 怎么连？',
    chatId: 'chat-2',
    requestId: 'req-2',
    onMeta: value => { received.meta = value },
    onSources: value => { received.sources = value },
    onChunk: value => received.chunks.push(value),
    onServerError: value => { received.serverError = value },
    fetchEventSource: async (url, options) => {
      assert.equal(url, '/api/customer-service/chat/completion')
      assert.equal(options.credentials, 'same-origin')
      assert.deepEqual(Object.keys(JSON.parse(options.body)).sort(), ['chatId', 'message', 'requestId'])
      await options.onopen(new Response('', { headers: { 'content-type': 'text/event-stream;charset=UTF-8' } }))
      options.onmessage({ event: 'meta', data: '{"requestId":"req-2","answerId":42}' })
      options.onmessage({ event: 'sources', data: '[{"title":"VPN 指引","historical":false}]' })
      options.onmessage({ event: 'delta', data: '{"text":"请先连接公司网络。"}' })
      options.onmessage({ event: 'done', data: '{"answerId":42}' })
      options.onclose()
    },
  })
  assert.deepEqual(received.meta, { requestId: 'req-2', answerId: 42 })
  assert.deepEqual(received.sources, [{ title: 'VPN 指引', historical: false }])
  assert.deepEqual(received.chunks, ['请先连接公司网络。'])
  assert.equal(received.serverError, null)
})

test('missing done rejects rather than treating an early end as success', async () => {
  const token = createReplyScope().begin(1)
  await assert.rejects(streamScopedReply({
    token, message: '问题', chatId: 'chat', requestId: 'req',
    fetchEventSource: async (_url, options) => {
      await options.onopen(new Response('', { headers: { 'content-type': 'text/event-stream' } }))
      options.onmessage({ event: 'delta', data: '{"text":"部分回答"}' })
      options.onclose()
    },
  }), /提前结束/)
})

test('server error is surfaced and prevents transport retry', async () => {
  const token = createReplyScope().begin(1)
  let received
  await assert.rejects(streamScopedReply({
    token, message: '问题', chatId: 'chat', requestId: 'req', onServerError: error => { received = error },
    fetchEventSource: async (_url, options) => {
      await options.onopen(new Response('', { headers: { 'content-type': 'text/event-stream' } }))
      options.onmessage({ event: 'error', data: '{"code":"GENERATION_FAILED","message":"生成失败"}' })
    },
  }), /生成失败/)
  assert.equal(received.code, 'GENERATION_FAILED')
})

test('active turn error display retains the safe server code without exposing non-code text', async () => {
  const { serverErrorDisplay } = await import('../src/components/customer-service/knowledgeScope.js')
  assert.equal(typeof serverErrorDisplay, 'function')
  assert.deepEqual(serverErrorDisplay({ code: 'MODEL_TIMEOUT', message: '生成回答时遇到问题，请稍后重试。' }), {
    message: '生成回答时遇到问题，请稍后重试。',
    code: 'MODEL_TIMEOUT',
  })
  assert.deepEqual(serverErrorDisplay({ code: 'java.lang.IllegalStateException: secret', message: 'secret' }), {
    message: '生成回答时遇到问题，请稍后重试。',
    code: null,
  })
})

test('transport callback errors escape so fetch-event-source cannot retry forever', async () => {
  const token = createReplyScope().begin(1)
  const failure = new Error('connection lost')
  await assert.rejects(streamScopedReply({ token, message: '问题', fetchEventSource: async (_url, options) => options.onerror(failure) }), error => error === failure)
})

test('an existing file is searchable only after parsing and all indexes have completed', () => {
  assert.equal(uploadAvailability({ status: 4, indexStatus: 'FAILED' }).kind, 'failed')
  assert.equal(uploadAvailability({ status: 2, indexStatus: 'NOT_READY' }).kind, 'processing')
  assert.equal(uploadAvailability({ status: 3, indexStatus: 'PENDING' }).kind, 'indexing')
  assert.equal(uploadAvailability({ status: 3, indexStatus: 'FAILED' }).kind, 'indexFailed')
  assert.equal(uploadAvailability({ status: 3, indexStatus: 'SYNCED' }).kind, 'ready')
  assert.equal(uploadAvailability({}).kind, 'processing')
})
