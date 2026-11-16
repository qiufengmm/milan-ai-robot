# 企业知识库平台前后端一致性静态检查

日期：2026-11-16。项目：F:/code/qiufengm-ai-robot/project。

本轮按用户澄清检查“基于企业私有知识库智能问答平台”，不是 Mall 或已清理的电商客服旧应用。Mall 仅在定位阶段读取过目录和规则，没有修改。

## 结论与范围

前后端不是“完全一致、可以直接验收”的状态。主要接口路径与数据结构能够对应，但页面提交入口、异步作用域、版本生命周期和错误反馈仍有问题。

对照了前端七个 API 模块的 35 个 HTTP 调用以及 1 个 SSE 入口，与后端九个 Controller 的 36 个映射；检查方法、路径、主要请求字段、响应封装、分页、SSE 和页面处理。计数相同本身不是通过依据，仍需逐项核对及后续验证。

本次只读源码、本地已安装的组件库实现和现有文档，没有运行测试、构建、浏览器、接口、数据库或模型请求。以下并发场景为静态可达时序分析，尚未通过运行复现。未修改项目代码；本文件是新建的审查记录。

## 已对齐的部分

| 链路 | 静态核对结果 |
|---|---|
| 开发代理 | Axios 使用 /api，Vite 去掉此前缀后代理到 8080；SSE 也使用同一 /api 前缀。 |
| 登录与 CSRF | 获取 token、登录后刷新 token、写请求携带 CSRF header，以及 Session Cookie 的设计能够对应后端。页面提交入口另有下述阻断问题。 |
| 会话 | 新建传 message/knowledgeBaseId；后续问答传 chatId/requestId/message，知识库由服务端会话反查。 |
| 分页 | 前端读取 data/current/total/pages，与 PageResponse 结构对应；历史消息的跨页逆序拼接与服务端单页按 ID 升序返回的逻辑相符。 |
| SSE | meta/sources/delta/done/error 及 answerId/text/code 等主要字段能够对应；客户端取消后的状态处理仍有缺口。 |
| 上传 | multipart 字段、MD5、分片编号和 knowledgeBaseId 对应 VO；0-4 文件状态及索引状态标签基本对应。 |
| 来源与反馈 | 来源卡片、反馈提交、本人反馈、维护者队列与处理说明的主要字段能够对应。 |
| 权限 | 前端按角色展示入口，服务端继续校验账号、知识库、会话和来源归属；不能把界面限制当作后端授权替代。 |

## F01 高优先级：登录及管理员创建表单不触发提交

位置：[登录表单](F:/code/qiufengm-ai-robot/project/milan-ai-robot-vue3/src/views/LoginPage.vue:8)、[账号创建表单](F:/code/qiufengm-ai-robot/project/milan-ai-robot-vue3/src/views/AdminAccessPage.vue:12)、[知识库创建表单](F:/code/qiufengm-ai-robot/project/milan-ai-robot-vue3/src/views/AdminAccessPage.vue:27)。

三个 a-form 使用 @finish，却没有 :model。已读取本地 ant-design-vue/es/form/Form.js：handleSubmit 只有在 props.model 存在时才调用校验并发出 finish。因此点击提交不会进入相应业务回调，不是后端路径不匹配。

最小修复：分别绑定 form、newUser、newLibrary，维持现有 reactive 对象与接口不变。随后对齐用户名、显示名和密码校验。验收应使用真实 Form 组件验证提交能触发请求，不能只检查 API 模块函数存在。

## F02 高优先级：停止回答后，旧的新建会话响应可以污染当前知识库

位置：[创建会话并回写路由](F:/code/qiufengm-ai-robot/project/milan-ai-robot-vue3/src/views/CustomerServiceChatPage.vue:232)、[完成状态赋值](F:/code/qiufengm-ai-robot/project/milan-ai-robot-vue3/src/views/CustomerServiceChatPage.vue:252)。

静态可达时序：在 A 库发起首问，新建会话尚未返回；点击停止后切到 B 库；A 的新建响应晚到，仍会写全局 chatId 并跳转路由。路由监听发现 UUID 等于刚写入的 chatId 后直接返回，可能保留 B 的界面而继续使用 A 的会话。流函数在取消后返回时，调用方还可能无条件将状态改成 COMPLETED。

这不是已证实的服务端越权，而是前端显示范围与实际会话所属库不一致。

最小修复：始终使用 reply token 捕获的库 ID；在异步边界、创建结果回写和完成状态赋值前检查 token 是否仍有效；取消的轮次保持取消状态。验收覆盖“新建响应晚到”“停止后切库”“停止后开始新会话”。

## F03 高优先级：管理员切库时，成员列表存在过期回写风险

位置：[成员加载](F:/code/qiufengm-ai-robot/project/milan-ai-robot-vue3/src/views/AdminAccessPage.vue:119)、[撤销授权](F:/code/qiufengm-ai-robot/project/milan-ai-robot-vue3/src/views/AdminAccessPage.vue:154)。

loadMembers 未捕获库 ID，也没有请求序号。A/B 请求逆序返回时，A 成员可能覆盖当前 B 列表；撤销按钮却使用当前选择的 B 库 ID，从而可能操作错误库中的同一个员工授权。

最小修复：捕获 ownerId 与请求序号，只接受最新且仍属于当前库的结果；切库时清空旧成员，加载期间禁用成员操作。验收覆盖 A/B 响应逆序和加载期间撤销按钮。

## F04 高优先级补充：校验错误会回显并记录敏感字段原值

位置：[统一参数校验错误处理](F:/code/qiufengm-ai-robot/project/milan-ai-robot-springboot/src/main/java/com/milanai/robot/exception/GlobalExceptionHandler.java:75)、[账号请求密码约束](F:/code/qiufengm-ai-robot/project/milan-ai-robot-springboot/src/main/java/com/milanai/robot/controller/UserAdminController.java:53)。

处理器无条件拼接 getRejectedValue()，随后同时返回并记录 errorMessage。创建账号的 password 校验失败时，明文原值可能进入响应与日志。这是在错误响应核对中发现的附加问题。

最小修复：错误响应只保留字段名与安全的约束说明，不回显原值；日志也不记录密码或请求凭据。先处理此问题，再完善前端结构化错误展示。验收检查无效密码不会出现在响应和日志中。

## F05 中优先级：发布、删除按钮与服务端生命周期规则不一致

位置：[前端发布条件](F:/code/qiufengm-ai-robot/project/milan-ai-robot-vue3/src/components/customer-service/MarkdownFileDrawer.vue:512)、[删除按钮](F:/code/qiufengm-ai-robot/project/milan-ai-robot-vue3/src/components/customer-service/MarkdownFileDrawer.vue:89)、[后端发布规则](F:/code/qiufengm-ai-robot/project/milan-ai-robot-springboot/src/main/java/com/milanai/robot/service/KnowledgeDocumentService.java:116)。

前端对“处理完成、索引已同步、不是 PUBLISHED”的版本都显示发布按钮，包含 ARCHIVED；后端只接受 DRAFT 或最新的 WITHDRAWN。删除按钮也未按 DRAFT 限制，用户可点击当前/历史版本后才收到拒绝。

最小修复：界面条件跟随后端现有规则，不扩大为历史版本回滚功能。保留后端最终校验。验收检查 DRAFT、PUBLISHED、ARCHIVED、最新/非最新 WITHDRAWN 的操作矩阵。

## F06 中优先级：解析片段预览没有完整隔离文档、版本与知识库

位置：[预览请求](F:/code/qiufengm-ai-robot/project/milan-ai-robot-vue3/src/components/customer-service/MarkdownFileDrawer.vue:542)、[库切换清理](F:/code/qiufengm-ai-robot/project/milan-ai-robot-vue3/src/components/customer-service/MarkdownFileDrawer.vue:379)。

loadPreview 返回后直接覆盖 previewChunks；版本选择变化没有主动清空预览，切库清理也未清空已有 previewChunks/previewLoaded。旧版本请求晚到或切库后可能在新选择下显示旧片段。

最小修复：预览绑定库 ID、文档 ID、版本 ID 与请求序号；任一选择变化立即清空；只接收仍匹配的结果。验收覆盖 v1/v2 逆序响应和 A/B 切库。

## F07 中优先级：HTTP 错误分支丢失后端业务说明和错误码

位置：[Axios 非 2xx 分支](F:/code/qiufengm-ai-robot/project/milan-ai-robot-vue3/src/axios.js:56)、[后端状态异常响应](F:/code/qiufengm-ai-robot/project/milan-ai-robot-springboot/src/main/java/com/milanai/robot/exception/GlobalExceptionHandler.java:41)。

后端 400/403/409 等可能返回结构化 message/errorCode，前端只显示 HTTP 状态，没有统一提取业务说明与 errorCode。例如重名账号、不能发布历史版本，用户只能看到泛化报错。当前“仅识别 HTTP 200 中业务失败”的处理不覆盖这些情况。

最小修复：在后端敏感值回显修复后，规范化可信的业务错误；保留 401 登录失效处理、silent 约定和网络/5xx 的安全通用提示，不直接展示堆栈或原始请求值。

## F08 中优先级：清空日期后直接解构可能中断文件查询

位置：[日期筛选解构](F:/code/qiufengm-ai-robot/project/milan-ai-robot-vue3/src/components/customer-service/MarkdownFileDrawer.vue:346)。

日期选择器清空值可能为 null，而 renderTableData 直接解构 startEndDate。此时可在发起请求前抛错，且 loading 已设为 true，无法走后面的 finally 复位。

最小修复：将日期值规范成数组后再读取，空日期不传开始/结束筛选；不改变后端 LocalDate 契约。此项尚未运行 UI 复现。

## F09 中优先级：产品上传入口允许后端不能解析的视频格式

位置：[文件选择器](F:/code/qiufengm-ai-robot/project/milan-ai-robot-vue3/src/components/customer-service/MarkdownFileDrawer.vue:80)、[后端格式分派](F:/code/qiufengm-ai-robot/project/milan-ai-robot-springboot/src/main/java/com/milanai/robot/event/listener/AiCustomerServiceMdUploadedListener.java:198)。

前端仍允许 mp4/mov/avi，注释说明用于分片测试；企业知识库后端只支持 Markdown、PDF、Word、TXT。选择这些视频后，即使分片成功也会解析失败。

最小修复：企业资料入口只展示实际支持的格式；大文件分片验证样例留在测试资料，不能混入正常产品能力描述。不增加音视频解析功能。

## 其他需对齐的细节

- 问答输入框没有与服务端 4000 字符上限对应的限制；超过上限后前端先清空输入，再收到参数错误，应补输入提示并保留失败时的问题。
- 管理员表单用户名模式、显示名长度和密码字符/UTF-8 字节约束缺少对应提示，应以现有后端规则为准。
- 上传和索引就绪的文案应明确“还需发布才参与新问答”；不得把处理完成等同已发布。
- 知识库编辑 API 已有，但主问答页面把选择器管理入口关闭，管理员页目前只有创建表单。若验收要求管理员从页面编辑名称/说明，需要单独确认这个入口的范围，不能把“有 API”当作“完整 UI 已提供”。

## 推荐修复顺序与边界

1. 修复三处表单绑定和校验错误敏感原值处理。
2. 修复问答取消、成员列表和版本预览的作用域/过期响应问题。
3. 对齐发布/删除按钮、HTTP 错误、空日期和支持格式。
4. 再执行有针对性的前端交互验证与契约校验；本轮未运行。

不改数据库结构、模型配置、检索算法或旧路由命名，不新增订单、审批、工单、音视频或历史版本回滚能力。工作区原有大量未提交改动，本轮不重置、不提交、不推送。
