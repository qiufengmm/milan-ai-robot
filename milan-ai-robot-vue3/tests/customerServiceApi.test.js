import test from 'node:test'
import assert from 'node:assert/strict'
import { loadJsModule } from './helpers/vueSetup.js'

const requests = []
const api = loadJsModule(new URL('../src/api/customerService.js', import.meta.url), {
  '@/axios': { post(...args) { requests.push(args); return Promise.resolve() } },
})

test('file APIs reject an absent knowledge base before sending any request', () => {
  const missingOwnerCalls = [
    () => api.findMarkdownFilePageList(1, 10, ''),
    () => api.checkFile('hash'),
    () => api.mergeFileChunk('hash'),
    () => api.deleteMarkdownFile(6),
    () => api.updateMarkdownFile({ id: 6 }),
    () => api.uploadFileChunk(new FormData()),
  ]
  for (const call of missingOwnerCalls) assert.throws(call, /知识库/)
  assert.deepEqual(requests, [])
})

test('file APIs keep the caller supplied knowledge base and parameter order', async () => {
  requests.length = 0
  await api.mergeFileChunk('hash', 120000, 7)
  await api.deleteMarkdownFile(6, true, { silent: true }, 7)
  await api.updateMarkdownFile({ id: 6, knowledgeBaseId: 2 }, 7)
  assert.deepEqual(requests, [
    ['/customer-service/file/merge-chunk', { fileMd5: 'hash', knowledgeBaseId: 7 }, { timeout: 120000 }],
    ['/customer-service/file/delete', { id: 6, force: true, knowledgeBaseId: 7 }, { silent: true }],
    ['/customer-service/file/update', { id: 6, knowledgeBaseId: 7 }],
  ])
})
