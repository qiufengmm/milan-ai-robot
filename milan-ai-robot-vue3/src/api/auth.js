import axios from '@/axios'
import { setCsrfToken, clearCsrfToken } from '@/auth/csrfState'

export async function refreshCsrf() {
  const response = await axios.get('/auth/csrf', { silent: true })
  const { token, headerName } = response.data.data
  setCsrfToken(token, headerName)
  return token
}

export async function login({ username, password }) {
  await refreshCsrf()
  const response = await axios.post('/auth/login', { username, password }, { silent: true })
  clearCsrfToken()
  await refreshCsrf()
  return response.data.data
}

export async function currentUser() {
  const response = await axios.get('/auth/me', { silent: true })
  return response.data.data
}

export async function logout() {
  try {
    await axios.post('/auth/logout', {}, { silent: true })
  } finally {
    clearCsrfToken()
  }
}
