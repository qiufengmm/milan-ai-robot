let csrfToken = null
let csrfHeader = 'X-CSRF-TOKEN'

export function setCsrfToken(value, headerName = 'X-CSRF-TOKEN') {
  csrfToken = value || null
  csrfHeader = headerName || 'X-CSRF-TOKEN'
}

export function getCsrfHeader() {
  return csrfToken ? { [csrfHeader]: csrfToken } : {}
}

export function clearCsrfToken() {
  csrfToken = null
  csrfHeader = 'X-CSRF-TOKEN'
}
