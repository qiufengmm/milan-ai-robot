import test from 'node:test'
import assert from 'node:assert/strict'
import { restoreChatSelection } from '../src/stores/persistedChatSelection.js'
import { createApp } from 'vue'
import { createPinia } from 'pinia'
import { createPersistedState } from 'pinia-plugin-persistedstate'
import { useChatStore } from '../src/stores/chatStore.js'

test('obsolete persisted default is replaced by the available default model', () => {
  const state = restoreChatSelection({
    selectedModel: { id: 1, name: 'deepseek-v4.1-flash', selected: true },
    models: [{ id: 1, name: 'deepseek-v4.1-flash', selected: true }],
  })
  assert.equal(state.selectedModel.name, 'qwen3.8-flash')
  assert.equal(state.selectedModel.icon, 'qwen-logo')
  assert.deepEqual(state.models.filter(model => model.selected).map(model => model.name), ['qwen3.8-flash'])
  assert.equal(state.models.some(model => model.name === 'deepseek-v4.1-flash'), false)
})

for (const name of ['qwen3.8-27b', 'kimi-k3']) {
  test(`hydration preserves the valid ${name} alternative and repairs stale flags`, () => {
    const state = restoreChatSelection({ selectedModel: { name, icon: 'stale' }, models: [{ name: 'deepseek-v4.1-flash', selected: true }] })
    assert.equal(state.selectedModel.name, name)
    assert.equal(state.selectedModel.icon, name === 'kimi-k3' ? 'kimi-logo' : 'qwen-logo')
    assert.equal(state.models.filter(model => model.selected).length, 1)
    assert.equal(state.models.find(model => model.name === name), state.selectedModel)
  })
}

test('empty, unknown and malformed persisted choices use a fresh canonical model list', () => {
  for (const state of [undefined, null, {}, { selectedModel: { name: 'unavailable-model' } }, { selectedModel: 'kimi-k3' }]) {
    assert.equal(restoreChatSelection(state).selectedModel.name, 'qwen3.8-flash')
  }
  const first = restoreChatSelection()
  first.models[0].name = 'modified'
  assert.equal(restoreChatSelection().selectedModel.name, 'qwen3.8-flash')
})

test('Pinia hydration repairs menu flags and rewrites the obsolete persisted snapshot', () => {
  for (const [oldName, expectedName] of [['deepseek-v4.1-flash', 'qwen3.8-flash'], ['kimi-k3', 'kimi-k3']]) {
    let saved = JSON.stringify({ selectedModel: { name: oldName }, models: [{ name: 'deepseek-v4.1-flash', selected: true }], isNetworkSearchSelected: true })
    const pinia = createPinia()
    pinia.use(createPersistedState({ storage: { getItem: () => saved, setItem: (_key, value) => { saved = value } } }))
    createApp({}).use(pinia)
    const store = useChatStore(pinia)
    assert.equal(store.selectedModel.name, expectedName)
    assert.deepEqual(store.models.filter(model => model.selected).map(model => model.name), [expectedName])
    assert.equal(store.isNetworkSearchSelected, true)
    assert.equal(JSON.parse(saved).selectedModel.name, expectedName)
    assert.equal('models' in JSON.parse(saved), false)
    store.$dispose()
  }
})
