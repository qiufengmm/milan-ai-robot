<template>
  <a-tag :color="current.color">{{ current.text }}</a-tag>
</template>

<script setup>
import { computed } from 'vue'

/**
 * 问答文件处理状态标签。
 * 状态码与后端 AiCustomerServiceFileStatusEnum 保持一致。
 */
const STATUS_MAP = {
  0: { text: '上传中', color: 'default' },
  1: { text: '待处理', color: 'processing' },
  2: { text: '向量化中', color: 'processing' },
  3: { text: '已完成', color: 'success' },
  4: { text: '失败', color: 'error' },
}

const props = defineProps({
  status: {
    type: Number,
    default: null,
  },
})

const current = computed(() => STATUS_MAP[props.status] || { text: '未知', color: 'default' })
</script>
