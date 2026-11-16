import axios from '@/axios'

export const listDocuments = knowledgeBaseId => axios.get('/customer-service/documents', { params: { knowledgeBaseId } })
export const publishDocumentVersion = (documentId, fileId) => axios.post(`/customer-service/documents/${documentId}/publish`, { fileId })
export const withdrawDocument = documentId => axios.post(`/customer-service/documents/${documentId}/withdraw`)
export const previewDocumentChunks = (documentId, fileId) => axios.get(`/customer-service/documents/${documentId}/files/${fileId}/chunks`)
