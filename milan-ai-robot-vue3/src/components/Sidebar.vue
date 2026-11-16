<template>
  <!-- 左边栏 -->
  <div :class="sidebarOpen ? 'translate-x-0' : '-translate-x-full'"
    class="w-64 bg-[#f9fbff] border-r border-gray-200 fixed left-0 top-0 h-full transition-transform duration-300 ease-in-out z-10 overflow-y-auto">
    <!-- 侧边栏内容区域 -->
    <div class="p-0 h-full flex flex-col">
      <!-- Logo 与应用名称 -->
      <div class="flex items-center justify-center p-4 cursor-pointer" @click="startNewChat">
        <SvgIcon name="ai-robot-logo" customCss="w-8 h-8 text-gray-700 mr-3" />
        <span class="text-2xl font-bold font-sans tracking-wide text-gray-800">企业知识库</span>
      </div>

      <!-- 开启新对话按钮 -->
      <button @click="startNewChat"
        class="mx-auto mb-[34px] my-2 px-6 py-2 text-white rounded-xl transition-colors new-chat-btn w-fit cursor-pointer">
        <SvgIcon name="new-chat" customCss="w-6 h-6 mr-1.5 inline text-[#4d6bfe]" />
        开启新对话
      </button>
      <!-- 分割线 -->
      <div class="h-1 border-b border-gray-200 mx-4"></div>

      <!-- 历史对话区域 -->
      <div class="my-4 px-2 overflow-y-auto overflow-x-hidden flex-1" ref="historyChatContainerRef">
        <div class="space-y-1">
          <!-- 使用 sticky 定位使标题在滚动时保持可见 -->
          <div class="text-xs px-3 py-1 text-gray-500 sticky top-0 bg-[#f9fbff] z-10">历史对话</div>

          <div v-for="(historyChat, index) in historyChats" :key="index" :class="['relative px-3 py-1 rounded-xl cursor-pointer transition-colors flex items-center justify-between',
    selectedChatId === historyChat.uuid ? 'bg-[#e4edfd] text-[#4d6bfe]' : 'hover:bg-[rgb(239,246,255)]']"
            @click="jumpToChatPage(historyChat.uuid)" @mouseenter="showButton = historyChat.uuid"
            @mouseleave="showButton = null">

            <a-tooltip placement="top">
              <!-- Tooltip 提示文字 -->
              <template #title>
                <span>{{ historyChat.summary }}</span>
              </template>

              <p class="text-[14px] overflow-hidden whitespace-nowrap">{{ historyChat.summary }}</p>
            </a-tooltip>

            <!-- 下拉菜单 -->
            <a-dropdown>
              <template #overlay>
                <a-menu @click="handleMenuClick(historyChat.uuid, historyChat.summary, $event)">
                  <a-menu-item key="rename">
                    <EditOutlined />
                    重命名
                  </a-menu-item>
                  <a-menu-item key="delete" danger>
                    <DeleteOutlined />
                    删除
                  </a-menu-item>
                </a-menu>
              </template>
              <!-- 右边菜单按钮 -->
              <button
                class="z-10 rounded-lg outline-none justify-center items-center bg-white
                            w-6 h-6 flex absolute right-2 top-1/2 transform -translate-y-1/2 transition-all duration-300 hover:bg-gray-50"
                :style="{ opacity: showButton === historyChat.uuid ? 1 : 0 }">
                <EllipsisOutlined class="w-4 h-4 text-gray-500" />
              </button>
            </a-dropdown>



          </div>
        </div>
      </div>
      <div class="border-t border-gray-200 p-3 text-sm">
        <div class="mb-2 truncate text-slate-600">{{ auth.user?.displayName }} · {{ auth.user?.role === 'ADMIN' ? '管理员' : '员工' }}</div>
        <a-button v-if="auth.user?.role === 'ADMIN'" block size="small" class="mb-2" @click="router.push({ name: 'AdminAccessPage' })">账号与权限</a-button>
        <a-button block size="small" @click="signOut">退出登录</a-button>
      </div>
    </div>
  </div>

  <!-- 侧边栏切换按钮 -->
  <a-tooltip placement="bottom">
    <!-- Tooltip 提示文字 -->
    <template #title>
      <span>{{ sidebarOpen ? '收缩边栏' : '打开边栏' }}</span>
    </template>

    <button :class="sidebarOpen ? 'left-64' : 'left-0'" @click="toggleSidebar"
      class="fixed top-4 z-20 bg-white border border-gray-200 rounded-r-lg p-2 transition-all duration-300">
      <!-- 图标 -->
      <SvgIcon :name="sidebarOpen ? 'sidebar-open' : 'sidebar-close'"
        :customCss="sidebarOpen ? 'w-6 h-6 text-gray-400' : 'w-7 h-7 text-gray-400'" />
    </button>
  </a-tooltip>
  

  <!-- 删除对话确认框 -->
  <a-modal v-model:open="deleteChatModelOpen" width="400px" :centered=true title="永久删除对话" ok-text="确认" ok-type="danger" cancel-text="取消" 
    @ok="handleDeleteChatModelOk()">
      <p>删除后，该对话将不可恢复。确认删除吗？</p>
  </a-modal>

  <!-- 重命名对话弹出框 -->
  <a-modal v-model:open="renameChatModelOpen" width="600px" :centered=true title="重命名对话" ok-text="确认" cancel-text="取消"
  @ok="handleRenameChatModelOk()">
              <a-form
                ref="formRef"
                :rules="rules"
                :model="formState"
                :label-col="{ span: 2 }"
                :wrapper-col="{ span: 22 }"
                autocomplete="off"
                >
                    <a-form-item
                      label="摘要"
                      name="summary"
                    >
                      <a-input v-model:value="formState.summary" />
                    </a-form-item>
                </a-form>
  </a-modal>

</template>

<script setup>
import { ref, reactive, onMounted, onBeforeUnmount, watch } from 'vue'
import SvgIcon from '@/components/SvgIcon.vue'
import { EditOutlined, EllipsisOutlined, DeleteOutlined } from '@ant-design/icons-vue'
import { useRouter, useRoute } from 'vue-router'
// 导入获取历史对话列表的API
import { findHistoryChatPageList, deleteChat, renameChat } from '@/api/chat'
import { message } from 'ant-design-vue'
import { useAuthStore } from '@/stores/authStore'

const router = useRouter()
const route = useRoute()
const auth = useAuthStore()

// 定义 props, 对外部暴露配置项
const props = defineProps({
  sidebarOpen: { type: Boolean, required: true }, // 左边栏是否展开
})

// 定义emits
const emit = defineEmits(['toggle-sidebar'])

// 历史对话
const historyChats = ref([])

// 切换侧边栏显示/隐藏
const toggleSidebar = () => {
  emit('toggle-sidebar')
}

// 当前显示右侧栏按钮的聊天 ID
const showButton = ref(null)

// 当前选中的聊天 ID
const selectedChatId = ref(null)

// 开始员工知识问答
const startNewChat = () => {
  router.push({ name: 'CustomerServiceChatPage' })
}

// 左边栏滚动区域引用
const historyChatContainerRef = ref(null)

onMounted(() => {
  // 查询历史对话
  fetchHistoryChats(true)
  window.addEventListener('enterprise:history-refresh', refreshHistoryChats)

  // 检查当前路由是否有 chatId 参数
  const currentChatId = route.params.chatId
  if (currentChatId) {
    // 设置当前选中的对话 ID
    selectedChatId.value = currentChatId
  }

  // 添加滚动监听事件
  if (historyChatContainerRef.value) {
    historyChatContainerRef.value.addEventListener('scroll', handleScroll)
  }
})

onBeforeUnmount(() => {
  // 移除滚动事件监听
  if (historyChatContainerRef.value) {
    historyChatContainerRef.value.removeEventListener('scroll', handleScroll);
  }
  window.removeEventListener('enterprise:history-refresh', refreshHistoryChats)
})

watch(() => route.params.chatId, value => { selectedChatId.value = value || null })
const refreshHistoryChats = () => fetchHistoryChats(true)

const handleScroll = () => {
  if (historyChatContainerRef.value) {
    // 获取滚动参数
    const { scrollTop, scrollHeight, clientHeight } = historyChatContainerRef.value
    // 距离底部距离
    const scrollPosition = scrollHeight - scrollTop - clientHeight

    // 打印滚动过程中的详细日志
    console.log('=== 滚动事件日志 ===')
    console.log('scrollPosition:', scrollPosition)

    // 当滚动到距离底部20px以内时，且有更多数据，且当前没有在加载中时，加载更多
    if (scrollPosition <= 20 && hasMore.value && !isLoadingMore.value) {
      loadMoreHistoryChats()
    }
  }
}

// 加载更多历史对话
const loadMoreHistoryChats = async () => {
  console.log('=== 开始加载更多历史对话 ===')
  console.log('当前页码:', current.value)

  // 双重检查：
  // 1. 如果当前页面已经是最后一页，则不再发送请求
  // 2. 如果已经有请求在进行中，则不再发送请求
  if (!hasMore.value) {
    console.log('=== 没有更多历史对话，不再请求 ===')
    return
  }
  
  if (isLoadingMore.value) {
    console.log('=== 已有加载请求正在进行中，不再发送新请求 ===')
    return
  }
  
  // 设置加载状态为 true，防止并发请求
  isLoadingMore.value = true

  // 计算下一页页码（向下滑动加载更早的历史对话，页码应该增加）
  const nextPageNo = current.value + 1
  console.log('=== 计算下一页页码 ===', nextPageNo)

  // 保存当前页码用于错误恢复
  const currentTemp = current.value
  // 当前需要请求的页码
  current.value = nextPageNo

  try {
    await fetchHistoryChats();
  } catch (error) {
    // 恢复页码
    current.value = currentTemp;
  }
}

// 分页相关状态
// 当前页码（默认第一页）
const current = ref(1)
// 每页展示数据量
const size = ref(20)
// 是否还有下一页数据（默认有）
const hasMore = ref(true)
// 是否正在加载中 (解决并发请求后续页数据问题)
const isLoadingMore = ref(false)

// 获取历史对话列表
const fetchHistoryChats = async (reset = false) => {
  if (reset) {
    current.value = 1
    historyChats.value = []
  }
  const page = reset ? 1 : current.value
  return findHistoryChatPageList(page, size.value).then(res => {
    // 无论成功失败，请求完成后都需要重置加载状态
    isLoadingMore.value = false

    if (res.data.success) {
      const data = res.data.data

      // 判断是否还有下一页(总页数 > 当前页)
      hasMore.value = res.data.pages > page

      if (data && data.length > 0) {
        // 将新数据添加到历史对话列表末尾
          historyChats.value = [...historyChats.value, ...data.filter(item => !historyChats.value.some(existing => existing.uuid === item.uuid))]
      }
    }
  }).catch((error) => {
      // 错误处理，重置加载状态
      console.error('加载历史对话失败:', error)
      isLoadingMore.value = false
  })
}

// 跳转历史对话页
const jumpToChatPage = (chatId) => {
  // 更新被选中的对话 ID
  selectedChatId.value = chatId
  router.push({ name: 'CustomerServiceChatPage', params: { chatId } })
}

// 删除对话确认框是否展示
const deleteChatModelOpen = ref(false)
// 被删除的对话 UUID
const deleteChatUUID = ref(null)

// 重命名对话弹出框是否展示
const renameChatModelOpen = ref(false)

// 表单对象
const formState = reactive({
  uuid: null, // 被重命名对话 UUID
  summary: '' // 被重命名对话摘要
})

// 处理菜单点击
const handleMenuClick = (uuid, summary, e) => {
  if (e.key === 'delete') { // 删除对话
    console.log('用户点击了删除对话：', uuid)
    // 展示确认框
    deleteChatModelOpen.value = true
    // 保存当前被删除的对话 UUID
    deleteChatUUID.value = uuid
  } else if (e.key === 'rename') { // 重命名对话
    console.log('用户点击了重命名对话：', uuid)
    
    // 展示弹出框
    renameChatModelOpen.value = true
    // 保存当前被重命名对话的 ID
    formState.uuid = uuid
    // 保存摘要
    formState.summary = summary

    console.log('表单数据：', formState)
  }
}

// 删除对话确认框确认事件
const handleDeleteChatModelOk = () => {
  deleteChat(deleteChatUUID.value).then((res) => {
      if (res.data.success) {
        message.success('删除成功')
        historyChats.value = historyChats.value.filter(chat => chat.uuid !== deleteChatUUID.value)
        if (route.params.chatId === deleteChatUUID.value) router.push({ name: 'CustomerServiceChatPage' })
      } else {
        // 提示错误信息
        message.error(res.data.message)
      }
  }).finally(
    // 隐藏确认框
    deleteChatModelOpen.value = false
  )
}

// 表单引用
const formRef = ref(null)

// 校验规则
const rules = {
  summary: [
   { required: true, message: '请输入对话摘要', trigger: 'change' }
  ]
}

const signOut = async () => {
  await auth.signOut()
  await router.replace({ name: 'Login' })
}

// 重命名对话弹出框确认事件
const handleRenameChatModelOk = () => {
  formRef.value
    .validate()
    .then(() => { // 校验通过
      renameChat(formState.uuid, formState.summary).then(res => {
          if (!res.data.success) {
            // 接口返回失败，提示错误消息
            message.error(res.data.message)

            return
          }

          // 提示操作成功
          message.success('操作成功！')
          
          // 更新 historyChats 数组中对应 id 的对话摘要
          const chatIndex = historyChats.value.findIndex(chat => chat.uuid === formState.uuid);
          if (chatIndex !== -1) {
            historyChats.value[chatIndex].summary = formState.summary;
          }
          
          // 关闭弹出框
          renameChatModelOpen.value = false
      })
    })
    .catch(error => { // 校验不通过
      console.log('error', error);
    })
}


</script>

<style scoped>
.overflow-y-auto {
  scrollbar-color: rgba(0, 0, 0, 0.2) transparent;
  /* 自定义滚动条颜色 */
}

.new-chat-btn {
  background-color: rgb(219 234 254);
  color: #4d6bfe;
}

.new-chat-btn:hover {
  background-color: #c6dcf8;
}
</style>
