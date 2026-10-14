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
        <div class="flex-1 max-w-3xl mx-auto pb-24 pt-4 px-4 w-full">
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
                <StreamMarkdownRender :content="chat.content" />
              </div>
            </div>
          </template>
        </div>

        <!-- 提问输入框 -->
        <ChatInputBox v-model="chatMessage" containerClass="sticky max-w-3xl mx-auto bg-white bottom-8 left-0 w-full"
          @sendMessage="sendMessage" placeholder="向米兰AI 智能客服询问" :showModelDropdown="false" :showNetworkSearch="false"/>

        <!-- 抽屉：客服问答文件管理（搜索、上传、表格、编辑/删除模态框都在组件内部） -->
        <MarkdownFileDrawer v-model:open="settingsDrawerOpen" />
      </div>
</template>

<script setup>
import { ref, nextTick } from 'vue'
import SvgIcon from '@/components/SvgIcon.vue'
import StreamMarkdownRender from '@/components/StreamMarkdownRender.vue'
import LoadingDots from '@/components/LoadingDots.vue'
import ChatInputBox from '@/components/ChatInputBox.vue'
import MarkdownFileDrawer from '@/components/customer-service/MarkdownFileDrawer.vue'
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

// 聊天记录，默认给一个欢迎语
const chatList = ref([{ role: 'assistant', content: '你好呀！我是米兰AI 智能客服，基于 Spring AI 开发，欢迎向我咨询相关问题哈 😁', loading: false }])


// 对话 ID
const chatId = ref(null)


// 发送消息
const sendMessage = async () => {
  // 校验发送的消息不能为空
  if (!chatMessage.value.trim()) return

  // 将用户发送的消息添加到 chatList 聊天列表中
  const userMessage = chatMessage.value.trim()
  chatList.value.push({ role: 'user', content: userMessage })

  // 点击发送按钮后，清空输入框
  chatMessage.value = ''

  // 添加一个占位的回复消息
  chatList.value.push({ role: 'assistant', content: '', loading: true})

  try {
    // 构建请求体
    const requestBody = {
      message: userMessage,
      chatId: chatId.value,
    }

    // 响应的回答
    let responseText = ''
    // 获取最后一条消息
    const lastMessage = chatList.value[chatList.value.length - 1]

    const controller = new AbortController()
    const signal = controller.signal

    fetchEventSource('http://localhost:8080/customer-service/chat/completion', {
      method: 'POST',
      openWhenHidden: true, // 页面切到后台时不要中断 SSE 连接
      signal: signal,
      headers: {
        'Content-Type': 'application/json',
      },
      body: JSON.stringify(requestBody),
      onmessage(msg) {
        if (msg.event === '') {
          // 收到第一条数据后设置 loading 为 false
          if (lastMessage.loading) {
              lastMessage.loading = false;
          }

          // 后端 /customer-service/chat/completion 返回的是 Flux<String>，
          // SSE 里的 data 就是纯文本分片（例如 `data:你好`），不是 JSON。
          // 这里做成兼容写法：能解析成 JSON 就取 v 字段，否则按纯文本处理，
          // 这样即使后续后端改成返回 JSON 结构也不用再改前端。
          let chunk = msg.data
          try {
            const parsed = JSON.parse(msg.data)
            if (parsed && typeof parsed === 'object') {
              chunk = parsed.v ?? ''
            }
          } catch {
            // 不是 JSON，说明后端返回的就是纯文本，直接用原文
          }

          // 持续追加流式回答
          responseText += chunk

          // 更新最后一条消息
          chatList.value[chatList.value.length - 1].content = responseText
          // 滚动到底部
          scrollToBottom()
        }
        else if (msg.event === 'close') {
          if (import.meta.env.DEV) {
            console.log('-- sse close')
          }
          controller.abort();
        }
      },
      onerror(err) {
        throw err;    // 必须 throw 才能停止 
      }
    })
  } catch (error) {
    console.error('发送消息错误: ', error)
    // 提示用户 “请求出错”
    const lastMessage = chatList.value[chatList.value.length - 1]
    lastMessage.content = '抱歉，请求出错了，请稍后重试。'
    lastMessage.loading = false
    // 滚动到底部
    scrollToBottom()
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
