<template>
  <a-tag :color="current.color">{{ current.text }}</a-tag>
</template>

<script setup>
import { computed } from 'vue'

/**
 * Markdown 问答文件处理状态标签。
 * 状态码与后端 AiCustomerServiceMdStatusEnum 保持一致。
 */
const STATUS_MAP = {
  0: { text: '待处理', color: 'default' },
  1: { text: '向量化中', color: 'processing' },
  2: { text: '已完成', color: 'success' },
  3: { text: '失败', color: 'error' },
}

const props = defineProps({
  status: {
    type: Number,
    default: null,
  },
})

const current = computed(() => STATUS_MAP[props.status] || { text: '未知', color: 'default' })
</script>
