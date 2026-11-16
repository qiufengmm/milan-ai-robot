<template>
  <Layout :show-footer-text="false">
    <template #main-content>
      <div ref="chatContainer" class="relative flex h-screen flex-col overflow-y-auto bg-white">
        <header class="sticky top-0 z-10 flex items-center justify-between border-b border-slate-100 bg-white/95 px-5 py-3 backdrop-blur">
          <div>
            <h1 class="m-0 text-lg font-semibold text-slate-800">员工服务与 IT 支持</h1>
            <p class="m-0 text-xs text-slate-500">回答只依据当前知识库已发布资料</p>
          </div>
          <div class="flex items-center gap-3">
            <KnowledgeBaseSelector v-model="selectedKnowledgeBase" :disabled="uploadBusy || isReplying"
              :can-manage="false" />
            <a-button v-if="canMaintain" :disabled="isReplying" @click="settingsDrawerOpen = true">资料管理</a-button>
          </div>
        </header>

        <main class="mx-auto w-full max-w-4xl flex-1 px-5 pb-8 pt-8">
          <a-alert v-if="loadingConversation" message="正在加载本人会话" type="info" show-icon class="mb-5" />
          <div v-if="!chatId && chatList.length === 1" class="mb-8 rounded-2xl bg-slate-50 p-6">
            <h2 class="mb-2 text-xl font-semibold text-slate-800">你好，{{ auth.user?.displayName }}</h2>
            <p class="mb-0 text-slate-600">可以咨询员工制度、报销流程、账号登录、VPN、邮箱和办公设备问题。切换知识库会开始一段新会话。</p>
          </div>

          <template v-for="(item, index) in chatList" :key="item.id || `${item.requestId || 'message'}-${index}`">
            <div v-if="item.role === 'user'" class="mb-5 flex justify-end">
              <div class="max-w-[85%] whitespace-pre-wrap break-words rounded-2xl bg-blue-50 px-5 py-3 text-slate-800">{{ item.content }}</div>
            </div>
            <div v-else-if="!item.welcome" class="mb-7 flex gap-3">
              <div class="flex h-9 w-9 shrink-0 items-center justify-center rounded-full border border-slate-200">
                <SvgIcon name="customer-service-logo" customCss="h-5 w-5" />
              </div>
              <div class="min-w-0 max-w-[90%] flex-1 pt-1">
                <LoadingDots v-if="item.loading && !item.content" />
                <StreamMarkdownRender v-if="item.content" :content="item.content" />
                <p v-if="item.status === 'FAILED'" class="mt-2 text-sm text-rose-700">回答生成失败{{ item.errorCode ? `（${item.errorCode}）` : '' }}，可以重新提问。</p>
                <p v-else-if="item.status === 'CANCELLED'" class="mt-2 text-sm text-amber-700">回答已停止，已生成内容可回看。</p>
                <p v-else-if="item.status === 'GENERATING' && !item.loading" class="mt-2 text-sm text-amber-700">回答在服务中断前未完成，请重新提问。</p>

                <div v-if="item.status === 'COMPLETED' && item.id" class="mt-3">
                  <a-button size="small" type="link" @click="toggleSources(item)">
                    {{ item.sourcesVisible ? '收起参考资料' : '查看参考资料' }}
                  </a-button>
                  <div v-if="item.sourcesVisible" class="mt-2 space-y-2">
                    <div v-for="(source, sourceIndex) in item.sources" :key="`${source.chunkId}-${sourceIndex}`"
                      class="rounded-xl border border-slate-200 bg-slate-50 p-3 text-sm">
                      <div class="mb-1 flex flex-wrap items-center gap-2 font-medium text-slate-700">
                        <span>{{ source.title || source.fileName || '知识库资料' }}</span>
                        <a-tag>版本 {{ source.versionNo || '-' }}</a-tag>
                        <a-tag v-if="source.historical" color="orange">历史版本</a-tag>
                      </div>
                      <p class="mb-0 whitespace-pre-wrap break-words text-slate-600">{{ source.excerpt }}</p>
                    </div>
                    <p v-if="item.sourcesLoaded && !item.sources.length" class="text-sm text-slate-500">本次回答没有使用到可展示的参考片段。</p>
                  </div>
                  <div class="mt-2">
                    <a-button size="small" type="link" @click="toggleFeedback(item)">
                      {{ item.feedbackStatus === 'RESOLVED' ? '查看反馈处理结果' : item.feedbackStatus === 'OPEN' ? '修改反馈' : '反馈问题' }}
                    </a-button>
                    <div v-if="item.feedbackOpen && item.feedbackStatus !== 'RESOLVED'" class="mt-2 max-w-2xl rounded-xl border border-slate-200 p-3">
                      <a-textarea v-model:value="item.feedbackText" :maxlength="500" :rows="3" show-count
                        placeholder="请说明回答或资料哪里不准确，维护者只会看到这条反馈及对应回答和来源。" />
                      <a-button class="mt-2" type="primary" size="small" :loading="item.feedbackSaving" @click="submitFeedback(item)">提交反馈</a-button>
                    </div>
                    <p v-if="item.feedbackStatus === 'OPEN'" class="mt-1 text-xs text-amber-700">反馈已提交，等待知识库维护者处理。</p>
                    <div v-if="item.feedbackStatus === 'RESOLVED'" class="mt-2 rounded-xl bg-emerald-50 p-3 text-sm">
                      <p class="mb-1 font-medium text-emerald-800">维护者已处理</p>
                      <p class="mb-0 whitespace-pre-wrap text-emerald-900">{{ item.resolutionNote }}</p>
                    </div>
                  </div>
                </div>
              </div>
            </div>
          </template>
        </main>

        <div class="sticky bottom-0 mx-auto w-full max-w-4xl bg-white pb-5 pt-2">
          <div class="mx-4 flex justify-end" v-if="isReplying">
            <a-button size="small" @click="stopReply">停止回答</a-button>
          </div>
          <ChatInputBox v-model="chatMessage" container-class="w-full"
            :disabled="isReplying || loadingConversation || !selectedKnowledgeBase.id"
            :placeholder="`向${selectedKnowledgeBase.name || '当前知识库'}提问……`" @send-message="sendMessage" />
        </div>

        <MarkdownFileDrawer v-model:open="settingsDrawerOpen" :knowledge-base="selectedKnowledgeBase"
          :can-edit="canMaintain" @upload-busy="uploadBusy = $event" @published="loadDocuments" />
      </div>
    </template>
  </Layout>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import { fetchEventSource } from '@microsoft/fetch-event-source'
import Layout from '@/layouts/Layout.vue'
import SvgIcon from '@/components/SvgIcon.vue'
import StreamMarkdownRender from '@/components/StreamMarkdownRender.vue'
import LoadingDots from '@/components/LoadingDots.vue'
import ChatInputBox from '@/components/ChatInputBox.vue'
import MarkdownFileDrawer from '@/components/customer-service/MarkdownFileDrawer.vue'
import KnowledgeBaseSelector from '@/components/customer-service/KnowledgeBaseSelector.vue'
import { createReplyScope, serverErrorDisplay, streamScopedReply } from '@/components/customer-service/knowledgeScope.js'
import { newChat, getChat, findChatMessagePageList, getAnswerSources } from '@/api/chat'
import { listKnowledgeBases } from '@/api/knowledgeBase'
import { useAuthStore } from '@/stores/authStore'
import { listMyFeedback, submitAnswerFeedback } from '@/api/feedback'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const welcomeItem = () => ({ role: 'assistant', content: '', welcome: true })
const chatContainer = ref(null)
const chatMessage = ref('')
const selectedKnowledgeBase = ref({ id: null, name: '', description: '', accessLevel: null })
const uploadBusy = ref(false)
const isReplying = ref(false)
const loadingConversation = ref(false)
const settingsDrawerOpen = ref(false)
const chatId = ref(null)
const chatList = ref([welcomeItem()])
const replyScope = createReplyScope()
let loadGeneration = 0

const canMaintain = computed(() => auth.user?.role === 'ADMIN' || selectedKnowledgeBase.value.accessLevel === 'EDITOR')

const loadDocuments = () => window.dispatchEvent(new Event('enterprise:documents-refresh'))

const loadConversation = async (uuid) => {
  const generation = ++loadGeneration
  loadingConversation.value = true
  chatList.value = []
  try {
    const [detailResponse, basesResponse] = await Promise.all([getChat(uuid), listKnowledgeBases()])
    if (generation !== loadGeneration) return
    const detail = detailResponse.data.data
    const bases = (basesResponse.data.data || []).map(base => ({ ...base, id: Number(base.id) }))
    const base = bases.find(item => item.id === Number(detail.knowledgeBaseId))
    if (!base) throw new Error('当前账号已无权查看该会话所属知识库')
    selectedKnowledgeBase.value = base
    chatId.value = uuid

    const firstPage = await findChatMessagePageList(1, 50, uuid)
    if (generation !== loadGeneration) return
    const result = firstPage.data
    const pages = Math.max(1, Number(result.pages || 1))
    const pageData = [result.data || []]
    for (let current = 2; current <= pages; current++) {
      const response = await findChatMessagePageList(current, 50, uuid)
      if (generation !== loadGeneration) return
      pageData.push(response.data.data || [])
    }
    chatList.value = pageData.reverse().flat()
    try {
      const feedbackResponse = await listMyFeedback()
      const byAnswerId = new Map((feedbackResponse.data.data || []).map(item => [Number(item.answerId), item]))
      chatList.value.forEach(item => {
        if (item.role !== 'assistant' || !item.id) return
        const feedbackItem = byAnswerId.get(Number(item.id))
        if (feedbackItem) Object.assign(item, { feedbackId: feedbackItem.id, feedbackStatus: feedbackItem.status,
          feedbackText: feedbackItem.description, resolutionNote: feedbackItem.resolutionNote })
      })
    } catch { /* Chat history remains available if feedback lookup fails. */ }
    await scrollToBottom()
  } catch (error) {
    if (generation === loadGeneration) {
      chatId.value = null
      chatList.value = []
      message.error(error.message || '加载会话失败')
      router.replace({ name: 'CustomerServiceChatPage' })
    }
  } finally {
    if (generation === loadGeneration) loadingConversation.value = false
  }
}

watch(() => route.params.chatId, (uuid) => {
  if (uuid === chatId.value) return
  replyScope.cancel()
  isReplying.value = false
  chatMessage.value = ''
  if (uuid) loadConversation(String(uuid))
  else {
    loadGeneration++
    loadingConversation.value = false
    chatId.value = null
    chatList.value = [welcomeItem()]
  }
}, { immediate: true })

watch(() => selectedKnowledgeBase.value.id, (current, previous) => {
  if (!previous || current === previous || loadingConversation.value) return
  replyScope.cancel()
  isReplying.value = false
  chatId.value = null
  chatList.value = [welcomeItem()]
  if (route.params.chatId) router.replace({ name: 'CustomerServiceChatPage' })
})

const stopReply = () => {
  replyScope.cancel()
  isReplying.value = false
  const reply = [...chatList.value].reverse().find(item => item.role === 'assistant' && item.loading)
  if (reply) {
    reply.loading = false
    reply.status = 'CANCELLED'
    if (!reply.content) reply.content = '回答已停止。'
  }
}

onBeforeUnmount(() => replyScope.cancel())

const sendMessage = async () => {
  const text = chatMessage.value.trim()
  if (!text || isReplying.value || loadingConversation.value) return
  if (!selectedKnowledgeBase.value.id) {
    message.warning('当前账号没有可访问的知识库')
    return
  }
  chatMessage.value = ''
  const requestId = crypto.randomUUID()
  const userItem = { role: 'user', content: text, requestId }
  const reply = { role: 'assistant', content: '', status: 'GENERATING', loading: true, requestId, sources: [] }
  chatList.value.push(userItem, reply)
  const token = replyScope.begin(selectedKnowledgeBase.value.id)
  isReplying.value = true
  await scrollToBottom()

  try {
    if (!chatId.value) {
      const created = await newChat(text, selectedKnowledgeBase.value.id)
      chatId.value = created.data.data.uuid
      router.replace({ name: 'CustomerServiceChatPage', params: { chatId: chatId.value } })
      window.dispatchEvent(new Event('enterprise:history-refresh'))
    }
    await streamScopedReply({
      fetchEventSource,
      token,
      message: text,
      chatId: chatId.value,
      requestId,
      onMeta(meta) { reply.id = meta.answerId },
      onSources(sources) { reply.sources = sources },
      onChunk(chunk) { reply.loading = false; reply.content += chunk; scrollToBottom() },
      onServerError(error) {
        const display = serverErrorDisplay(error)
        reply.serverError = display.message
        reply.errorCode = display.code
      },
    })
    reply.status = 'COMPLETED'
  } catch (error) {
    if (token.isCurrent()) {
      reply.status = reply.serverError ? 'FAILED' : 'FAILED'
      if (!reply.content) reply.content = reply.serverError || '回答未完整结束，请刷新会话查看保存状态。'
      message.warning(`${reply.serverError || '回答未完整结束，请稍后重试'}${reply.errorCode ? `（${reply.errorCode}）` : ''}`)
    }
  } finally {
    reply.loading = false
    if (replyScope.finish(token)) isReplying.value = false
    await scrollToBottom()
  }
}

const toggleSources = async (item) => {
  item.sourcesVisible = !item.sourcesVisible
  if (!item.sourcesVisible || item.sourcesLoaded || !item.id) return
  try {
    const response = await getAnswerSources(item.id)
    item.sources = response.data.data || []
    item.sourcesLoaded = true
  } catch {
    item.sourcesVisible = false
  }
}

const toggleFeedback = async (item) => {
  if (item.feedbackStatus === 'RESOLVED') {
    item.feedbackOpen = !item.feedbackOpen
    return
  }
  item.feedbackOpen = !item.feedbackOpen
}

const submitFeedback = async (item) => {
  const description = String(item.feedbackText || '').trim()
  if (!description || description.length > 500) {
    message.warning('反馈说明须为1至500个字符')
    return
  }
  item.feedbackSaving = true
  try {
    const response = await submitAnswerFeedback(item.id, description)
    const saved = response.data.data
    item.feedbackId = saved.id
    item.feedbackStatus = saved.status
    item.feedbackText = saved.description
    item.resolutionNote = saved.resolutionNote
    item.feedbackOpen = false
    message.success('反馈已提交给知识库维护者')
  } finally { item.feedbackSaving = false }
}

const scrollToBottom = async () => {
  await nextTick()
  if (chatContainer.value) chatContainer.value.scrollTop = chatContainer.value.scrollHeight
}
</script>
