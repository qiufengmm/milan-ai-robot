<template>
  <a-tag :color="current.color">{{ current.text }}</a-tag>
</template>

<script setup>
import { computed } from 'vue'

const props = defineProps({ status: { type: String, default: 'NOT_READY' } })
const states = {
  SYNCED: { text: '索引已同步', color: 'success' },
  PENDING: { text: '等待同步', color: 'processing' },
  FAILED: { text: '同步失败', color: 'error' },
  NOT_READY: { text: '等待文件处理', color: 'default' },
}
const current = computed(() => states[props.status] || states.NOT_READY)
</script>
