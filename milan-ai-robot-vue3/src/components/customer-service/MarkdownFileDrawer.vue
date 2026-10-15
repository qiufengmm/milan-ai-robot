<template>
  <!-- 抽屉：客服问答文件管理 -->
  <a-drawer width="80%" title="客服问答文件管理" placement="right" :open="open" @close="handleClose">
    <!-- 搜索区域 -->
    <div class="mb-5">
      <a-form layout="inline" :model="formState">
        <a-form-item label="文件名称：" name="fileName">
          <a-input v-model:value="formState.fileName" placeholder="请输入文件名称（模糊查询）" style="width: 230px" allowClear>
          </a-input>
        </a-form-item>
        <a-form-item label="创建时间：" name="startEndDate">
          <a-range-picker v-model:value="formState.startEndDate" />
        </a-form-item>
        <a-button type="primary" :icon="h(SearchOutlined)" class="mr-3" @click="handleSearch">查询</a-button>
        <a-button :icon="h(RedoOutlined)" @click="handleReset">重置</a-button>
      </a-form>
      <!-- 分割线 -->
      <a-divider />
    </div>

    <a-button type="primary" class="mb-5" :icon="h(UploadOutlined)" @click="triggerFileUpload" :loading="uploadBtnLoading">上传问答文件</a-button>

    <!-- 隐藏的 file input 元素 -->
    <input
      type="file"
      ref="fileInput"
      @change="handleFileSelect"
      class="hidden"
      accept=".md,.markdown,text/markdown,.pdf,.doc,.docx,.txt"
    />

    <a-table :dataSource="dataSource" :columns="columns" :pagination="pagination" @change="handleTableChange" :loading="loading">
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'action'">
          <span>
            <a-button @click="showEditMarkdownModel(record)">编辑</a-button>
            <a-divider type="vertical" />
            <a-button danger @click="showDeleteMarkdownModel(record.id)">删除</a-button>
          </span>
        </template>
        <template v-else-if="column.key === 'status'">
          <StatusTag :status="record.status" />
        </template>
      </template>
    </a-table>
  </a-drawer>

  <!-- 删除 Markdown 问答文件确认框 -->
  <a-modal v-model:open="deleteMarkdownModelOpen" width="400px" :centered=true title="永久删除问答文件" ok-text="确认" ok-type="danger" cancel-text="取消"
    :confirm-loading="deleteBtnLoading" @ok="handleDeleteMarkdownModelOk">
    <p>是否确认删除此 Markdown 问答文件？</p>
  </a-modal>

  <!-- 编辑 Markdown 问答文件模态框 -->
  <a-modal v-model:open="editMarkdownModelOpen" width="700px" :centered=true title="编辑问答文件" ok-text="提交" cancel-text="取消"
    :confirm-loading="editBtnLoading" @ok="handleEditMarkdownModelOk">
    <div class="mt-7"></div>
    <a-form
      :model="editMarkdownRecord"
      :label-col="{ span: 4 }"
      :wrapper-col="{ span: 20 }"
      autocomplete="off"
    >
      <a-form-item label="ID" name="id">
        <a-input v-model:value="editMarkdownRecord.id" disabled/>
      </a-form-item>
      <a-form-item label="文件名" name="originalFileName">
        <a-input v-model:value="editMarkdownRecord.originalFileName" disabled/>
      </a-form-item>
      <a-form-item label="文件大小" name="fileSize">
        <a-input v-model:value="editMarkdownRecord.fileSize" disabled/>
      </a-form-item>
      <a-form-item label="处理状态" name="status">
        <StatusTag :status="editMarkdownRecord.status" />
      </a-form-item>
      <a-form-item label="创建时间" name="createTime">
        <a-input v-model:value="editMarkdownRecord.createTime" disabled/>
      </a-form-item>
      <a-form-item label="更新时间" name="updateTime">
        <a-input v-model:value="editMarkdownRecord.updateTime" disabled/>
      </a-form-item>
      <a-form-item label="备注" name="remark">
        <!-- maxlength 必须与数据库 remark 字段长度、后端 @Size(max = ...) 保持一致（三处对齐为 500），
             否则用户在输入框里能一直打字，点了提交才被后端拦下来报错。 -->
        <a-textarea v-model:value="editMarkdownRecord.remark"
          placeholder="请输入备注信息（留空即可清空原有备注）"
          :maxlength="500"
          :auto-size="{ minRows: 5, maxRows: 10 }"
          allow-clear show-count />
      </a-form-item>
    </a-form>
  </a-modal>
</template>

<script setup>
import { ref, reactive, computed, h, watch } from 'vue'
import { UploadOutlined, SearchOutlined, RedoOutlined } from '@ant-design/icons-vue'
import { message, Modal } from 'ant-design-vue'
import StatusTag from './StatusTag.vue'
import {
  findMarkdownFilePageList,
  uploadMarkdownFile,
  deleteMarkdownFile,
  updateMarkdownFile,
} from '@/api/customerService'

const props = defineProps({
  // 是否展示抽屉
  open: {
    type: Boolean,
    default: false,
  },
})

const emit = defineEmits(['update:open'])

// 表格加载状态
const loading = ref(false)
// 上传按钮 Loading 动画
const uploadBtnLoading = ref(false)
// 删除确认框「确认」按钮 Loading
const deleteBtnLoading = ref(false)
// 编辑模态框「提交」按钮 Loading
const editBtnLoading = ref(false)

// 搜索表单
const formState = reactive({
  fileName: '', // 文件名称
  startEndDate: [], // 起始、结束日期
})

// 表格列
const columns = ref([
  {
    title: '文件名称',
    dataIndex: 'originalFileName',
    key: 'originalFileName',
  },
  {
    title: '文件大小',
    dataIndex: 'fileSize',
    key: 'fileSize',
  },
  {
    title: '状态',
    dataIndex: 'status',
    key: 'status',
  },
  {
    title: '创建时间',
    dataIndex: 'createTime',
    key: 'createTime',
  },
  {
    title: '更新时间',
    dataIndex: 'updateTime',
    key: 'updateTime',
  },
  {
    title: '备注',
    dataIndex: 'remark',
    key: 'remark',
  },
  {
    title: '操作',
    key: 'action',
  },
])

// 表格数据
const dataSource = ref([])

// 分页数据
// 当前页码
const current = ref(1)
// 每页展示数据量
const pageSize = ref(10)
// 总数据量
const total = ref(0)

const pagination = computed(() => ({
  total: total.value,
  current: current.value,
  pageSize: pageSize.value,
}))

// 渲染 a-table 表格数据
const renderTableData = (pageNo, size) => {
  // 设置 a-table 组件加载状态为 true
  loading.value = true

  const [startDate, endDate] = formState.startEndDate
  const startDateStr = startDate?.format('YYYY-MM-DD')
  const endDateStr = endDate?.format('YYYY-MM-DD')

  // 请求分页接口
  return findMarkdownFilePageList(pageNo, size, formState.fileName, startDateStr, endDateStr)
    .then((res) => {
      dataSource.value = res.data.data
      current.value = res.data.current
      total.value = res.data.total
    })
    .catch(() => {
      // 业务失败 / 网络异常已由 axios 响应拦截器统一提示，这里只需兜住，避免未捕获的 Promise 报错
    })
    .finally(() => {
      // 设置 a-table 组件加载状态为 false
      loading.value = false
    })
}

// 抽屉打开时，刷新表格数据
watch(
  () => props.open,
  (val) => {
    if (val) {
      renderTableData(current.value, pageSize.value)
    }
  }
)

// 关闭抽屉
const handleClose = () => {
  emit('update:open', false)
}

// 表格变化监听事件（点击页码、切换每页条数时会触发）
const handleTableChange = (pageObj) => {
  // 回写 pageSize，否则切换每页条数后分页器显示的仍是旧值
  pageSize.value = pageObj.pageSize
  renderTableData(pageObj.current, pageObj.pageSize)
}

// 搜索按钮点击事件
// 注意：查询条件变了，必须回到第 1 页，
// 否则在第 2 页搜索时，若筛选后只剩 1 页数据，会请求到不存在的第 2 页导致表格空白
const handleSearch = () => {
  renderTableData(1, pageSize.value)
}

// 重置搜索表单
const handleReset = () => {
  formState.fileName = ''
  formState.startEndDate = []

  // 重置表单后，渲染第一页的数据
  renderTableData(1, pageSize.value)
}

// 隐藏的文件上传 input 引用
const fileInput = ref(null)

// 触发文件选择对话框
const triggerFileUpload = () => {
  fileInput.value?.click()
}

// 处理文件选择
const handleFileSelect = (event) => {
  const file = event.target.files[0]
  if (!file) {
    return
  }

  // 表单对象
  const formData = new FormData()
  // 添加 file 字段，并将文件传入
  formData.append('file', file)

  // 显示上传按钮的 Loading 动画
  uploadBtnLoading.value = true

  uploadMarkdownFile(formData)
    .then(() => {
      message.success('上传成功')

      // 重新渲染列表数据
      renderTableData(1, pageSize.value)
    })
    .catch(() => {})
    .finally(() => {
      // 隐藏上传按钮的 Loading 动画
      uploadBtnLoading.value = false
      // 清空 input 选中的文件，否则连续上传同一个文件不会触发 change 事件
      event.target.value = ''
    })
}

// 是否展示 “删除 Markdown 文件” 确认框
const deleteMarkdownModelOpen = ref(false)

// 被删除的文件记录 ID
const deleteMarkdownId = ref(null)

// 展示 “删除 Markdown 文件” 确认框
const showDeleteMarkdownModel = (id) => {
  // 展示确认框
  deleteMarkdownModelOpen.value = true
  // 保存被删除的文件记录 ID
  deleteMarkdownId.value = id
}

// 执行删除。force 为 true 表示强制删除（跳过「处理中不允许删除」的限制）
const doDelete = (id, force) => {
  deleteBtnLoading.value = true

  return deleteMarkdownFile(id, force, { silent: true })
    .then(() => {
      message.success('删除成功')

      // 隐藏确认框
      deleteMarkdownModelOpen.value = false

      // 重新渲染列表数据
      renderTableData(1, pageSize.value)
    })
    .catch((err) => {
      // 20005：文件正在处理中。多半是卡在向量化流程里的僵死记录，问一下要不要强制删除
      if (err?.errorCode === '20005' && !force) {
        Modal.confirm({
          title: '该文件仍在处理中',
          content: '它可能卡在了向量化流程里（例如服务中途重启过）。强制删除会同时清除已生成的向量数据，确定继续吗？',
          okText: '强制删除',
          okType: 'danger',
          cancelText: '取消',
          onOk: () => doDelete(id, true),
        })
        return
      }

      message.warning(err?.message || '删除失败')
    })
    .finally(() => {
      deleteBtnLoading.value = false
    })
}

// “删除 Markdown 文件” 确认框确认按钮点击事件
const handleDeleteMarkdownModelOk = () => {
  doDelete(deleteMarkdownId.value, false)
}

// 是否展示 “编辑 Markdown 文件” 模态框
const editMarkdownModelOpen = ref(false)

// 被编辑的文件记录
const editMarkdownRecord = reactive({
  id: null,
  originalFileName: '',
  fileSize: '',
  status: null,
  createTime: '',
  updateTime: '',
  remark: '',
})

// 展示 “编辑 Markdown 文件” 模态框
const showEditMarkdownModel = (record) => {
  // 展示模态框
  editMarkdownModelOpen.value = true
  // 保存被编辑的文件记录（record 里正好是表单需要的这 7 个字段）
  Object.assign(editMarkdownRecord, record)
}

// 编辑模态框 “提交” 按钮事件
const handleEditMarkdownModelOk = () => {
  editBtnLoading.value = true

  updateMarkdownFile(editMarkdownRecord)
    .then(() => {
      message.success('更新成功')

      // 隐藏模态框
      editMarkdownModelOpen.value = false

      // 重新渲染列表数据
      renderTableData(current.value, pageSize.value)
    })
    .catch(() => {})
    .finally(() => {
      editBtnLoading.value = false
    })
}
</script>

<style>
/* 解决抽屉中按钮的图标没有垂直居中问题。
   注意：这里不能用 scoped，否则选择器会被加上 [data-v-xxx]，匹配不到 antd 组件内部的 span。
   也不要抄教程里的 :where(.css-dev-only-do-not-override-xxxxx)，那个哈希类名每版都会变、
   生产构建里也不存在，只能临时对付一下。 */
.ant-drawer .ant-btn > span {
  display: inline-flex;
  align-items: center;
}
</style>
