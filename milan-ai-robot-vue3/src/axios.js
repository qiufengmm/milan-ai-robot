import axios from "axios";
import { message } from "ant-design-vue";

// 创建 Axios 实例
const instance = axios.create({
    baseURL: "/api", // 你的 API 基础 URL
    timeout: 7000, // 请求超时时间
})

/**
 * 响应拦截器：把「请求失败」和「业务失败」统一在这里处理掉，
 * 这样各个调用方只需要关心成功分支，不用每次都写一遍
 * `if (!res.data.success) { message.warning(res.data.message); return }`。
 *
 * 约定：
 * - 失败时拦截器会 reject，调用方用 .catch() 兜一下即可（通常只写 .catch(() => {})）。
 * - reject 出来的 Error 上带了 `errorCode` 字段，便于调用方按错误码分支处理。
 * - 若某个请求想自己处理业务错误（不要拦截器自动弹提示），
 *   调用时传第 3 个参数 `{ silent: true }` 即可。
 */
instance.interceptors.response.use(
    // HTTP 状态码为 2xx
    (response) => {
        const res = response.data

        // 后端统一响应体形如 { success, message, errorCode, data }
        // 若返回的不是这个结构（例如文件流），直接放行，不做业务判断
        if (!res || typeof res !== 'object' || !('success' in res)) {
            return response
        }

        // 业务失败：构造带错误码的 Error，并中断后续 then 链
        if (res.success === false) {
            const err = new Error(res.message || '请求失败')
            err.errorCode = res.errorCode
            err.response = response

            // silent 为 true 时由调用方自行提示
            if (!response.config?.silent) {
                message.warning(err.message)
            }

            return Promise.reject(err)
        }

        return response
    },
    // HTTP 状态码非 2xx，或网络异常、超时
    (error) => {
        if (!error.config?.silent) {
            let tip = '网络异常，请稍后重试'

            if (error.code === 'ECONNABORTED' || error.code === 'ETIMEDOUT') {
                tip = '请求超时，请稍后重试'
            } else if (error.response) {
                tip = `请求失败（HTTP ${error.response.status}）`
            }

            message.error(tip)
        }

        return Promise.reject(error)
    }
)

// 暴露出去
export default instance;
