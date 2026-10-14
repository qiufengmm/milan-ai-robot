import axios from "@/axios";

// 查询 Markdown 问答文件分页列表
export function findMarkdownFilePageList(current, size, fileName, startDate, endDate) {
    return axios.post("/customer-service/md/list", { current, size, fileName, startDate, endDate })
}

// 上传 Markdown 问答文件
export function uploadMarkdownFile(form) {
    return axios.post("/customer-service/md/upload", form)
}

// 删除 Markdown 问答文件
// force：为 true 时强制删除，用于清理卡在「待处理 / 向量化中」的僵死记录
// options：可传 { silent: true }，表示业务错误由调用方自己处理，拦截器不自动弹提示
export function deleteMarkdownFile(id, force = false, options = {}) {
    return axios.post("/customer-service/md/delete", { id, force }, options)
}

// 修改 Markdown 问答文件
export function updateMarkdownFile(record) {
    return axios.post("/customer-service/md/update", record)
}
