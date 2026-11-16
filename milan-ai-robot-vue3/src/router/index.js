import { createRouter, createWebHashHistory } from 'vue-router'
import { useAuthStore } from '@/stores/authStore'

const routes = [
  { path: '/', redirect: { name: 'CustomerServiceChatPage' } },
  {
    path: '/login',
    name: 'Login',
    component: () => import('@/views/LoginPage.vue'),
    meta: { public: true, title: '登录 · 米兰企业知识库' },
  },
  {
    path: '/customer-service/chat/:chatId?',
    name: 'CustomerServiceChatPage',
    component: () => import('@/views/CustomerServiceChatPage.vue'),
    meta: { requiresAuth: true, title: '员工知识问答' },
  },
  {
    path: '/admin/access',
    name: 'AdminAccessPage',
    component: () => import('@/views/AdminAccessPage.vue'),
    meta: { requiresAuth: true, administrator: true, title: '账号与知识库授权' },
  },
  { path: '/:pathMatch(.*)*', redirect: { name: 'CustomerServiceChatPage' } },
]

const router = createRouter({ history: createWebHashHistory(), routes })

router.beforeEach(async (to) => {
  document.title = to.meta.title || '米兰企业知识库'
  const auth = useAuthStore()
  if (to.meta.public) {
    if (to.query.expired) auth.clear()
    const user = auth.user || await auth.initialize()
    if (user) return { name: 'CustomerServiceChatPage' }
    return true
  }
  const user = await auth.initialize()
  if (!user) return { name: 'Login', query: { redirect: to.fullPath } }
  if (to.meta.administrator && user.role !== 'ADMIN') return { name: 'CustomerServiceChatPage' }
  return true
})

export default router
