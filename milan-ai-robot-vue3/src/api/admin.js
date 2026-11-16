import axios from '@/axios'

export const listUsers = () => axios.get('/admin/users')
export const createUser = (data) => axios.post('/admin/users', data)
export const setUserEnabled = (id, enabled) => axios.patch(`/admin/users/${id}/enabled`, { enabled })
export const listKnowledgeBaseMembers = (knowledgeBaseId) => axios.get(`/admin/knowledge-bases/${knowledgeBaseId}/members`)
export const assignKnowledgeBaseMember = (knowledgeBaseId, data) => axios.put(`/admin/knowledge-bases/${knowledgeBaseId}/members`, data)
export const revokeKnowledgeBaseMember = (knowledgeBaseId, userId) => axios.delete(`/admin/knowledge-bases/${knowledgeBaseId}/members`, { params: { userId } })
