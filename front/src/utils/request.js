import axios from "axios"
import { ElMessage } from "element-plus"
import { clearAuth, getToken } from "@/utils/auth"

const request = axios.create({
  baseURL: "/api",
  timeout: 5000
})

request.interceptors.request.use(config => {
  const token = getToken()
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
}, error => Promise.reject(error))

request.interceptors.response.use(
  response => response.data,
  error => {
    const status = error?.response?.status
    if (status === 401) {
      clearAuth()
      if (window.location.pathname !== "/login") {
        window.location.href = "/login"
      }
    } else if (status === 403) {
      ElMessage.error("无权限访问该页面")
      if (window.location.pathname !== "/403") {
        window.location.href = "/403"
      }
    } else {
      ElMessage.error(error?.response?.data?.msg || "网络异常，请稍后重试")
    }
    return Promise.reject(error)
  }
)

export default request
