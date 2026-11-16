import axios from "@/axios";
import { knowledgeBaseId as requireKnowledgeBaseId } from '../components/customer-service/knowledgeScope.js'

// 查询问答文件分页列表
export function findMarkdownFilePageList(current, size, fileName, startDate, endDate, knowledgeBaseId) {
    knowledgeBaseId = requireKnowledgeBaseId(knowledgeBaseId)
    return axios.post("/customer-service/file/list", { current, size, fileName, startDate, endDate, knowledgeBaseId })
}

// 检查文件是否存在（秒传 / 断点续传）
export function checkFile(fileMd5, knowledgeBaseId) {
    knowledgeBaseId = requireKnowledgeBaseId(knowledgeBaseId)
    return axios.post("/customer-service/file/check", { fileMd5, knowledgeBaseId })
}

// 上传问答文件分片
// 默认超时 30s：axios 实例默认只有 7s，上传 2MB 分片在网络较慢时会超时
export function uploadFileChunk(form, timeout = 30000) {
    requireKnowledgeBaseId(form.get('knowledgeBaseId'))
    return axios.post("/customer-service/file/upload-chunk", form, {
        timeout: timeout // 自定义超时时间，默认 30s
    })
}

// 合并问答文件分片
// 合并是把所有分片顺序拼成一个大文件，大文件可能耗时较久，默认 30s，调用方可按需加长
export function mergeFileChunk(fileMd5, timeout = 30000, knowledgeBaseId) {
    knowledgeBaseId = requireKnowledgeBaseId(knowledgeBaseId)
    return axios.post("/customer-service/file/merge-chunk", { fileMd5, knowledgeBaseId }, {
        timeout: timeout // 自定义超时时间，默认 30s
    })
}

// 删除问答文件
// force：为 true 时强制删除，用于清理卡在「待处理 / 向量化中」的僵死记录
// options：可传 { silent: true }，表示业务错误由调用方自己处理，拦截器不自动弹提示
export function deleteMarkdownFile(id, force = false, options = {}, knowledgeBaseId) {
    knowledgeBaseId = requireKnowledgeBaseId(knowledgeBaseId)
    return axios.post("/customer-service/file/delete", { id, force, knowledgeBaseId }, options)
}

// 修改问答文件
export function updateMarkdownFile(record, knowledgeBaseId) {
    knowledgeBaseId = requireKnowledgeBaseId(knowledgeBaseId)
    return axios.post("/customer-service/file/update", { ...record, knowledgeBaseId })
}
