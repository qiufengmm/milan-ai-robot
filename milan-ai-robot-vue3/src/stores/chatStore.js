import { defineStore } from 'pinia'
import { ref } from 'vue'
import { restoreChatSelection } from './persistedChatSelection.js'

// 创建store
export const useChatStore = defineStore('chat', () => {

  // 定义模型列表
  const initialSelection = restoreChatSelection()
  const models = ref(initialSelection.models)

  // 选中的模型，默认为第一个
  const selectedModel = ref(initialSelection.selectedModel)

  // 联网搜索状态，默认为false
  const isNetworkSearchSelected = ref(false)

  // 更新选中的模型
  function updateSelectedModel(model) {
    const selection = restoreChatSelection({ selectedModel: model })
    models.value = selection.models
    selectedModel.value = selection.selectedModel
  }

  // 更新联网搜索状态
  function updateNetworkSearchStatus(status) {
    isNetworkSearchSelected.value = status
  }

  // 对外暴露相关变量与方法
  return { models, selectedModel, isNetworkSearchSelected, updateSelectedModel, updateNetworkSearchStatus }
},
{
  // 开启持久化
  persist: {
    pick: ['selectedModel', 'isNetworkSearchSelected'],
    afterHydrate({ store }) {
      store.updateSelectedModel(store.selectedModel)
      store.$persist()
    },
    serializer: {
      serialize: JSON.stringify,
      deserialize(value) {
        const persisted = JSON.parse(value)
        return { ...persisted, ...restoreChatSelection(persisted) }
      },
    },
  },
})
