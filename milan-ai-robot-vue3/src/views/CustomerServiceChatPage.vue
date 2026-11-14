<template>
    <!-- 主内容区域 -->
    <div class="h-screen flex flex-col overflow-y-auto" ref="chatContainer">
        <a-tooltip placement="right">
          <!-- Tooltip 提示文字 -->
          <template #title>
            <span>返回首页</span>
          </template>
          <!-- 返回首页按钮 -->
          <div class="fixed top-4 left-4 z-10">
            <button class="w-10 h-10 rounded-full bg-white border border-gray-200 flex items-center justify-center cursor-pointer
            shadow-xs hover:bg-gray-100 transition-all duration-200" @click="jumpHomePage">
              <SvgIcon name="back" customCss="w-5 h-5 text-gray-500"></SvgIcon>
            </button>
          </div>
        </a-tooltip>

        <!-- 右上角浮动设置按钮 -->
        <div class="fixed top-4 right-4 z-10">
          <a-tooltip placement="left">
            <!-- Tooltip 提示文字 -->
            <template #title>
              <span>设置</span>
            </template>
            <!-- 设置按钮 -->
            <button class="w-10 h-10 rounded-full bg-white border border-gray-200 flex items-center justify-center cursor-pointer
            shadow-xs hover:bg-gray-100 hover:shadow-xl transition-all duration-200" @click="showSettingsDrawer">
                <SvgIcon name="settings" customCss="w-5 h-5 text-gray-500"></SvgIcon>
            </button>
          </a-tooltip>
        </div>

        <!-- 聊天记录区域 -->
        <div class="flex-1 max-w-3xl mx-auto pb-24 pt-16 px-4 w-full">
          <div class="mb-6 rounded-xl border border-gray-200 bg-white p-4">
            <KnowledgeBaseSelector v-model="selectedKnowledgeBase" :disabled="uploadBusy" />
            <p class="text-xs text-gray-400 mt-2 mb-0">聊天和文件管理均使用当前知识库；切换后开始新的对话。</p>
          </div>
          <!-- 遍历聊天记录 -->
          <template v-for="(chat, index) in chatList" :key="index">
            <!-- 用户提问消息（靠右） -->
            <div v-if="chat.role === 'user'" class="flex justify-end mb-4">
              <div class="quesiton-container">
                <p>{{ chat.content }}</p>
              </div>
            </div>

            <!-- 大模型回复消息（靠左） -->
            <div v-else class="flex mb-4">
              <!-- 头像 -->
              <div class="flex-shrink-0 mr-3">
                <div class="w-8 h-8 rounded-full flex items-center justify-center border border-gray-200">
                  <SvgIcon name="customer-service-logo" customCss="w-5 h-5"></SvgIcon>
                </div>
              </div>
              <!-- 回复的内容 -->
              <div class="p-1 mb-2 max-w-[90%]">
                <LoadingDots v-if="chat.loading" />
                <StreamMarkdownRender :content="chat.welcome ? welcomeMessage : chat.content" />
              </div>
            </div>
          </template>
          <a-button v-if="isReplying" size="small" @click="stopReply">停止回答</a-button>
        </div>

        <!-- 提问输入框 -->
        <ChatInputBox v-model="chatMessage" containerClass="sticky max-w-3xl mx-auto bg-white bottom-8 left-0 w-full"
          @sendMessage="sendMessage" :placeholder="`向${selectedKnowledgeBase.name}提问……`" :showModelDropdown="false" :showNetworkSearch="false"/>

        <!-- 抽屉：客服问答文件管理（搜索、上传、表格、编辑/删除模态框都在组件内部） -->
        <MarkdownFileDrawer v-model:open="settingsDrawerOpen" :knowledge-base="selectedKnowledgeBase" @upload-busy="uploadBusy = $event" />
      </div>
</template>

<script setup>
import { computed, ref, nextTick, watch, onBeforeUnmount } from 'vue'
import { message } from 'ant-design-vue'
import SvgIcon from '@/components/SvgIcon.vue'
import StreamMarkdownRender from '@/components/StreamMarkdownRender.vue'
import LoadingDots from '@/components/LoadingDots.vue'
import ChatInputBox from '@/components/ChatInputBox.vue'
import MarkdownFileDrawer from '@/components/customer-service/MarkdownFileDrawer.vue'
import KnowledgeBaseSelector from '@/components/customer-service/KnowledgeBaseSelector.vue'
import { createReplyScope, streamScopedReply } from '@/components/customer-service/knowledgeScope.js'
import { useRouter } from 'vue-router'
import { fetchEventSource } from '@microsoft/fetch-event-source'

const router = useRouter()

// 返回首页
const jumpHomePage = () => {
  router.push({ name: 'Index' })
}

// 设置抽屉是否展示
const settingsDrawerOpen = ref(false)

// 打开设置抽屉（打开后由 MarkdownFileDrawer 内部自行加载表格数据）
const showSettingsDrawer = () => {
  settingsDrawerOpen.value = true
}

// 输入的消息（重命名为 chatMessage，避免与 ant-design-vue 的 message 消息组件命名冲突）
const chatMessage = ref(history.state?.firstMessage || '')

// 聊天容器引用
const chatContainer = ref(null)

const selectedKnowledgeBase = ref({ id: 1, name: '米兰数码商城', description: '' })
const uploadBusy = ref(false)
const isReplying = ref(false)
const replyScope = createReplyScope()
const welcomeMessage = computed(() => selectedKnowledgeBase.value.id === 1
  ? `你好，我是${selectedKnowledgeBase.value.name}智能客服，可以帮你了解商品参数、配送规则、退换货和保修政策。请告诉我商品名称和想咨询的问题。`
  : `你好，我是${selectedKnowledgeBase.value.name}智能助手。请告诉我你的问题，我会根据当前知识库的资料为你解答。`)
const newConversation = () => [{ role: 'assistant', content: '', welcome: true, loading: false }]
const chatList = ref(newConversation())
const chatId = ref(null)

watch(() => selectedKnowledgeBase.value.id, () => {
  replyScope.cancel()
  isReplying.value = false
  chatList.value = newConversation()
  chatId.value = null
  chatMessage.value = ''
}, { flush: 'sync' })

const stopReply = () => {
  replyScope.cancel()
  isReplying.value = false
  const reply = chatList.value[chatList.value.length - 1]
  if (reply?.loading) reply.loading = false
  if (reply?.role === 'assistant' && !reply.welcome && !reply.content) reply.content = '回答已停止。'
}

onBeforeUnmount(() => replyScope.cancel())

// 发送消息
const sendMessage = async () => {
  // 校验发送的消息不能为空
  if (!chatMessage.value.trim()) return
  if (isReplying.value) {
    message.info('请等待当前回答完成，或先停止回答')
    return
  }

  // 将用户发送的消息添加到 chatList 聊天列表中
  const userMessage = chatMessage.value.trim()
  chatList.value.push({ role: 'user', content: userMessage })

  // 点击发送按钮后，清空输入框
  chatMessage.value = ''

  // 添加一个占位的回复消息
  const reply = { role: 'assistant', content: '', loading: true }
  chatList.value.push(reply)
  // Use the reactive object actually stored by Vue, rather than the raw object.
  const targetReply = chatList.value[chatList.value.length - 1]
  const token = replyScope.begin(selectedKnowledgeBase.value.id)
  isReplying.value = true
  scrollToBottom()

  try {
    await streamScopedReply({
      fetchEventSource,
      token,
      message: userMessage,
      chatId: chatId.value,
      onChunk(chunk) {
        targetReply.loading = false
        targetReply.content += chunk
        scrollToBottom()
      },
    })
  } catch (error) {
    if (token.isCurrent()) {
      targetReply.content += `${targetReply.content ? '\n\n' : ''}抱歉，回答中断了，请稍后重试。`
      console.error('发送消息错误: ', error)
    }
  } finally {
    targetReply.loading = false
    if (replyScope.finish(token)) {
      isReplying.value = false
      if (!targetReply.content) targetReply.content = '暂未收到回答，请稍后重试。'
      scrollToBottom()
    }
  }
}

// 滚动到底部
const scrollToBottom = async () => {
  await nextTick() // 等待 Vue.js 完成 DOM 更新
  if (chatContainer.value) { // 若容器存在
    // 将容器的滚动条位置设置到最底部
    const container = chatContainer.value;
    container.scrollTop = container.scrollHeight;
  }
}

if (import.meta.env.DEV) {
  console.log('首页传递过来的消息: ', history.state?.firstMessage)
}
</script>

<style scoped>
.quesiton-container {
  font-size: 16px;
  line-height: 28px;
  color: #262626;
  padding: calc((44px - 28px) / 2) 20px;
  box-sizing: border-box;
  white-space: pre-wrap;
  word-break: break-word;
  background-color: #eff6ff;
  border-radius: 14px;
  max-width: calc(100% - 48px);
  position: relative;
}

/* 聊天内容区域样式 */
.overflow-y-auto {
  scrollbar-color: rgba(0, 0, 0, 0.2) transparent;
  /* 自定义滚动条颜色 */
}
</style>
