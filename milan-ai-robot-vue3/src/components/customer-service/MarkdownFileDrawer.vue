<template>
  <!-- 企业知识库资料管理 -->
  <a-drawer width="80%" :title="`${knowledgeBase.name || '企业知识库'} · 资料管理`" placement="right" :open="open" @close="handleClose">
    <a-alert message="文件处理完成且索引同步后才可完整检索。失败或等待同步的文件可在处理后重建索引。" type="info" show-icon class="mb-5" />
    <a-card v-if="canEdit && hasKnowledgeBase" title="资料版本与发布" class="mb-5">
      <div class="mb-4 flex flex-wrap items-center gap-3">
        <span class="text-sm text-slate-600">选择逻辑资料</span>
        <a-select v-model:value="selectedDocumentId" :options="documentOptions" allow-clear placeholder="查看或选择要替换的资料" class="min-w-64" :disabled="uploading" @change="selectDocument" />
        <a-select v-if="selectedDocument" v-model:value="selectedVersionId" :options="versionOptions" class="min-w-64" placeholder="选择版本" />
        <a-button v-if="selectedDocument && selectedVersionId" @click="loadPreview">查看解析片段</a-button>
        <a-button v-if="canPublishSelected" type="primary" :loading="publishing" @click="publishSelected">发布所选版本</a-button>
        <a-button v-if="canWithdrawSelected" danger :loading="publishing" @click="withdrawSelected">撤回当前版本</a-button>
      </div>
      <p v-if="selectedDocument" class="text-sm text-slate-500">
        当前发布版本：{{ selectedDocument.currentFileId || '无' }}。上传时保留逻辑资料选择会创建下一版本；清空选择则新建资料。
      </p>
      <div v-if="previewChunks.length" class="mt-3 space-y-2">
        <div v-for="chunk in previewChunks" :key="chunk.chunkId" class="rounded-lg border border-slate-200 bg-slate-50 p-3 text-sm whitespace-pre-wrap">{{ chunk.content }}</div>
      </div>
      <a-empty v-else-if="previewLoaded" description="没有解析片段" />
    </a-card>
    <a-card v-if="canEdit && hasKnowledgeBase" title="员工反馈" class="mb-5" :loading="feedbackLoading">
      <a-empty v-if="!feedbackLoading && !feedbackQueue.length" description="当前知识库暂无员工反馈" />
      <div v-else class="space-y-4">
        <article v-for="item in feedbackQueue" :key="item.id" class="rounded-xl border border-slate-200 p-4">
          <div class="mb-2 flex flex-wrap items-center gap-2 text-sm">
            <a-tag :color="item.status === 'OPEN' ? 'orange' : 'green'">{{ item.status === 'OPEN' ? '待处理' : '已处理' }}</a-tag>
            <span class="text-slate-500">{{ item.reporterDisplayName || '员工' }} · {{ item.updateTime || item.createTime }}</span>
          </div>
          <p class="mb-1 text-sm font-medium">员工问题：{{ item.userQuestion || '未记录原问题' }}</p>
          <p class="mb-2 whitespace-pre-wrap break-words text-sm text-slate-700">反馈说明：{{ item.description }}</p>
          <details class="mb-3 text-sm">
            <summary class="cursor-pointer text-slate-600">查看对应回答与来源</summary>
            <p class="mt-2 whitespace-pre-wrap break-words text-slate-700">{{ item.answerContent }}</p>
            <div v-for="(source, index) in item.sources || []" :key="`${source.chunkId || source.fileId}-${index}`" class="mt-2 rounded-lg bg-slate-50 p-3">
              <div class="mb-1 flex flex-wrap items-center gap-2 font-medium">
                <span>{{ source.title || source.fileName || '知识库资料' }}</span>
                <a-tag>v{{ source.versionNo || '-' }}</a-tag>
                <a-tag v-if="source.historical" color="orange">历史版本</a-tag>
              </div>
              <p class="mb-0 whitespace-pre-wrap break-words text-slate-600">{{ source.excerpt }}</p>
            </div>
          </details>
          <p v-if="item.status === 'RESOLVED'" class="mb-0 rounded-lg bg-emerald-50 p-3 text-sm text-emerald-900">处理说明：{{ item.resolutionNote }}</p>
          <div v-else class="flex flex-wrap items-start gap-2">
            <a-textarea v-model:value="item.resolutionDraft" :maxlength="500" :rows="2" show-count placeholder="写明已核查或更新的处理结果" class="max-w-2xl" />
            <a-button type="primary" :loading="item.resolving" @click="resolveFeedbackItem(item)">标记已处理</a-button>
          </div>
        </article>
      </div>
    </a-card>
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
        <a-button type="primary" :disabled="!hasKnowledgeBase" :icon="h(SearchOutlined)" class="mr-3" @click="handleSearch">查询</a-button>
        <a-button :disabled="!hasKnowledgeBase" :icon="h(RedoOutlined)" @click="handleReset">重置</a-button>
      </a-form>
      <!-- 分割线 -->
      <a-divider />
    </div>

    <div class="flex gap-3 mb-5">
      <a-button v-if="canEdit" type="primary" :disabled="uploading || !hasKnowledgeBase" :icon="h(UploadOutlined)" @click="triggerFileUpload">上传资料</a-button>
      <a-button v-if="canEdit" :disabled="!hasKnowledgeBase" :loading="reindexing" @click="handleReindex">重建当前库索引</a-button>
    </div>

    <!-- 保留视频格式用于验证大文件分片/续传；视频无法向量化，处理结果会显示失败。 -->
    <input
      type="file"
      ref="fileInput"
      @change="handleFileSelect"
      class="hidden"
      accept=".md,.markdown,text/markdown,.pdf,.doc,.docx,.txt,.mp4,.mov,.avi"
    />

    <a-table :dataSource="dataSource" :columns="columns" :pagination="pagination" @change="handleTableChange" :loading="loading">
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'action'">
          <span>
            <a-button v-if="canEdit" :disabled="!hasKnowledgeBase" @click="showEditMarkdownModel(record)">编辑</a-button>
            <a-divider v-if="canEdit" type="vertical" />
            <a-button v-if="canEdit" danger :disabled="!hasKnowledgeBase" @click="showDeleteMarkdownModel(record.id)">删除草稿</a-button>
          </span>
        </template>
        <template v-else-if="column.key === 'status'">
          <StatusTag :status="record.status" />
        </template>
        <template v-else-if="column.key === 'indexStatus'">
          <IndexStatusTag :status="record.indexStatus" />
        </template>
      </template>
    </a-table>
  </a-drawer>

  <!-- 删除问答文件确认框 -->
  <a-modal v-model:open="deleteMarkdownModelOpen" width="400px" :centered=true title="永久删除问答文件" ok-text="确认" ok-type="danger" cancel-text="取消"
    :confirm-loading="deleteBtnLoading" @ok="handleDeleteMarkdownModelOk">
    <p>是否确认删除此问答文件？</p>
  </a-modal>

  <!-- 编辑问答文件模态框 -->
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
      <a-form-item label="文件名" name="fileName">
        <a-input v-model:value="editMarkdownRecord.fileName" disabled/>
      </a-form-item>
      <a-form-item label="文件大小" name="fileSize">
        <a-input v-model:value="editMarkdownRecord.fileSize" disabled/>
      </a-form-item>
      <a-form-item label="处理状态" name="status">
        <StatusTag :status="editMarkdownRecord.status" />
      </a-form-item>
      <a-form-item label="索引状态" name="indexStatus">
        <IndexStatusTag :status="editMarkdownRecord.indexStatus" />
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

  <!-- 上传文件模态框 -->
  <a-modal v-model:open="uploadFileInfoModelOpen" width="700px" :centered=true title="文件上传" :footer="null"
    :mask-closable="!uploading" :closable="!uploading" :keyboard="!uploading">
    <div class="mt-5"></div>
    <!-- 文件信息 -->
    <a-descriptions :column="1">
      <a-descriptions-item label="所属知识库">{{ uploading ? uploadOwnerName : knowledgeBase.name }}</a-descriptions-item>
      <a-descriptions-item label="文件名">{{ selectedFile?.name }}</a-descriptions-item>
      <a-descriptions-item label="文件大小">{{ selectedFile ? filesize(selectedFile.size) : '-' }}</a-descriptions-item>
      <a-descriptions-item label="文件 MD5">
        <a-tag v-if="fileMd5" color="blue">{{ fileMd5 }}</a-tag>
        <div v-else>
          <a-spin size="small" /> 计算中...
        </div>
      </a-descriptions-item>
      <a-descriptions-item label="分片数">
        {{ totalChunks }} 片（每片 {{ filesize(CHUNK_SIZE) }}）
      </a-descriptions-item>
    </a-descriptions>

    <div class="mt-5"></div>

    <!-- 上传进度 -->
    <a-card v-if="uploading || uploadProgress > 0" size="small" title="上传进度">
      <a-progress
        :percent="uploadProgress"
        :status="uploadStatus"
        :stroke-color="{
          '0%': '#108ee9',
          '100%': '#87d068',
        }"
      />
      <a-alert
        :message="statusText"
        :type="alertType"
        show-icon
        style="margin-top: 16px"
      />
    </a-card>
    <div class="mt-5"></div>

    <!-- 上传按钮：MD5 算完之后才允许点 -->
    <a-button
      v-if="selectedFile && fileMd5"
      type="primary"
      size="large"
      block
      :loading="uploading"
      :disabled="uploading || !hasKnowledgeBase"
      @click="startUpload"
    >
      <template #icon>
        <upload-outlined />
      </template>
      {{ uploading ? '上传中...' : '开始上传' }}
    </a-button>
  </a-modal>
</template>

<script setup>
import { ref, reactive, computed, h, watch } from 'vue'
import { UploadOutlined, SearchOutlined, RedoOutlined } from '@ant-design/icons-vue'
import { message, Modal } from 'ant-design-vue'
import { filesize } from 'filesize'
import SparkMD5 from 'spark-md5'
import StatusTag from './StatusTag.vue'
import IndexStatusTag from './IndexStatusTag.vue'
import { captureUploadContext, uploadAvailability } from './knowledgeScope.js'
import { reindexKnowledgeBase } from '@/api/knowledgeBase'
import { listDocuments, publishDocumentVersion, previewDocumentChunks, withdrawDocument } from '@/api/documents'
import { listKnowledgeBaseFeedback, resolveFeedback } from '@/api/feedback'
import {
  findMarkdownFilePageList,
  checkFile,
  uploadFileChunk,
  mergeFileChunk,
  deleteMarkdownFile,
  updateMarkdownFile,
} from '@/api/customerService'

// 分片大小：2MB。
// 必须小于后端 spring.servlet.multipart.max-file-size（当前 10MB），
// 否则分片会被服务端直接拒掉（HTTP 413）。
const CHUNK_SIZE = 2 * 1024 * 1024
// 单批并发上传的分片数。太大容易把服务端连接池打满，太小又体现不出并发收益
const MAX_CONCURRENT = 3
// 单个分片的最大重试次数
const MAX_RETRY = 3

const props = defineProps({
  // 是否展示抽屉
  open: {
    type: Boolean,
    default: false,
  },
  knowledgeBase: {
    type: Object,
    required: true,
  },
  canEdit: { type: Boolean, default: true },
})

const emit = defineEmits(['update:open', 'upload-busy', 'published'])
const hasKnowledgeBase = computed(() => {
  const id = Number(props.knowledgeBase.id)
  return Number.isSafeInteger(id) && id > 0
})

// 表格加载状态
const loading = ref(false)
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
    dataIndex: 'fileName',
    key: 'fileName',
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
    title: '索引同步',
    dataIndex: 'indexStatus',
    key: 'indexStatus',
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
let listRequest = 0
const renderTableData = (pageNo, size) => {
  const requestId = ++listRequest
  if (!hasKnowledgeBase.value) {
    dataSource.value = []
    total.value = 0
    loading.value = false
    return Promise.resolve()
  }
  const ownerId = props.knowledgeBase.id
  // 设置 a-table 组件加载状态为 true
  loading.value = true

  const [startDate, endDate] = formState.startEndDate
  const startDateStr = startDate?.format('YYYY-MM-DD')
  const endDateStr = endDate?.format('YYYY-MM-DD')

  // 请求分页接口
  return findMarkdownFilePageList(pageNo, size, formState.fileName, startDateStr, endDateStr, ownerId)
    .then((res) => {
      if (requestId !== listRequest || ownerId !== props.knowledgeBase.id) return
      dataSource.value = (res.data.data || []).filter(record => Number(record.knowledgeBaseId) === Number(ownerId))
      current.value = res.data.current
      total.value = res.data.total
    })
    .catch(() => {
      // 业务失败 / 网络异常已由 axios 响应拦截器统一提示，这里只需兜住，避免未捕获的 Promise 报错
    })
    .finally(() => {
      // 设置 a-table 组件加载状态为 false
      if (requestId === listRequest) loading.value = false
    })
}

// 抽屉打开时，刷新表格数据
watch(
  () => props.open,
  (val) => {
    if (val) {
      renderTableData(current.value, pageSize.value)
      loadDocuments().catch(() => {})
      loadFeedbackQueue().catch(() => {})
    }
  }
)

watch(() => props.knowledgeBase.id, () => {
  listRequest++
  dataSource.value = []
  current.value = 1
  total.value = 0
  loading.value = false
  formState.fileName = ''
  formState.startEndDate = []
  deleteMarkdownModelOpen.value = false
  editMarkdownModelOpen.value = false
  if (!uploading.value) uploadFileInfoModelOpen.value = false
  if (props.open) renderTableData(1, pageSize.value)
  selectedDocumentId.value = undefined
  selectedVersionId.value = undefined
  documents.value = []
  if (props.open) loadDocuments().catch(() => {})
  if (props.open) loadFeedbackQueue().catch(() => {})
})

const reindexing = ref(false)
const feedbackQueue = ref([])
const feedbackLoading = ref(false)
let feedbackRequest = 0
const loadFeedbackQueue = async () => {
  if (!props.canEdit || !hasKnowledgeBase.value) {
    feedbackQueue.value = []
    return
  }
  const request = ++feedbackRequest
  const ownerId = Number(props.knowledgeBase.id)
  feedbackLoading.value = true
  try {
    const response = await listKnowledgeBaseFeedback(ownerId)
    if (request === feedbackRequest && ownerId === Number(props.knowledgeBase.id)) {
      feedbackQueue.value = (response.data.data || []).map(item => ({ ...item, resolutionDraft: item.resolutionNote || '', resolving: false }))
    }
  } finally {
    if (request === feedbackRequest) feedbackLoading.value = false
  }
}

const resolveFeedbackItem = async (item) => {
  const resolutionNote = String(item.resolutionDraft || '').trim()
  if (!resolutionNote || resolutionNote.length > 500) {
    message.warning('处理说明须为1至500个字符')
    return
  }
  item.resolving = true
  try {
    await resolveFeedback(item.id, resolutionNote)
    await loadFeedbackQueue()
    message.success('反馈已标记为已处理')
  } finally {
    item.resolving = false
  }
}

const handleReindex = async () => {
  if (!props.canEdit || !hasKnowledgeBase.value) return
  const ownerId = props.knowledgeBase.id
  reindexing.value = true
  try {
    await reindexKnowledgeBase(ownerId)
    message.success('索引重建任务已提交，请刷新查看同步状态')
    if (ownerId === props.knowledgeBase.id) renderTableData(current.value, pageSize.value)
  } catch {
    // Axios handles API errors.
  } finally {
    reindexing.value = false
  }
}

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
  if (props.canEdit && hasKnowledgeBase.value && !uploading.value) fileInput.value?.click()
}

// 是否展示上传文件模态框
const uploadFileInfoModelOpen = ref(false)

// 上传的文件
const selectedFile = ref(null)

// 文件 MD5
const fileMd5 = ref('')

// 是否正在上传中
const uploading = ref(false)
const uploadOwnerName = ref('')
const selectedDocumentId = ref()
const selectedVersionId = ref()
const documents = ref([])
const previewChunks = ref([])
const previewLoaded = ref(false)
const publishing = ref(false)
const selectedDocument = computed(() => documents.value.find(item => Number(item.id) === Number(selectedDocumentId.value)) || null)
const documentOptions = computed(() => documents.value.map(item => ({ value: Number(item.id), label: item.title })))
const versionOptions = computed(() => (selectedDocument.value?.versions || []).map(item => ({
  value: Number(item.id),
  label: `v${item.versionNo} · ${item.fileName} · ${item.publishStatus || 'DRAFT'} · ${statusLabel(item.status)} / ${item.indexStatus}`,
})))
const selectedVersion = computed(() => selectedDocument.value?.versions?.find(item => Number(item.id) === Number(selectedVersionId.value)) || null)
const canPublishSelected = computed(() => Boolean(props.canEdit && hasKnowledgeBase.value && selectedVersion.value
  && Number(selectedVersion.value.status) === 3 && selectedVersion.value.indexStatus === 'SYNCED'
  && selectedVersion.value.publishStatus !== 'PUBLISHED' && !publishing.value))
const canWithdrawSelected = computed(() => Boolean(props.canEdit && hasKnowledgeBase.value && selectedDocument.value?.currentFileId
  && Number(selectedDocument.value.currentFileId) === Number(selectedVersionId.value) && !publishing.value))

function statusLabel(status) {
  return ({ 0: '上传中', 1: '待处理', 2: '处理中', 3: '已完成', 4: '失败' })[Number(status)] || '未知'
}

const loadDocuments = async () => {
  if (!props.canEdit || !hasKnowledgeBase.value) return
  const ownerId = Number(props.knowledgeBase.id)
  const response = await listDocuments(ownerId)
  if (ownerId !== Number(props.knowledgeBase.id)) return
  documents.value = response.data.data || []
  if (!documents.value.some(item => Number(item.id) === Number(selectedDocumentId.value))) {
    selectedDocumentId.value = undefined
    selectedVersionId.value = undefined
  }
}

const selectDocument = () => {
  previewChunks.value = []
  previewLoaded.value = false
  selectedVersionId.value = selectedDocument.value?.currentFileId
      || selectedDocument.value?.versions?.[0]?.id
      || undefined
}

const loadPreview = async () => {
  if (!selectedDocument.value || !selectedVersionId.value) return
  const response = await previewDocumentChunks(selectedDocument.value.id, selectedVersionId.value)
  previewChunks.value = response.data.data || []
  previewLoaded.value = true
}

const publishSelected = async () => {
  if (!selectedDocument.value || !selectedVersionId.value || !canPublishSelected.value) return
  publishing.value = true
  try {
    await publishDocumentVersion(selectedDocument.value.id, selectedVersionId.value)
    await loadDocuments()
    emit('published')
    message.success('资料版本已发布，新问题将使用该版本')
  } finally { publishing.value = false }
}

const withdrawSelected = async () => {
  if (!selectedDocument.value || !canWithdrawSelected.value) return
  publishing.value = true
  try {
    await withdrawDocument(selectedDocument.value.id)
    await loadDocuments()
    emit('published')
    message.success('当前版本已撤回')
  } finally { publishing.value = false }
}
watch(uploading, busy => emit('upload-busy', busy), { flush: 'sync' })

// 上传进度
const uploadProgress = ref(0)

// 上传状态（控制进度条样式）：active / success / exception
const uploadStatus = ref('active')

// 展示状态进度信息
const statusText = ref('')

// 匹配到的已上传分片序号（断点续传用），只用于展示分片数
const uploadedChunksCache = ref([])

// 总分片数 = 已上传的 + 本轮待上传的
const totalChunks = computed(() => {
  if (!selectedFile.value) return 0
  return Math.ceil(selectedFile.value.size / CHUNK_SIZE)
})

// 计算提示类型
const alertType = computed(() => {
  // 错误状态
  if (uploadStatus.value === 'exception') return 'error'
  // 成功状态（只有明确标记为 success 才显示绿色，合并中也应是蓝色）
  if (uploadStatus.value === 'success') return 'success'
  // 其他状态（上传中、合并中等）显示蓝色信息提示
  return 'info'
})

// 分片读取文件计算 MD5。
// 不一次性 readAsArrayBuffer 整个文件，否则几百 MB 的文件会把浏览器内存打满。
let md5Calculation = 0
const calculateMD5 = (file) => {
  const calculation = ++md5Calculation
  // 创建 SparkMD5.ArrayBuffer 对象，用于计算 MD5
  const spark = new SparkMD5.ArrayBuffer()

  // 创建 FileReader 对象，用于读取文件
  const fileReader = new FileReader()

  // 计算分片数
  const chunks = Math.ceil(file.size / CHUNK_SIZE)

  // 当前读取的分片，从 0 开始
  let currentChunk = 0

  // 当读取完一片数据时触发
  fileReader.onload = (e) => {
    if (calculation !== md5Calculation) return
    // 将读取到的 ArrayBuffer 数据追加到 spark 对象中
    spark.append(e.target.result)
    currentChunk++

    // 还有分片没读完，继续读下一片
    if (currentChunk < chunks) {
      loadNext()
    } else {
      // 全部分片读完，得出最终 MD5
      fileMd5.value = spark.end()
      statusText.value = ''
    }
  }

  // 读取出错
  fileReader.onerror = () => {
    if (calculation !== md5Calculation) return
    fileMd5.value = ''
    message.error('MD5 计算失败，请重新选择文件')
  }

  // 读取下一分片
  const loadNext = () => {
    const start = currentChunk * CHUNK_SIZE
    // 取最小值，避免最后一片超过文件大小
    const end = Math.min(start + CHUNK_SIZE, file.size)
    fileReader.readAsArrayBuffer(file.slice(start, end))
  }

  // 开始读第一片
  loadNext()
}

// 处理文件选择
const handleFileSelect = (event) => {
  const file = event.target.files[0]

  // 清空 input 的值，确保下次选择相同文件时也能触发 change 事件
  // （不选同一个文件时路径没变，change 不会触发，用户会觉得"点了没反应"）
  event.target.value = ''

  if (!file || uploading.value || !props.canEdit || !hasKnowledgeBase.value) {
    return
  }

  // 重置上一次上传残留的状态，否则换一个文件打开模态框，还会看到上个任务的进度条
  uploadProgress.value = 0
  uploadStatus.value = 'active'
  statusText.value = ''
  uploading.value = false
  fileMd5.value = ''
  uploadedChunksCache.value = []

  // 保存本次要上传的文件
  selectedFile.value = file

  // 展示上传文件模态框
  uploadFileInfoModelOpen.value = true

  // 大文件算 MD5 需要一点时间，这里不阻塞 UI，算完自动填进模态框
  calculateMD5(file)
}

// 开始上传：秒传检查 -> 并发分批上传分片 -> 合并
const startUpload = async () => {
  // 前置校验
  if (uploading.value || !selectedFile.value || !fileMd5.value || !props.canEdit || !hasKnowledgeBase.value) return

  // Every request in this upload uses this immutable owner/file/hash snapshot.
  const upload = captureUploadContext({ file: selectedFile.value, fileMd5: fileMd5.value,
    knowledgeBaseId: props.knowledgeBase.id, documentId: selectedDocumentId.value })
  uploadOwnerName.value = props.knowledgeBase.name

  uploading.value = true
  uploadProgress.value = 0
  uploadStatus.value = 'active'

  try {
    // 1. 检查文件是否存在（秒传）
    statusText.value = '检查文件是否已存在...'
    const checkResponse = await checkFile(upload.fileMd5, upload.knowledgeBaseId)
    const checkData = checkResponse.data.data || {}

    // Existing bytes can still be processing or have failed parsing/indexing.
    if (checkData.exists && !checkData.needUpload) {
      uploadProgress.value = 100
      const availability = uploadAvailability(checkData)
      uploadStatus.value = availability.status
      statusText.value = `文件已存在。${availability.text}`
      if (availability.status === 'success') message.success(availability.text)
      else if (availability.status === 'exception') message.warning(availability.text)
      else message.info(availability.text)

      // 重新渲染列表数据
      if (upload.knowledgeBaseId === props.knowledgeBase.id) renderTableData(1, pageSize.value)
      return
    }

    // 断点续传：拿到服务端已上传的分片序号，后面跳过它们
    const uploadedChunks = Array.isArray(checkData.uploadedChunks) ? checkData.uploadedChunks : []
    uploadedChunksCache.value = uploadedChunks

    const chunks = Math.ceil(upload.file.size / CHUNK_SIZE)

    // 2. 构建待上传分片队列
    const uploadQueue = []
    for (let i = 0; i < chunks; i++) {
      // 跳过已上传的分片
      if (uploadedChunks.includes(i)) {
        continue
      }
      uploadQueue.push(i)
    }

    // 服务端已上传分片数，用于计算进度基数
    const alreadyUploaded = uploadedChunks.length

    if (uploadQueue.length === 0 && chunks !== alreadyUploaded) {
      throw new Error('分片进度异常，请删除该文件后重新上传')
    }

    statusText.value = `开始上传分片... (已上传: ${alreadyUploaded}/${chunks})`

    // 记录成功上传的分片索引。
    // 这里刻意用数组而不是 uploadedCount++ 计数：并发场景下 ++ 是「读-改-写」三步，
    // 多个异步任务可能同时读到同一个旧值，导致计数比实际少，进度条卡在 99%。
    const successUploads = []
    // 记录全部重试仍失败的分片索引
    const failedUploads = []

    // 上传单个分片，失败按指数退避重试
    const uploadChunkWithRetry = async (chunkIndex, retryCount = 0) => {
      try {
        const start = chunkIndex * CHUNK_SIZE
        const end = Math.min(start + CHUNK_SIZE, upload.file.size)
        const chunk = upload.file.slice(start, end)

        const formData = new FormData()
        formData.append('chunk', chunk)
        formData.append('fileMd5', upload.fileMd5)
        formData.append('fileName', upload.file.name)
        formData.append('fileSize', upload.file.size)
        formData.append('knowledgeBaseId', upload.knowledgeBaseId)
        if (upload.documentId !== null) formData.append('documentId', upload.documentId)
        formData.append('chunkNumber', chunkIndex)
        formData.append('totalChunks', chunks)

        await uploadFileChunk(formData)

        // 记录成功后立刻更新进度。先 push 再算 count，
        // 保证进度值一定是「已落库的分片数」，不会出现进度超前于真实进度
        successUploads.push(chunkIndex)
        const uploadedCount = alreadyUploaded + successUploads.length
        uploadProgress.value = Math.floor((uploadedCount / chunks) * 100)
        statusText.value = `上传中... ${uploadedCount}/${chunks} 分片`
      } catch (error) {
        // 未达最大重试次数，等一会儿再来
        if (retryCount < MAX_RETRY) {
          const delay = Math.min(1000 * Math.pow(2, retryCount), 5000) // 最多等 5 秒
          statusText.value = `分片 ${chunkIndex} 上传失败，重试中 (${retryCount + 1}/${MAX_RETRY})...`
          await new Promise((resolve) => setTimeout(resolve, delay))
          return uploadChunkWithRetry(chunkIndex, retryCount + 1)
        }

        // 重试用尽
        failedUploads.push(chunkIndex)
        console.error(`分片 ${chunkIndex} 上传失败，已达最大重试次数`, error)
      }
    }

    // 3. 并发分批上传：每批最多 MAX_CONCURRENT 个，一批全部结束后再进下一批
    for (let i = 0; i < uploadQueue.length; i += MAX_CONCURRENT) {
      const batch = uploadQueue.slice(i, i + MAX_CONCURRENT)
      await Promise.all(batch.map((chunkIndex) => uploadChunkWithRetry(chunkIndex)))
    }

    // 有分片最终失败，不能合并（合并出来会是一份残缺文件）
    if (failedUploads.length > 0) {
      throw new Error(`有 ${failedUploads.length} 个分片上传失败: ${failedUploads.join(', ')}`)
    }

    // 4. 合并分片。合并要顺序拼接所有分片，大文件耗时较久，超时给到 2 分钟
    statusText.value = '正在合并文件...'
    await mergeFileChunk(upload.fileMd5, 120000, upload.knowledgeBaseId)

    uploadProgress.value = 100
    uploadStatus.value = 'active'
    statusText.value = '文件上传完成，后台正在解析并同步索引，请在列表查看处理结果。'
    message.info('文件已接收，等待后台处理')

    // 重新渲染列表数据
    if (upload.knowledgeBaseId === props.knowledgeBase.id) renderTableData(1, pageSize.value)
    if (upload.knowledgeBaseId === props.knowledgeBase.id) {
      loadDocuments().catch(() => {})
      emit('published')
    }
  } catch (error) {
    console.error('上传失败:', error)
    uploadStatus.value = 'exception'
    statusText.value = '上传失败: ' + (error.response?.data?.message || error.message)
  } finally {
    uploading.value = false
  }
}

// 是否展示 “删除问答文件” 确认框
const deleteMarkdownModelOpen = ref(false)

// 被删除的文件记录 ID
const deleteMarkdownId = ref(null)
const deleteKnowledgeBaseId = ref(null)

// 展示 “删除问答文件” 确认框
const showDeleteMarkdownModel = (id) => {
  if (!props.canEdit || !hasKnowledgeBase.value) return
  // 展示确认框
  deleteMarkdownModelOpen.value = true
  // 保存被删除的文件记录 ID
  deleteMarkdownId.value = id
  deleteKnowledgeBaseId.value = props.knowledgeBase.id
}

// 执行删除。force 为 true 表示强制删除（跳过「处理中不允许删除」的限制）
const doDelete = (id, force, ownerId) => {
  if (!props.canEdit || !hasKnowledgeBase.value || Number(ownerId) !== Number(props.knowledgeBase.id)) return
  deleteBtnLoading.value = true

  return deleteMarkdownFile(id, force, { silent: true }, ownerId)
    .then(() => {
      message.success('删除成功')

      // 隐藏确认框
      deleteMarkdownModelOpen.value = false

      // 重新渲染列表数据
      if (ownerId === props.knowledgeBase.id) renderTableData(1, pageSize.value)
    })
    .catch((err) => {
      // 20005：文件正在处理中。多半是卡在向量化流程里的僵死记录，问一下要不要强制删除
      if (String(err?.errorCode) === '20005' && !force && ownerId === props.knowledgeBase.id) {
        Modal.confirm({
          title: '该文件仍在处理中',
          content: '它可能卡在了向量化流程里（例如服务中途重启过）。强制删除会同时清除已生成的向量数据，确定继续吗？',
          okText: '强制删除',
          okType: 'danger',
          cancelText: '取消',
          onOk: () => doDelete(id, true, ownerId),
        })
        return
      }

      message.warning(err?.message || '删除失败')
    })
    .finally(() => {
      deleteBtnLoading.value = false
    })
}

// “删除问答文件” 确认框确认按钮点击事件
const handleDeleteMarkdownModelOk = () => {
  doDelete(deleteMarkdownId.value, false, deleteKnowledgeBaseId.value)
}

// 是否展示 “编辑问答文件” 模态框
const editMarkdownModelOpen = ref(false)

// 被编辑的文件记录
const editMarkdownRecord = reactive({
  id: null,
  fileName: '',
  fileSize: '',
  status: null,
  indexStatus: 'NOT_READY',
  knowledgeBaseId: null,
  createTime: '',
  updateTime: '',
  remark: '',
})

// 展示 “编辑问答文件” 模态框
const showEditMarkdownModel = (record) => {
  if (!props.canEdit || !hasKnowledgeBase.value) return
  if (record.knowledgeBaseId != null && Number(record.knowledgeBaseId) !== Number(props.knowledgeBase.id)) return
  // 展示模态框
  editMarkdownModelOpen.value = true
  // 保存被编辑的文件记录（record 里正好是表单需要的这 7 个字段）
  Object.assign(editMarkdownRecord, record, { knowledgeBaseId: Number(props.knowledgeBase.id) })
}

// 编辑模态框 “提交” 按钮事件
const handleEditMarkdownModelOk = () => {
  if (!props.canEdit || !hasKnowledgeBase.value) return
  const ownerId = editMarkdownRecord.knowledgeBaseId
  if (Number(ownerId) !== Number(props.knowledgeBase.id)) return
  editBtnLoading.value = true

  updateMarkdownFile({ ...editMarkdownRecord }, ownerId)
    .then(() => {
      message.success('更新成功')

      // 隐藏模态框
      editMarkdownModelOpen.value = false

      // 重新渲染列表数据
      if (ownerId === props.knowledgeBase.id) renderTableData(current.value, pageSize.value)
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
