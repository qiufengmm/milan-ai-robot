import { defineStore } from 'pinia'
import * as authApi from '@/api/auth'

export const useAuthStore = defineStore('enterprise-auth', {
  state: () => ({ user: null, initialized: false, initializing: null }),
  actions: {
    async initialize() {
      if (this.initialized) return this.user
      if (this.initializing) return this.initializing
      this.initializing = authApi.currentUser()
        .then(async user => { await authApi.refreshCsrf(); this.user = user; return user })
        .catch(() => { this.user = null; return null })
        .finally(() => { this.initialized = true; this.initializing = null })
      return this.initializing
    },
    async signIn(credentials) {
      this.user = await authApi.login(credentials)
      this.initialized = true
      return this.user
    },
    async signOut() {
      try { await authApi.logout() }
      finally { this.user = null; this.initialized = true }
    },
    clear() {
      this.user = null
      this.initialized = false
      this.initializing = null
    },
  },
})
