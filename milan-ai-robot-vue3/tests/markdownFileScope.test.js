import test from 'node:test'
import assert from 'node:assert/strict'
import { reactive } from 'vue'
import { loadVueSetup } from './helpers/vueSetup.js'

function setup(id) {
  const requests = []
  const props = reactive({ open: false, knowledgeBase: { id, name: '员工服务' }, canEdit: true })
  const { state } = loadVueSetup(new URL('../src/components/customer-service/MarkdownFileDrawer.vue', import.meta.url), props, {
    '@ant-design/icons-vue': {},
    'ant-design-vue': { message: { success() {}, warning() {} }, Modal: {} },
    './StatusTag.vue': {},
    './IndexStatusTag.vue': {},
    '@/api/knowledgeBase': { reindexKnowledgeBase(owner) { requests.push(['reindex', owner]); return Promise.resolve() } },
    '@/api/documents': {},
    '@/api/feedback': {},
    '@/api/customerService': {
      findMarkdownFilePageList(...args) {
        requests.push(['list', ...args])
        return Promise.resolve({ data: { data: [], current: 1, total: 0 } })
      },
    },
  })
  return { state, requests }
}

test('an unselected library cannot query, reindex, upload, edit or delete files', async () => {
  const { state, requests } = setup(null)
  await state.renderTableData(1, 10)
  await state.handleReindex()
  state.fileInput.value = { click() { requests.push(['choose file']) } }
  state.triggerFileUpload()
  state.showDeleteMarkdownModel(6)
  state.showEditMarkdownModel({ id: 6, fileName: 'policy.md' })
  assert.deepEqual(requests, [])
  assert.equal(state.deleteMarkdownModelOpen.value, false)
  assert.equal(state.editMarkdownModelOpen.value, false)
})

test('editing uses the current library and rejects a record from another library', () => {
  const { state } = setup(7)
  assert.equal(state.editMarkdownRecord.knowledgeBaseId, null)
  state.showEditMarkdownModel({ id: 6, fileName: 'policy.md' })
  assert.equal(state.editMarkdownRecord.knowledgeBaseId, 7)
  assert.equal(state.editMarkdownModelOpen.value, true)
  state.editMarkdownModelOpen.value = false
  state.showEditMarkdownModel({ id: 8, knowledgeBaseId: 2, fileName: 'another.md' })
  assert.equal(state.editMarkdownModelOpen.value, false)
  assert.equal(state.editMarkdownRecord.id, 6)
})
