<template>
  <main class="min-h-screen flex items-center justify-center bg-slate-50 px-4">
    <section class="w-full max-w-md rounded-2xl border border-slate-200 bg-white p-8 shadow-sm">
      <div class="mb-7 text-center">
        <h1 class="text-2xl font-semibold text-slate-800">米兰企业知识库</h1>
        <p class="mt-2 text-sm text-slate-500">使用管理员分配的企业账号登录</p>
      </div>
      <a-form layout="vertical" @finish="submit">
        <a-form-item label="账号" name="username" :rules="[{ required: true, message: '请输入账号' }]">
          <a-input v-model:value="form.username" autocomplete="username" size="large" />
        </a-form-item>
        <a-form-item label="密码" name="password" :rules="[{ required: true, message: '请输入密码' }]">
          <a-input-password v-model:value="form.password" autocomplete="current-password" size="large" />
        </a-form-item>
        <a-button type="primary" html-type="submit" size="large" block :loading="loading">登录</a-button>
      </a-form>
      <p v-if="$route.query.expired" class="mt-4 text-sm text-amber-700">登录状态已失效，请重新登录。</p>
    </section>
  </main>
</template>

<script setup>
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import { useAuthStore } from '@/stores/authStore'

const form = reactive({ username: '', password: '' })
const loading = ref(false)
const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

const submit = async () => {
  loading.value = true
  try {
    await auth.signIn(form)
    message.success('登录成功')
    const target = typeof route.query.redirect === 'string' ? route.query.redirect : '/customer-service/chat'
    await router.replace(target)
  } catch {
    message.error('账号或密码错误，或账号已停用')
  } finally {
    loading.value = false
    form.password = ''
  }
}
</script>
