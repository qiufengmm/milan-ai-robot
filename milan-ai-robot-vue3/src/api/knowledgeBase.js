import axios from '@/axios'

export function listKnowledgeBases() {
  return axios.get('/customer-service/knowledge-bases')
}

export function createKnowledgeBase({ name, description }) {
  return axios.post('/customer-service/knowledge-bases', { name, description })
}

export function updateKnowledgeBase(id, { name, description }) {
  return axios.put(`/customer-service/knowledge-bases/${id}`, { name, description })
}

export function reindexKnowledgeBase(id) {
  return axios.post(`/customer-service/knowledge-bases/${id}/reindex`)
}
