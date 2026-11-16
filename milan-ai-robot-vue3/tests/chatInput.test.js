import test from 'node:test'
import assert from 'node:assert/strict'
import { reactive, nextTick } from 'vue'
import { loadVueSetup } from './helpers/vueSetup.js'

const setup = (text = '员工制度', disabled = false) => loadVueSetup(
  new URL('../src/components/ChatInputBox.vue', import.meta.url),
  reactive({ modelValue: text, disabled }),
  {
    '@/components/SvgIcon.vue': {},
    'ant-design-vue': { message: { warning() {} } },
  },
)

test('IME confirmation and Shift+Enter keep the draft without sending', () => {
  for (const event of [{ isComposing: true }, { keyCode: 229 }, { shiftKey: true }]) {
    const { state, events } = setup()
    state.handleEnter({ ...event, target: { value: '员工制度' }, preventDefault() { throw new Error('keep input') } })
    assert.deepEqual(events, [])
    assert.equal(state.userMessage.value, '员工制度')
  }
})

test('Enter sends without model options, clears the draft and resets its height', async () => {
  const { state, events } = setup()
  const textarea = { scrollHeight: 48, style: {} }
  state.textareaRef.value = textarea
  let prevented = false
  state.handleEnter({ target: { value: '员工制度' }, preventDefault() { prevented = true } })
  await nextTick()
  assert.equal(prevented, true)
  assert.deepEqual(events, [['sendMessage'], ['update:modelValue', '']])
  assert.equal(state.userMessage.value, '')
  assert.equal(textarea.style.height, '48px')
  assert.equal(textarea.style.overflowY, 'hidden')
})

test('disabled or empty input cannot emit a send event', () => {
  for (const [text, disabled] of [['员工制度', true], ['  ', false]]) {
    const { state, events } = setup(text, disabled)
    state.handleSendMessage()
    assert.deepEqual(events, [])
  }
})

test('input autosizing caps tall drafts and enables scrolling', () => {
  const { state } = setup()
  const textarea = { scrollHeight: 450, style: {} }
  state.textareaRef.value = textarea
  state.autoResize()
  assert.equal(textarea.style.height, '300px')
  assert.equal(textarea.style.overflowY, 'auto')
})
