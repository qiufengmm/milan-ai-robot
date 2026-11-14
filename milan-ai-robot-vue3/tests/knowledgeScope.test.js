import test from 'node:test'
import assert from 'node:assert/strict'
import { captureUploadContext, createReplyScope, streamScopedReply, uploadAvailability } from '../src/components/customer-service/knowledgeScope.js'

test('an upload retains the file, hash and knowledge owner captured before requests begin', () => {
  const file = { name: 'policy.md', size: 42 }
  let selection = { file, fileMd5: 'a'.repeat(32), knowledgeBaseId: 2 }
  const upload = captureUploadContext(selection)
  selection = { file: { name: 'other.md', size: 88 }, fileMd5: 'b'.repeat(32), knowledgeBaseId: 3 }
  assert.equal(upload.file, file)
  assert.equal(upload.fileMd5, 'a'.repeat(32))
  assert.equal(upload.knowledgeBaseId, 2)
  assert.equal(Object.isFrozen(upload), true)
  assert.throws(() => captureUploadContext({ file, fileMd5: 'a'.repeat(32), knowledgeBaseId: 0 }))
  assert.equal(captureUploadContext({ file, fileMd5: 'a'.repeat(32) }).knowledgeBaseId, 1)
})

test('switch cancellation aborts the old reply and prevents late frames entering another library', async () => {
  const scope = createReplyScope()
  const old = scope.begin(2)
  const oldChunks = []
  let deliver
  let close
  const pending = streamScopedReply({
    token: old, message: '旧库问题', onChunk: chunk => oldChunks.push(chunk),
    fetchEventSource: async (_url, options) => {
      await options.onopen(new Response('', { headers: { 'content-type': 'text/event-stream;charset=UTF-8' } }))
      assert.equal(JSON.parse(options.body).knowledgeBaseId, 2)
      deliver = data => options.onmessage({ event: '', data })
      await new Promise(resolve => { close = resolve })
    },
  })
  await Promise.resolve()
  deliver('第一段')
  scope.cancel()
  const next = scope.begin(3)
  deliver('不应出现的晚到回答')
  close()
  await pending
  assert.deepEqual(oldChunks, ['第一段'])
  assert.equal(old.controller.signal.aborted, true)
  assert.equal(old.isCurrent(), false)
  assert.equal(next.isCurrent(), true)
  assert.equal(scope.finish(old), false)
  assert.equal(next.isCurrent(), true)
  assert.equal(scope.finish(next), true)
  assert.equal(next.isCurrent(), false)
})

test('a new reply cancels the previous reply even in the same library', () => {
  const scope = createReplyScope()
  const first = scope.begin(1)
  const second = scope.begin(1)
  assert.equal(first.controller.signal.aborted, true)
  assert.equal(first.isCurrent(), false)
  assert.equal(second.isCurrent(), true)
})

test('stream awaits completion, accepts Flux text and JSON chunks, and skips completion marker', async () => {
  const scope = createReplyScope()
  const token = scope.begin(7)
  const chunks = []
  await streamScopedReply({ token, message: '问题', chatId: 'chat-1', onChunk: chunk => chunks.push(chunk), fetchEventSource: async (url, options) => {
    assert.equal(url, '/api/customer-service/chat/completion')
    assert.deepEqual(JSON.parse(options.body), { message: '问题', chatId: 'chat-1', knowledgeBaseId: 7 })
    await options.onopen(new Response('', { headers: { 'content-type': 'text/event-stream' } }))
    options.onmessage({ event: '', data: '文本' })
    options.onmessage({ event: 'message', data: '{"v":"分片"}' })
    options.onmessage({ event: '', data: '{"policy":"原样 JSON 资料"}' })
    options.onmessage({ event: '', data: '[DONE]' })
    await Promise.resolve()
    options.onmessage({ event: '', data: '完成' })
  } })
  assert.deepEqual(chunks, ['文本', '分片', '{"policy":"原样 JSON 资料"}', '完成'])
})

test('non-SSE and unsuccessful HTTP replies reject before accepting response text', async () => {
  for (const response of [new Response('bad', { status: 500 }), new Response('{}', { headers: { 'content-type': 'application/json' } })]) {
    const token = createReplyScope().begin(1)
    await assert.rejects(streamScopedReply({ token, message: 'x', onChunk: () => assert.fail('must not accept error body'), fetchEventSource: async (_url, options) => options.onopen(response) }))
  }
})

test('stream errors escape the transport callback so fetch-event-source does not retry forever', async () => {
  const token = createReplyScope().begin(1)
  const failure = new Error('connection lost')
  await assert.rejects(streamScopedReply({ token, message: 'x', onChunk: () => {}, fetchEventSource: async (_url, options) => options.onerror(failure) }), error => error === failure)
})

test('an existing file is searchable only after parsing and all indexes have completed', () => {
  assert.equal(uploadAvailability({ status: 4, indexStatus: 'FAILED' }).kind, 'failed')
  assert.equal(uploadAvailability({ status: 2, indexStatus: 'NOT_READY' }).kind, 'processing')
  assert.equal(uploadAvailability({ status: 3, indexStatus: 'PENDING' }).kind, 'indexing')
  assert.equal(uploadAvailability({ status: 3, indexStatus: 'FAILED' }).kind, 'indexFailed')
  assert.equal(uploadAvailability({ status: 3, indexStatus: 'SYNCED' }).kind, 'ready')
  assert.equal(uploadAvailability({}).kind, 'processing')
})
