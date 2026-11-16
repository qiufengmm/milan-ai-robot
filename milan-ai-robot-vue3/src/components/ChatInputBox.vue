<template>
  <div :class="containerClass">
    <div class="mx-4 flex flex-col rounded-3xl border border-gray-200 bg-gray-100 px-4 py-3">
      <textarea ref="textareaRef" v-model="userMessage" :placeholder="placeholder" :disabled="disabled"
        class="min-h-[24px] w-full resize-none border-none bg-transparent text-sm outline-none" rows="2"
        @input="autoResize" @keydown.enter="handleEnter"></textarea>
      <div class="mt-3 flex justify-end">
        <a-tooltip placement="top">
          <template #title><span>请输入你的问题</span></template>
          <button class="flex h-8 w-8 items-center justify-center rounded-full border border-[#4d6bfe] bg-[#4d6bfe] transition-colors hover:bg-[#3b5bef] disabled:cursor-not-allowed disabled:opacity-50"
            :disabled="disabled || !userMessage.trim()" @click="handleSendMessage">
            <SvgIcon name="up-arrow" customCss="h-5 w-5 text-white" />
          </button>
        </a-tooltip>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, nextTick, watch } from 'vue'
import SvgIcon from '@/components/SvgIcon.vue'

const props = defineProps({
  modelValue: { type: String, required: true },
  containerClass: { type: String, default: '' },
  placeholder: { type: String, default: '向企业知识库提问……' },
  disabled: { type: Boolean, default: false },
})
const emit = defineEmits(['update:modelValue', 'sendMessage'])
const userMessage = computed({
  get: () => props.modelValue,
  set: value => emit('update:modelValue', value),
})
const textareaRef = ref(null)

const autoResize = () => {
  const textarea = textareaRef.value
  if (!textarea) return
  textarea.style.height = 'auto'
  textarea.style.height = `${Math.min(textarea.scrollHeight, 300)}px`
  textarea.style.overflowY = textarea.scrollHeight > 300 ? 'auto' : 'hidden'
}

watch(() => props.modelValue, () => nextTick(autoResize))

const handleSendMessage = () => {
  if (props.disabled || !userMessage.value.trim()) return
  emit('sendMessage')
  userMessage.value = ''
  nextTick(autoResize)
}

const handleEnter = event => {
  if (event.isComposing || event.keyCode === 229 || event.shiftKey) return
  event.preventDefault()
  handleSendMessage()
}
</script>
