# 企业知识库前端

Vue 3 + Vite 的员工知识问答界面，包含登录、按授权库问答、本人会话历史、来源卡片、资料版本管理、反馈和管理员账号授权页面。Pinia 用于登录状态；问答模型和检索参数由后端配置。

## 本地开发

先按 [项目 README](../README.md) 启动企业后端并初始化管理员，再在本目录运行：

```powershell
npm install
npm run dev
```

默认访问 `http://localhost:5173`。路由使用 Hash 模式，入口包括 `/#/login`、`/#/customer-service/chat` 和管理员的 `/#/admin/access`。资料管理和反馈在问答界面中打开，操作权限由后端按知识库校验。

## API 与代理

普通 HTTP 请求使用 `src/axios.js` 中的固定 `/api` 基址，SSE 请求为 `/api/customer-service/chat/completion`。当前没有配置 API 地址的 `VITE_*` 环境变量。

`vite.config.js` 的开发代理将 `/api/**` 转发到 `http://localhost:8080`，并移除 `/api` 前缀。后端地址不同时修改该代理的 `target`。登录使用 Session Cookie，写请求使用 CSRF token；会话过期会回到登录页。

问答请求仅发送 `chatId / requestId / message`。知识库从会话读取；文件列表、上传和管理操作必须显式传入已授权 `knowledgeBaseId`，未选库时应先选择知识库。

## 检查与构建

```powershell
npm test
npm run build -- --emptyOutDir=false
npm run preview
```

`npm test` 运行当前 Node 离线测试；构建输出位于 `dist/`。`--emptyOutDir=false` 保留已有目录内容，因此输出路径应先由用户准备为空目录，避免旧文件混入交付。`npm run preview` 只用于本地查看构建页面。

生产部署时，将静态页面和 `/api` 放在同一站点，由反向代理将 `/api/**` 去掉前缀后转发至后端；Vite 的开发代理不包含在构建产物中。SSE 路径应支持持续连接并关闭响应缓冲，Cookie 和 CSRF 请求头需正常转发。

## 相关说明

- [后端配置、模型与接口](../README.md)
- [虚构企业语料、导入和历史评测](../test-data/enterprise/README.md)

资料中的员工政策与 IT 指引均为演示内容；界面不接入真实审批、账号重置或工单操作。
