import test from 'node:test'
import assert from 'node:assert/strict'
import { loadVueSetup } from './helpers/vueSetup.js'

test('renaming history sends the selected conversation UUID', async () => {
  let renamed
  const { state } = loadVueSetup(new URL('../src/components/Sidebar.vue', import.meta.url), { sidebarOpen: true }, {
    '@/components/SvgIcon.vue': {},
    '@ant-design/icons-vue': {},
    'vue-router': { useRouter: () => ({}), useRoute: () => ({ params: {} }) },
    '@/stores/authStore': { useAuthStore: () => ({}) },
    '@/api/chat': { renameChat(uuid, summary) { renamed = { uuid, summary }; return Promise.resolve({ data: { success: true } }) } },
    'ant-design-vue': { message: { success() {} } },
  })
  state.formRef.value = { validate: () => Promise.resolve() }
  state.formState.uuid = '4a2ac34f-430d-446e-946b-70798e51a823'
  state.formState.summary = 'VPN 登录问题'
  state.handleRenameChatModelOk()
  await new Promise(resolve => setImmediate(resolve))
  assert.deepEqual(renamed, { uuid: '4a2ac34f-430d-446e-946b-70798e51a823', summary: 'VPN 登录问题' })
})
