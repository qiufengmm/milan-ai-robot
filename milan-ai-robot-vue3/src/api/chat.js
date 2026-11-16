import axios from "@/axios";

// 新建对话
export function newChat(message, knowledgeBaseId) {
    return axios.post("/chat/new", {message, knowledgeBaseId})
}

// 查询历史消息分页列表
export function findChatMessagePageList(current, size, chatId) {
    return axios.post("/chat/message/list", {current, size, chatId})
}

// 查询历史对话列表
export function findHistoryChatPageList(current, size) {
    return axios.post("/chat/list", {current, size})
}

// 删除对话
export function deleteChat(uuid) {
    return axios.post("/chat/delete", {uuid})
}

// 重命名对话
export function renameChat(uuid, summary) {
    return axios.post("/chat/summary/rename", {uuid, summary})
}

export function getChat(uuid) {
    return axios.get(`/chat/${encodeURIComponent(uuid)}`)
}

export function getAnswerSources(answerId) {
    return axios.get(`/customer-service/chat/sources/${encodeURIComponent(answerId)}`)
}
