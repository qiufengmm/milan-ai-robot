import axios from '@/axios'

export const submitAnswerFeedback = (answerId, description) => axios.post(`/customer-service/chat/feedback/${answerId}`, { description })
export const listMyFeedback = () => axios.get('/customer-service/chat/feedback/mine')
export const listKnowledgeBaseFeedback = knowledgeBaseId => axios.get(`/customer-service/chat/feedback/knowledge-bases/${knowledgeBaseId}`)
export const resolveFeedback = (feedbackId, resolutionNote) => axios.post(`/customer-service/chat/feedback/${feedbackId}/resolve`, { resolutionNote })
