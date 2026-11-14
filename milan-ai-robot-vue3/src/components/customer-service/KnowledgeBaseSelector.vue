<template>
  <div class="flex flex-wrap items-center gap-2">
    <span class="text-sm text-gray-500">当前知识库</span>
    <a-select :value="modelValue.id" :options="options" :loading="loading" :disabled="disabled || saving"
      aria-label="选择知识库" style="min-width: 190px; max-width: 300px" @change="selectKnowledgeBase" />
    <a-button size="small" :disabled="disabled || saving" @click="openEditor(false)">新建</a-button>
    <a-button size="small" :disabled="disabled || saving" @click="openEditor(true)">编辑</a-button>
    <span v-if="disabled" class="text-xs text-gray-500">上传期间请留在当前知识库</span>
  </div>
  <p v-if="modelValue.description" class="text-sm text-gray-500 mt-2 mb-0">{{ modelValue.description }}</p>

  <a-modal v-model:open="editorOpen" :title="editing ? '编辑知识库' : '新建知识库'" ok-text="保存" cancel-text="取消"
    :confirm-loading="saving" :mask-closable="!saving" :closable="!saving" @ok="save">
    <a-form layout="vertical" class="mt-5">
      <a-form-item label="名称" required>
        <a-input v-model:value="form.name" :maxlength="100" show-count placeholder="例如：米兰数码商城" />
      </a-form-item>
      <a-form-item label="说明">
        <a-textarea v-model:value="form.description" :maxlength="500" show-count :rows="4" placeholder="描述这个知识库的业务场景" />
      </a-form-item>
    </a-form>
  </a-modal>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { message } from 'ant-design-vue'
import { createKnowledgeBase, listKnowledgeBases, updateKnowledgeBase } from '@/api/knowledgeBase'

const props = defineProps({
  modelValue: { type: Object, required: true },
  disabled: { type: Boolean, default: false },
})
const emit = defineEmits(['update:modelValue'])
const knowledgeBases = ref([props.modelValue])
const loading = ref(false)
const saving = ref(false)
const editorOpen = ref(false)
const editing = ref(false)
const editingId = ref(null)
const form = reactive({ name: '', description: '' })
const options = computed(() => knowledgeBases.value.map(base => ({ value: base.id, label: base.name })))

let loadRequest = 0
const load = async (preferredId = props.modelValue.id) => {
  const requestId = ++loadRequest
  const selectedAtStart = props.modelValue.id
  loading.value = true
  try {
    const response = await listKnowledgeBases()
    if (requestId !== loadRequest) return
    knowledgeBases.value = (response.data.data || []).map(base => ({ ...base, id: Number(base.id) }))
    const targetId = props.modelValue.id === selectedAtStart ? preferredId : props.modelValue.id
    const selected = knowledgeBases.value.find(base => base.id === Number(targetId))
      || knowledgeBases.value.find(base => base.id === 1) || knowledgeBases.value[0]
    if (selected && (!props.disabled || selected.id === props.modelValue.id)) emit('update:modelValue', selected)
  } finally {
    if (requestId === loadRequest) loading.value = false
  }
}

const selectKnowledgeBase = id => {
  const selected = knowledgeBases.value.find(base => base.id === id)
  if (selected && !props.disabled) emit('update:modelValue', selected)
}

const openEditor = isEditing => {
  editing.value = isEditing
  editingId.value = isEditing ? props.modelValue.id : null
  form.name = isEditing ? props.modelValue.name : ''
  form.description = isEditing ? props.modelValue.description || '' : ''
  editorOpen.value = true
}

const save = async () => {
  const name = form.name.trim()
  if (!name) {
    message.warning('请输入知识库名称')
    return
  }
  saving.value = true
  try {
    const fields = { name, description: form.description.trim() }
    let selectedId = editingId.value
    if (editing.value) await updateKnowledgeBase(selectedId, fields)
    else selectedId = Number((await createKnowledgeBase(fields)).data.data)
    const saved = { id: selectedId, ...fields }
    knowledgeBases.value = [...knowledgeBases.value.filter(base => base.id !== selectedId), saved]
    emit('update:modelValue', saved)
    editorOpen.value = false
    message.success('知识库已保存')
    // A failed refresh must not make a second click create a duplicate library.
    load(selectedId).catch(() => {})
  } catch {
    // Axios displays API errors; keep the editor open for correction/retry.
  } finally {
    saving.value = false
  }
}

onMounted(() => { load().catch(() => {}) })
</script>
