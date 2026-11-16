<template>
  <Layout :show-footer-text="false">
    <template #main-content>
      <main class="h-screen overflow-y-auto bg-slate-50 p-6 md:p-10">
        <div class="mx-auto max-w-5xl space-y-6">
          <header>
            <h1 class="text-2xl font-semibold text-slate-800">账号与知识库授权</h1>
            <p class="mt-1 text-sm text-slate-500">管理员创建员工账号、知识库并分配读写权限。</p>
          </header>

          <a-card title="创建普通员工账号">
            <a-form layout="inline" @finish="submitUser">
              <a-form-item label="账号" name="username" :rules="[{ required: true }]">
                <a-input v-model:value="newUser.username" placeholder="例如 hr.editor" />
              </a-form-item>
              <a-form-item label="显示名称" name="displayName" :rules="[{ required: true }]">
                <a-input v-model:value="newUser.displayName" placeholder="例如 人事维护者" />
              </a-form-item>
              <a-form-item label="初始密码" name="password" :rules="[{ required: true }]">
                <a-input-password v-model:value="newUser.password" placeholder="至少12个字符" />
              </a-form-item>
              <a-form-item><a-button type="primary" html-type="submit" :loading="savingUser">创建账号</a-button></a-form-item>
            </a-form>
          </a-card>

          <a-card title="创建知识库">
            <a-form layout="inline" @finish="submitLibrary">
              <a-form-item label="名称" name="name" :rules="[{ required: true }]">
                <a-input v-model:value="newLibrary.name" :maxlength="100" placeholder="员工服务或 IT 支持" />
              </a-form-item>
              <a-form-item label="说明">
                <a-input v-model:value="newLibrary.description" :maxlength="500" class="min-w-72" />
              </a-form-item>
              <a-form-item><a-button type="primary" html-type="submit" :loading="savingLibrary">创建知识库</a-button></a-form-item>
            </a-form>
          </a-card>

          <a-card title="知识库成员权限">
            <div class="mb-5 flex flex-wrap items-center gap-3">
              <span class="text-sm text-slate-600">知识库</span>
              <a-select v-model:value="selectedKnowledgeBaseId" :options="knowledgeBaseOptions" class="min-w-52" @change="loadMembers" />
              <a-select v-model:value="selectedUserId" :options="employeeOptions" placeholder="选择员工账号" class="min-w-52" />
              <a-select v-model:value="accessLevel" class="w-36">
                <a-select-option value="READER">只读</a-select-option>
                <a-select-option value="EDITOR">维护者</a-select-option>
              </a-select>
              <a-button type="primary" :disabled="!selectedKnowledgeBaseId || !selectedUserId" :loading="savingMember" @click="saveMember">保存授权</a-button>
            </div>
            <a-table :data-source="members" :columns="memberColumns" :loading="loadingMembers" row-key="userId" :pagination="false">
              <template #bodyCell="{ column, record }">
                <template v-if="column.key === 'user'">{{ userName(record.userId) }}</template>
                <template v-else-if="column.key === 'accessLevel'">{{ record.accessLevel === 'EDITOR' ? '维护者（读写）' : '员工（只读）' }}</template>
                <template v-else-if="column.key === 'action'">
                  <a-button size="small" danger @click="removeMember(record.userId)">撤销授权</a-button>
                </template>
              </template>
            </a-table>
          </a-card>

          <a-card title="员工账号">
            <a-table :data-source="users" :columns="userColumns" :loading="loadingUsers" row-key="id" :pagination="false">
              <template #bodyCell="{ column, record }">
                <template v-if="column.key === 'enabled'">{{ record.enabled ? '启用' : '已停用' }}</template>
                <template v-else-if="column.key === 'action'">
                  <a-button size="small" :disabled="record.role === 'ADMIN'" :danger="record.enabled" @click="toggleUser(record)">
                    {{ record.enabled ? '停用' : '启用' }}
                  </a-button>
                </template>
              </template>
            </a-table>
          </a-card>
        </div>
      </main>
    </template>
  </Layout>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { message } from 'ant-design-vue'
import Layout from '@/layouts/Layout.vue'
import { createKnowledgeBase, listKnowledgeBases } from '@/api/knowledgeBase'
import { assignKnowledgeBaseMember, createUser, listKnowledgeBaseMembers, listUsers, revokeKnowledgeBaseMember, setUserEnabled } from '@/api/admin'

const users = ref([])
const knowledgeBases = ref([])
const members = ref([])
const loadingUsers = ref(false)
const loadingMembers = ref(false)
const savingUser = ref(false)
const savingLibrary = ref(false)
const savingMember = ref(false)
const selectedKnowledgeBaseId = ref()
const selectedUserId = ref()
const accessLevel = ref('READER')
const newUser = reactive({ username: '', displayName: '', password: '' })
const newLibrary = reactive({ name: '', description: '' })
const knowledgeBaseOptions = computed(() => knowledgeBases.value.map(item => ({ value: Number(item.id), label: item.name })))
const employeeOptions = computed(() => users.value.filter(item => item.role === 'USER' && item.enabled)
  .map(item => ({ value: item.id, label: `${item.displayName}（${item.username}）` })))
const memberColumns = [
  { title: '员工', key: 'user' }, { title: '权限', key: 'accessLevel' }, { title: '操作', key: 'action', width: 130 },
]
const userColumns = [
  { title: '账号', dataIndex: 'username' }, { title: '显示名称', dataIndex: 'displayName' },
  { title: '身份', dataIndex: 'role' }, { title: '状态', key: 'enabled' }, { title: '操作', key: 'action', width: 120 },
]
const userName = id => users.value.find(item => item.id === id)?.displayName || `员工 ${id}`

const loadUsers = async () => {
  loadingUsers.value = true
  try { users.value = (await listUsers()).data.data || [] }
  finally { loadingUsers.value = false }
}
const loadLibraries = async () => {
  knowledgeBases.value = (await listKnowledgeBases()).data.data || []
  if (!selectedKnowledgeBaseId.value && knowledgeBases.value.length) selectedKnowledgeBaseId.value = Number(knowledgeBases.value[0].id)
}
const loadMembers = async () => {
  if (!selectedKnowledgeBaseId.value) { members.value = []; return }
  loadingMembers.value = true
  try { members.value = (await listKnowledgeBaseMembers(selectedKnowledgeBaseId.value)).data.data || [] }
  finally { loadingMembers.value = false }
}
const submitUser = async () => {
  savingUser.value = true
  try {
    await createUser(newUser)
    newUser.username = ''; newUser.displayName = ''; newUser.password = ''
    message.success('员工账号已创建')
    await loadUsers()
  } finally { savingUser.value = false }
}
const submitLibrary = async () => {
  savingLibrary.value = true
  try {
    const response = await createKnowledgeBase(newLibrary)
    newLibrary.name = ''; newLibrary.description = ''
    await loadLibraries()
    selectedKnowledgeBaseId.value = Number(response.data.data)
    await loadMembers()
    message.success('知识库已创建')
  } finally { savingLibrary.value = false }
}
const saveMember = async () => {
  savingMember.value = true
  try {
    await assignKnowledgeBaseMember(selectedKnowledgeBaseId.value, { userId: selectedUserId.value, accessLevel: accessLevel.value })
    await loadMembers()
    message.success('知识库权限已保存')
  } finally { savingMember.value = false }
}
const removeMember = async userId => {
  await revokeKnowledgeBaseMember(selectedKnowledgeBaseId.value, userId)
  await loadMembers()
  message.success('已撤销授权')
}
const toggleUser = async user => {
  await setUserEnabled(user.id, !user.enabled)
  await loadUsers()
  message.success(user.enabled ? '账号已停用' : '账号已启用')
}

onMounted(async () => {
  await Promise.all([loadUsers(), loadLibraries()])
  await loadMembers()
})
</script>
