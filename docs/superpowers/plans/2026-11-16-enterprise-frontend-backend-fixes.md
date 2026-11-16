# 企业知识库平台前后端一致性修复计划

> **For agentic workers:** 后续实施时使用 `executing-plans` 技能，按任务顺序执行和核验。本文使用 `- [ ]` 跟踪实施状态；编写本计划不代表修复已完成，不自动启动实施或委派子代理。

**Goal:** 在现有企业员工服务与 IT 支持流程内，修复前后端交互、异步状态和业务规则表达的九项问题，补齐三个直接相关的输入与文案缺口。

**Architecture:** 保留现有 Vue 3 页面、Axios / SSE 通信、Spring Security 和企业业务服务。优先修复后端校验错误泄露，再恢复表单入口；前端使用已有作用域令牌、请求序号和业务状态字段约束异步回写，不引入新的业务系统或通用状态框架。

**Tech Stack:** Java 21、Spring Boot 4.1.0、Spring AI 2.0.1、Spring Security、MyBatis-Plus、PostgreSQL / pgvector、Elasticsearch、Vue 3、Ant Design Vue、Axios、Node 内置测试工具、JUnit / Mockito。

**Spec:** [企业知识库设计方案](../specs/2026-11-15-enterprise-knowledge-assistant-design.md)、[企业平台实施计划](2026-11-15-enterprise-knowledge-assistant.md)。本轮问题依据为[前后端一致性静态检查报告](../../reviews/2026-11-16-enterprise-frontend-backend-review.md)。

日期：2026-11-16。工作区：`F:/code/qiufengm-ai-robot/project`。

## 全局约束

- 本轮交付仅为计划文档；未修改业务代码，未执行测试、构建、浏览器、接口、数据库或模型请求。
- 后续修复仍围绕“管理员分配账号与权限 → HR / IT 上传发布资料 → 员工提问追问并反馈 → 维护者处理反馈”的现有流程。
- 保留单体、单实例架构；全局身份为 `ADMIN / USER`，知识库权限为 `READER / EDITOR`。前端按钮控制不替代服务端授权。
- 保留 Java 21、Spring Boot 4.1.0、Spring AI 2.0.1 和现有前端依赖；不以本轮修复为由升级框架、引入测试框架或重构公共架构。
- 不改数据库结构、物理表名、ES 索引、模型配置、检索算法、接口路径或已有响应字段；不恢复旧通用机器人、电商、联网搜索功能。
- 草稿不得进入员工问答；只有当前发布版本用于新问题检索。保留现有发布、撤回和草稿删除规则，不新增历史版本回滚或自动发布。
- 保留 Session、CSRF、BCrypt、会话归属校验及知识库权限校验。问题上限仍为 4000，历史窗口及输出限制不在本次调整范围。
- 不新增 SSO、租户、部门树、OA / 工单操作、音视频解析、原文件下载中心或新的管理页面。
- 工作区已有大量未提交改动，实施前只记录基线，不重置、不自动暂存、提交或推送；不删除现有资料。禁止批量删除文件或目录。
- 后续验证仅针对本计划涉及的缺陷。不会据此重新运行 60 题评测、调用付费模型或批量上传资料。
- 静态源码证据与运行复现分开记录。尚未运行的并发时序不能写成已复现，计划中的验收不能写成已通过。

## 一、问题情况、原因与处理结论

目前主要接口方法、路径和主要字段能够对应，问题集中在页面提交、异步回写、状态判断和错误处理。下表沿用检查报告编号，便于逐项追踪。

| 编号 | 优先级与情况 | 造成原因 | 最小解决方案 | 对应任务 |
| --- | --- | --- | --- | --- |
| F01 | 高：登录、创建员工、创建知识库表单无法进入提交回调 | 三个 `a-form` 监听 `finish` 却未绑定 `model`；本地组件库只有存在 model 时才执行校验并发出 finish | 绑定现有表单对象，按现有后端约束完善创建表单校验，用真实 Form 组件确认提交链路 | T02 |
| F02 | 高：停止首轮回答后，旧的新建会话响应可能覆盖新知识库；取消可能被显示为完成 | 流回调有令牌校验，但 `newChat` 返回后的全局状态写入及最终 COMPLETED 赋值没有同样校验 | 捕获本轮知识库，在异步边界核验令牌；过期响应不改 chatId、路由或新轮次状态，取消保持 CANCELLED | T04 |
| F03 | 高：管理员切库后可能显示旧成员，并将撤销操作发给错误库 | 成员请求未捕获库归属和请求序号；按钮使用当前选库，列表却可能来自旧库 | 请求序号加归属标记，切库清空旧列表；加载时禁用操作，写操作捕获目标库 | T05 |
| F04 | 高：无效密码可能进入错误响应及日志 | 校验处理器将所有字段的 rejectedValue 拼入错误说明，再返回并记录 | 所有字段统一去掉拒绝原值；响应只含字段和安全约束说明，日志只记安全字段与错误码 | T01 |
| F05 | 中：历史版本可见发布按钮，非草稿可见删除按钮，点击后被后端拒绝 | 前端只判断“不是 PUBLISHED”，未实现后端的 DRAFT / 最新 WITHDRAWN 规则，删除也未判断 DRAFT | 按现有后端生命周期计算按钮和事件入口条件，保留服务端最终判断 | T07 |
| F06 | 中：选择新版本或切库后可能显示旧解析片段 | 预览请求没有库、文档、版本与请求序号联合校验；切换未完整清理预览 | 选择变化立即失效旧请求并清空预览，只接纳当前三元归属的最新结果 | T06 |
| F07 | 中：HTTP 400 / 403 / 409 的业务说明与错误码丢失 | Axios 非 2xx 分支只按 HTTP 状态给出泛化提示，没有提取统一响应体 | 在 F04 修复后规范化结构化业务错误；保留 401、silent、网络 / 超时及 5xx 通用提示 | T03 |
| F08 | 中：清空日期可能导致文件查询抛错、加载状态不复位 | 日期可能为 null，却直接解构；格式化发生在 Promise 的 finally 保护之外 | 空日期规范为数组，格式化和请求纳入 try / finally，空条件不传筛选日期 | T06 |
| F09 | 中：上传入口允许视频，上传后无法解析 | 产品文件选择器保留了分片测试用 mp4 / mov / avi；后端没有对应 Reader | 只允许现有 Markdown / PDF / Word / TXT；选择时校验扩展名，不新增视频解析 | T08 |

证据状态：F01 已核对本地 Ant Design Vue 实现；其余问题均有对应源码依据。F02、F03、F06 的并发后果及 F08 的实际组件交互尚未运行复现。F04 已确认存在原值拼接与记录路径，但未使用真实密码请求重现。

### 同批处理的三个小项

| 编号 | 当前情况与原因 | 方案与边界 | 对应任务 |
| --- | --- | --- | --- |
| A01 | 问题输入没有对应 4000 上限，组件与页面都会清空草稿，校验 / 请求失败容易丢失问题 | 由父页面统一决定接受和清空时机；拒绝输入保持原文，失败恢复不覆盖新草稿 | T04 |
| A02 | 创建员工表单只有 required，未体现用户名格式、显示名长度及密码约束 | 以控制器和服务当前共同生效的约束为准，不提高或放宽密码策略 | T02 |
| A03 | 上传及索引就绪文案可能让人误以为已参与问答 | 明确“处理及索引就绪后仍需发布”；不自动发布，也不把就绪状态改成发布状态 | T08 |

### 单独记录、不自动实施

管理员修改知识库名称 / 说明的 API 已有，但当前管理员页面只提供创建入口。这属于页面能力覆盖缺口，不能把“已有 API”描述成“已有完整编辑页面”。原范围只要求创建和授权，本次不新增编辑页面；后续只有明确要求这一操作时再单独确定方案。

## 二、影响文件与职责

所有表内路径相对项目根目录；实施时限定在这些文件，发现必须扩大修改范围时先记录原因。

| 类型 | 文件 | 本次职责 |
| --- | --- | --- |
| 修改 | `milan-ai-robot-springboot/src/main/java/com/milanai/robot/exception/GlobalExceptionHandler.java` | 删除校验原值回显和日志泄露 |
| 修改 | `milan-ai-robot-vue3/src/views/LoginPage.vue` | 绑定登录表单模型 |
| 修改 | `milan-ai-robot-vue3/src/views/AdminAccessPage.vue` | 创建表单、输入校验、成员列表归属及写操作保护 |
| 修改 | `milan-ai-robot-vue3/src/axios.js` | 安全地保留非 2xx 业务说明和错误码 |
| 修改 | `milan-ai-robot-vue3/src/views/CustomerServiceChatPage.vue` | 保护新建会话回写、取消状态和问题草稿 |
| 修改 | `milan-ai-robot-vue3/src/components/ChatInputBox.vue` | 输入上限提示，取消组件自行清空草稿 |
| 修改 | `milan-ai-robot-vue3/src/components/customer-service/MarkdownFileDrawer.vue` | 预览归属、空日期、操作状态及文件格式 |
| 修改 | `milan-ai-robot-vue3/src/components/customer-service/knowledgeScope.js` | 仅调整上传就绪提示；复用现有回答作用域，不扩大成通用框架 |
| 扩展 | `milan-ai-robot-springboot/src/test/java/com/milanai/robot/exception/GlobalExceptionHandlerSecurityTest.java` | 响应和日志不含拒绝原值的回归验证 |
| 扩展 | `milan-ai-robot-vue3/tests/chatInput.test.js` | 上限、父页面清空职责、IME 与换行行为 |
| 扩展 | `milan-ai-robot-vue3/tests/markdownFileScope.test.js` | 日期、预览、发布 / 删除矩阵、文件选择 |
| 按需扩展 | `milan-ai-robot-vue3/tests/knowledgeScope.test.js` | 上传就绪结果约定；不为纯文案编写逐字匹配测试 |
| 新增 | `milan-ai-robot-vue3/tests/adminAccess.test.js` | 管理员表单处理和成员逆序响应 |
| 新增 | `milan-ai-robot-vue3/tests/apiErrors.test.js` | 实际 Axios 拦截器的错误契约 |
| 新增 | `milan-ai-robot-vue3/tests/chatLifecycle.test.js` | 页面新建会话、停止及失败草稿的异步时序 |
| 实施后更新 | `docs/reviews/2026-11-16-enterprise-frontend-backend-review.md` | 追加每项修复、验证结果与剩余限制，不删除原始审查证据 |

只读对照文件包括 `UserAdminController.java`、`UserAdminService.java`、`KnowledgeDocumentService.java`、`FindMarkdownFilePageListRspVO.java`、`EnterpriseNewChatReqVO.java`、`EnterpriseChatCompletionReqVO.java` 和上传解析监听器；它们的现有业务规则不需要为适配页面而改变。

## 三、实施顺序和检查点

顺序：T01 敏感值 → T02 表单 → T03 HTTP 错误 → T04 问答 → T05 授权成员 → T06 查询与预览 → T07 生命周期 → T08 上传 → T09 收尾核验。

- T03 必须在 T01 之后，避免更完整地向用户展示仍含敏感值的后端错误。
- T02 与 T05 修改同一页面，T06 / T07 / T08 修改同一抽屉；顺序处理，避免互相覆盖。
- 每个任务先固定缺陷证据，再做最小修改，随后只运行相关验证。没有复现的场景，先用可控延迟建立复现。
- 不因单项失败重新运行全部问答评测。失败记录必须区分断言失败、依赖缺失、环境启动失败与业务缺陷。

以下代码用于明确修复逻辑与验证断言，不是本轮已经写入业务文件的代码。所有命令均为后续实施时执行，本次没有运行。

### T01：去除校验错误中的敏感原值（F04）

**问题及原因：** `GlobalExceptionHandler.handleMethodArgumentNotValidException` 对所有字段调用 `getRejectedValue()`，将内容同时写入响应和 warn 日志。创建员工的密码触发长度校验时会走这条路径。

**文件：** 修改 `GlobalExceptionHandler.java`，扩展 `GlobalExceptionHandlerSecurityTest.java`；只读 `UserAdminController.CreateUserRequest`。

**接口约定：** 保持 HTTP 400、现有 `PARAM_NOT_VALID` 错误码和 `Response` 字段不变。安全响应只含字段名与约束说明；日志不含原值，不打印 BindingResult、异常参数或完整请求体。

- [ ] 在现有安全测试中构造字段校验异常，使用明显的虚构标记 `S3cr3t!` 作为拒绝原值，同时覆盖 password 和普通字段；断言响应 message 不含标记，也不含“当前值”。
- [ ] 捕获该处理器的日志，断言至少记录一次校验失败但不含标记。项目使用 Log4j2：使用测试内存 Appender 捕获 `LogEvent.getMessage().getFormattedMessage()`，在 finally 中移除 Appender、停止并恢复 logger 级别；不另加 Logback 依赖。
- [ ] 运行这一个测试类，确认原实现因包含原值而失败，再修改处理器。
- [ ] 去掉原值拼接；为没有字段错误的情况给出固定兜底，日志仅记录 URI、错误码和字段名称。检查现有约束消息，不使用包含 `${validatedValue}` 的消息模板。

核心处理方向：

```java
String errorMessage = bindingResult.getFieldErrors().stream()
        .map(error -> error.getField() + " " +
                Optional.ofNullable(error.getDefaultMessage()).orElse("参数不合法"))
        .collect(java.util.stream.Collectors.joining("; "));
if (errorMessage.isBlank()) errorMessage = "请求参数不合法";
log.warn("{} request rejected, errorCode: {}, fields: {}", request.getRequestURI(),
        errorCode, bindingResult.getFieldErrors().stream()
                .map(org.springframework.validation.FieldError::getField).distinct().toList());
return ResponseEntity.badRequest().body(Response.fail(errorCode, errorMessage));
```

测试的必要断言：

```java
assertThat(response.getStatusCode().value()).isEqualTo(400);
assertThat(response.getBody().isSuccess()).isFalse();
assertThat(response.getBody().getMessage()).contains("password").doesNotContain("S3cr3t!");
```

- [ ] 再运行 `mvn -f milan-ai-robot-springboot/pom.xml "-Dtest=GlobalExceptionHandlerSecurityTest" test`，确认原有 403 / 409 断言与新增脱敏断言通过。该测试不启动数据库或模型。

**完成标准：** 响应、格式化后的日志均不包含被拒绝原值；不是只在 UI 隐藏密码；原 HTTP 状态和响应字段不变。

### T02：恢复三处表单提交并对齐创建账号规则（F01、A02）

**问题及原因：** `@finish` 依赖 Form 的模型绑定；当前三个入口没有绑定。仅调用 setup 中 submit 函数的测试无法发现这个组件层面的阻断。

**文件：** `LoginPage.vue`、`AdminAccessPage.vue`、新增 `tests/adminAccess.test.js`。不改登录 / 创建接口或后端密码策略。

**接口约定：** `auth.signIn(form)`、`createUser(newUser)`、`createKnowledgeBase(newLibrary)` 继续使用已有字段。登录页只校验必填，不把新建账号的密码复杂度限制加到登录入口。

- [ ] 记录修复前真实 Form 的行为：填写合法输入并点击提交，Network 中没有相应业务请求；不能以直接调用 submit 代替。
- [ ] 给三处 Form 绑定已经存在的对象，不重新建立第二份状态。

```vue
<a-form :model="form" layout="vertical" @finish="submit">
<a-form :model="newUser" layout="inline" @finish="submitUser">
<a-form :model="newLibrary" layout="inline" @finish="submitLibrary">
```

- [ ] 为创建员工增加与后端一致的规则：账号为 `[A-Za-z0-9._-]{3,80}`；显示名去掉首尾空白后非空且不超过 120；密码不 trim、不截断，非空白、长度至少 12，UTF-8 编码不超过 72 字节。知识库创建沿用现有名称 100、说明 500 的限制。

密码校验要同时满足当前控制器的字符串长度约束和服务的字节约束；不能仅照搬服务的“12 字节”而允许少于 12 个字符串长度单位的中文密码。前端字符串 `.length` 与后端 `@Size` 在此按同样的 UTF-16 长度单位计数。

```js
// 放在 AdminAccessPage.vue 的创建表单规则中；密码原文原样传递。
const validInitialPassword = value => typeof value === 'string'
  && value.trim().length > 0 && value.length >= 12
  && new TextEncoder().encode(value).length <= 72
```

- [ ] 在新增页面测试中调用真实的表单规则验证器，确认非法账号、空白显示名和非法密码返回校验失败；通过 `loadVueSetup` 加 mock API 检查有效提交字段及失败分支不显示创建成功。setup 测试不负责替 Form 触发验证，“非法输入产生零业务请求”必须由后续真实 Form 交互核验，不能仅直接调用 submitUser 得出这一结论。
- [ ] 密码边界覆盖 ASCII 11 / 12 / 72 / 73 个字符，中文 11 / 24 / 25 个字符；预期依次为拒绝 / 允许 / 允许 / 拒绝，中文分别为拒绝 / 允许 / 拒绝。校验不得悄悄 trim 密码。
- [ ] 运行 `node --test tests/adminAccess.test.js`（工作目录为前端目录）；再用真实 Form 验收三个提交入口，合法输入产生一次预期请求，校验不通过时零请求。

**完成标准：** 实际点击能触发对应请求；非法值在页面解释清楚，服务端校验仍保留。只测 setup 或检查模板字符串不能独立作为 F01 通过依据。

### T03：保留可信的业务错误说明和错误码（F07）

**前置条件：** T01 已通过脱敏验证。

**文件：** `src/axios.js`、新增 `tests/apiErrors.test.js`。

**问题及原因：** 非 2xx 分支丢弃统一响应中的 message / errorCode。页面看到的是通用 HTTP 提示，无法解释重名、权限和版本状态冲突。

**接口约定：** 保持 reject 流程，错误仍可读取 `errorCode`、`response`、`config`。保留 `silent: true`、401 的登录失效事件与跳转；网络和超时没有业务响应时使用现有通用提示。5xx、HTML、未知形状不得展示原始响应或异常堆栈。

- [ ] 使用 `loadJsModule` 加载实际 `axios.js`，mock `axios.create()`，捕获 `interceptors.response.use` 的成功和失败处理器；不复制一套拦截器实现作为被测对象。
- [ ] 编写失败验证：HTTP 409 的 `{success:false,errorCode:'HTTP_409',message:'不能重新发布历史版本'}` 应产生对应安全提示，reject 的 errorCode 可供页面读取。
- [ ] 在失败分支增加受限提取：只识别明确的客户端错误状态 400 / 403 / 404 / 409 / 422，以及 `success === false`、非空字符串 message、字符串或数字 errorCode 的统一响应。其他情况保持安全通用提示。

判定核心：

```js
const body = error.response?.data
const structuredClientError = [400, 403, 404, 409, 422].includes(error.response?.status)
  && body && typeof body === 'object' && body.success === false
  && typeof body.message === 'string' && body.message.trim().length > 0
  && ['string', 'number'].includes(typeof body.errorCode)
// structuredClientError 时规范化 Error.message / errorCode；否则保留安全兜底。
// 无论是否 silent，均应规范化 reject；silent 只控制自动提示。
```

- [ ] 验证 400 / 403 / 409、安全 HTML 兜底、500、网络、超时、silent、非 auth 的 401、登录接口 401，以及 HTTP 200 业务失败；断言非 auth 的单次 401 触发一次失效事件，silent 只抑制提示而不吞错。
- [ ] 运行 `node --test tests/apiErrors.test.js tests/customerServiceApi.test.js`（前端目录）。不改 SSE 的服务器错误脱敏策略，不扩大为服务端统一异常框架重构。

**完成标准：** 页面可以说明确定性的业务拒绝原因，并按错误码处理；原始 HTML、5xx 内容和敏感值不进入提示。

### T04：隔离新建会话响应，修正取消与草稿处理（F02、A01）

**文件：** `CustomerServiceChatPage.vue`、`ChatInputBox.vue`、扩展 `tests/chatInput.test.js`、新增 `tests/chatLifecycle.test.js`；复用 `createReplyScope` 和 `streamScopedReply`。

**问题及原因：** 令牌目前只覆盖流事件，未覆盖首轮 newChat 和完成赋值。另一个问题是输入组件 emit 后自行清空，而父页面也清空；父页面拒绝或恢复的内容可能在同一次事件中再次被子组件清掉。

**接口约定：** `newChat(message, knowledgeBaseId)`、SSE body 的 `chatId / requestId / message` 不变。`ChatInputBox` 继续发 `sendMessage` 事件，但由父页面在校验通过且接受本轮时清空 v-model。

- [ ] 通过可控 Promise 阻塞 newChat：A 库发送 → 停止 → 切到 B → 返回 A 的 UUID。断言不能覆盖 B 的库选择、chatId 和路由，也不能启动 A 的后续流。
- [ ] 补“取消流正常返回”及“停止旧轮次后启动新轮次”的测试，分别断言旧回答仍 CANCELLED，旧 finally 不释放新轮次的 isReplying。
- [ ] 将 `scrollToBottom` 纳入本轮 try / finally；使用 token 捕获的 knowledgeBaseId。滚动之后、调用 newChat 之前、newChat 返回之后、流函数返回之后，都检查本轮有效性。

核心顺序：

```js
// token 已由本轮开始时的 selectedKnowledgeBase.id 创建。
await scrollToBottom()
if (!token.isCurrent()) return
if (!chatId.value) {
  const created = await newChat(text, token.knowledgeBaseId)
  if (!token.isCurrent()) return
  chatId.value = created.data.data.uuid
  router.replace({ name: 'CustomerServiceChatPage', params: { chatId: chatId.value } })
}
// 调用既有 streamScopedReply，参数仍使用本轮身份。
// 流返回后，仅当前有效轮次可以写 COMPLETED。
```

- [ ] 保持 `replyScope.finish(token)` 的身份判断；过期轮次只能清理自己的局部对象，不写当前路由、chatId、输入草稿或新轮次状态。取消后服务端可能已经创建空会话，不把前端丢弃响应扩展为自动删除会话。
- [ ] 父页面校验实际待发送的 trim 后文本，超过 4000 时拒绝发送且保留原文；组件显示上限并在发送入口阻止超长值，保留中文输入法确认和 Shift+Enter 行为，不静默截断粘贴内容。
- [ ] 删除子组件“发出 sendMessage 后立即写空字符串”的职责，由父页面接受本轮时清空；更新既有输入测试，发送事件本身不再强制清空，父页面清空后仍正确重算高度。
- [ ] 对请求失败的本轮保留原问题；仅当它仍是当前轮次且当前输入为空时恢复，已有新草稿时不覆盖。取消或切库后不恢复旧库草稿；不自动重试、重发或重新调用模型。
- [ ] 覆盖 4000 / 4001、全空白、校验拒绝、首轮建会话失败、生成失败、取消、失败时已有新草稿。请求失败保留 FAILED / CANCELLED 真实状态，不把它改为完成。
- [ ] 运行 `node --test tests/chatInput.test.js tests/chatLifecycle.test.js tests/knowledgeScope.test.js`（前端目录）。测试 mock 创建会话和 SSE，不发模型请求。

**完成标准：** 页面知识库、会话和当前轮次始终一致；取消不能变成完成；拒绝或失败不会无条件丢失问题，也不覆盖用户后写的草稿。

### T05：为成员列表和授权操作绑定知识库归属（F03）

**文件：** `AdminAccessPage.vue`、`tests/adminAccess.test.js`。

**接口约定：** 保留 `listKnowledgeBaseMembers(knowledgeBaseId)`、`assignKnowledgeBaseMember(knowledgeBaseId, data)`、`revokeKnowledgeBaseMember(knowledgeBaseId, userId)`。成员显示归属不得靠“当前选中库”推测。

- [ ] 增加可控 Promise 用例：先发 A、再发 B，先返回 B、后返回 A；断言最终成员仍属于 B，旧请求不能关闭 B 的加载标记。
- [ ] 引入页面内 `membersRequest` 序号和 `membersOwnerId` 归属标记；每次加载捕获选中的库，先清空旧列表和归属，只接纳最新且归属仍匹配的结果。

核心回写条件：

```js
let membersRequest = 0
const membersOwnerId = ref(null)
// loadMembers 开始时捕获：
const requestId = ++membersRequest
const ownerId = Number(selectedKnowledgeBaseId.value)
// 请求返回及 finally 只有在下式成立时才可写当前页面状态：
const stillCurrent = () => requestId === membersRequest
  && ownerId === Number(selectedKnowledgeBaseId.value)
// 成功回写 members 时，同时设置 membersOwnerId.value = ownerId。
```

- [ ] 切库或清空选择立即清空旧成员；无有效库时不发请求。加载中或写操作中禁用保存和撤销；写操作期间禁用切库，结束后恢复。
- [ ] 保存 / 撤销函数内部再次检查加载状态、写状态和成员归属，而不是只依赖 disabled。撤销必须确认该行属于当前已加载成员，防止旧行事件使用新库 ID。
- [ ] 写操作开始时捕获库 ID、用户 ID 和权限值；成功后仅刷新同一仍被选中的库，清理 busy 的行为只属于本次操作。不把成员响应当作账号全局权限的事实。
- [ ] 测试逆序返回、清空库、旧请求失败、加载中调用撤销、连续点击以及合法撤销的参数；A 的旧行不得产生针对 B 的删除授权请求。
- [ ] 运行 `node --test tests/adminAccess.test.js`（前端目录）。后端的角色和知识库授权不改动。

**完成标准：** 任何成员列表都能对应到唯一当前库；操作不能使用过期列表，不会误操作另一库的同一用户授权。

### T06：隔离版本预览，修复空日期查询（F06、F08）

**文件：** `MarkdownFileDrawer.vue`、`tests/markdownFileScope.test.js`。

**接口约定：** 预览继续调用 `previewDocumentChunks(documentId, fileId)`；本地校验同时捕获知识库。文件查询继续按 `findMarkdownFilePageList(pageNo, size, fileName, startDate, endDate, knowledgeBaseId)` 传参，日期仍为 `YYYY-MM-DD`。

- [ ] 扩展现有 setup fixture，允许注入预览 mock 和可控响应；保持依赖只在测试中 mock，不把测试调度代码放入业务组件。
- [ ] 建立 v1 / v2 预览逆序、切库后旧预览返回、清空资料选择、关闭抽屉时响应返回的用例；断言只有当前选择可写 previewChunks，变化时立即显示空状态。
- [ ] 添加组件内预览请求序号，捕获库 / 文档 / 版本 ID；监听这三个选择与 open 的变化，立即失效旧请求、清空 previewChunks / previewLoaded。预览请求返回时校验全部归属和序号；关闭状态不接收结果。

预览失效所需状态操作：

```js
let previewRequest = 0
const resetPreview = () => {
  previewRequest++
  previewChunks.value = []
  previewLoaded.value = false
}
watch(() => [props.knowledgeBase.id, selectedDocumentId.value,
  selectedVersionId.value, props.open], resetPreview, { flush: 'sync' })
```

请求开始保存 `requestId / ownerId / documentId / fileId`，返回后逐一比较；不要只比较版本号 v1 / v2，因为不同资料也有同名版本。失败的旧请求不得清空新结果。

- [ ] 在同一测试文件增加 `formState.startEndDate = null` 用例，调用 `renderTableData(1, 10)`，断言请求日期参数均为 undefined、loading 最终为 false。
- [ ] 把 `renderTableData` 改为等价的 async / try / catch / finally；将日期读取、format 和 API 调用都放在 try 内，保留原有 listRequest 与 ownerId 校验。

```js
const dateRange = Array.isArray(formState.startEndDate) ? formState.startEndDate : []
const [startDate, endDate] = dateRange
const startDateStr = startDate?.format('YYYY-MM-DD')
const endDateStr = endDate?.format('YYYY-MM-DD')
```

- [ ] 覆盖 null、空数组、完整日期和请求失败；有效日期不变成 UTC 时间戳。旧请求的 finally 不得结束新请求的 loading。
- [ ] 运行 `node --test tests/markdownFileScope.test.js`（前端目录）。

**完成标准：** 预览内容与页面当前库、资料及版本一致；清空日期不抛异常，不遗留持续加载状态。

### T07：按现有版本生命周期控制发布与删除（F05）

**文件：** `MarkdownFileDrawer.vue`、`tests/markdownFileScope.test.js`；只读后端 `KnowledgeDocumentService.publish / assertDraftCanBeDeleted`。已有 `KnowledgeDocumentServiceTest` 作为后端契约回归，不为修复前端放宽服务端限制。

**接口约定：** 发布仍传 documentId / fileId，删除仍传 id / force / knowledgeBaseId。文件列表已有 publishStatus / documentId / versionNo，不增加字段。

- [ ] 在前端测试建立下列矩阵；先确认原逻辑对 ARCHIVED / 非最新 WITHDRAWN 仍允许发布，对非草稿仍打开删除确认。

| 版本状态 | 处理完成且索引 SYNCED 时允许发布 | 允许发起删除草稿操作 |
| --- | --- | --- |
| DRAFT | 是 | 是；仍服从已有处理中删除限制 |
| PUBLISHED | 不提供重复发布按钮 | 否 |
| ARCHIVED | 否 | 否 |
| WITHDRAWN，且 versionNo 为该资料最大值 | 是 | 否 |
| WITHDRAWN，存在更高 versionNo | 否 | 否 |
| 任意状态，但解析未完成或索引未同步 | 否 | 仅 DRAFT 可进入原有删除流程 |

- [ ] 用全部 versions 的最大 versionNo 判断“最新”，不依赖数组顺序。沿用当前 canEdit、有效库及 publishing 限制。

```js
const latestVersionNo = computed(() => Math.max(0,
  ...(selectedDocument.value?.versions || []).map(version => Number(version.versionNo))))
const publishableLifecycle = computed(() => selectedVersion.value?.publishStatus === 'DRAFT'
  || (selectedVersion.value?.publishStatus === 'WITHDRAWN'
    && Number(selectedVersion.value.versionNo) === latestVersionNo.value))
// canPublishSelected 继续同时要求 status === 3、indexStatus === 'SYNCED' 和维护权限。
```

- [ ] 删除按钮仅对当前库 DRAFT 显示 / 启用。`showDeleteMarkdownModel(id)` 从当前 dataSource 找到记录并核验状态；确认执行时再次核验，不允许直接调用处理函数绕过入口。
- [ ] 保留原 force 二次确认，只能处理已允许删除的草稿；禁止将所有删除默认改成 force=true，也不能对 PUBLISHED / ARCHIVED / WITHDRAWN 发起强制删除。
- [ ] 覆盖无权限、不同库、缺失状态、未就绪和乱序 versions；禁用 / 隐藏之外，直接调用事件函数也不得发出不允许的请求。后端 409 冲突通过 T03 安全显示，提示刷新后再操作。
- [ ] 运行前端 `node --test tests/markdownFileScope.test.js`；后端运行 `mvn -f milan-ai-robot-springboot/pom.xml "-Dtest=KnowledgeDocumentServiceTest" test`。后者使用 mock，不访问真实数据库。

**完成标准：** 页面允许的操作与后端现有规则一致；不借修复增加历史回滚，不以按钮正确代替后端约束。

### T08：限制上传格式，明确发布前提（F09、A03）

**文件：** `MarkdownFileDrawer.vue`、`knowledgeScope.js`、`tests/markdownFileScope.test.js`；仅在已有返回约定变化时调整 `tests/knowledgeScope.test.js`。

**问题及原因：** 视频格式留在正常上传入口是测试便利性遗留；选择器 accept 也只是浏览器提示，不能代替事件内校验。处理完成、索引同步和资料发布是三个不同事实。

**接口约定：** 上传分片、MD5、断点续传和秒传接口保持不变；现有后端 Reader 和 UNSUPPORTED_FILE_TYPE 兜底保留。`uploadAvailability` 的 kind / status 不因文案调整而改名。

- [ ] 编写行为验证：选择 `demo.mp4` / `demo.MOV` / `demo.avi` 后，不进入 MD5 计算，不调用检查 / 分片 / 合并接口，并给出支持格式提示。
- [ ] 把文件 input accept 收敛为 `.md,.markdown,.pdf,.doc,.docx,.txt`，删除“允许视频用于测试”的产品入口注释；handleFileSelect 在 MD5 计算之前再次做大小写不敏感的扩展名判断。

```js
const supportedFileName = name => /\.(md|markdown|pdf|doc|docx|txt)$/i.test(name || '')
```

- [ ] 对不支持格式清理待选文件与 MD5，失效上次尚未结束的 MD5 计算，避免旧计算回写并继续上传旧文件；实际上传进行中仍遵循现有禁用逻辑。有效格式继续走原有零字节、大小及分片约束，不添加 MIME 嗅探系统。
- [ ] 抽屉顶部提示改为“文件解析和索引同步完成后，请检查片段并发布；只有当前发布版本参与新问题检索。”
- [ ] 上传就绪提示使用“文件与索引已就绪，请确认资料已发布后再用于新问答”；等待索引提示也明确后续还需发布。不把就绪标成已发布，不自动调用 publish。
- [ ] 检查合法格式大小写、取消文件选择、同名文件重选、不支持格式后重新选合法文件；实际分片压力测试资料可保留，不再通过正常资料入口宣传视频支持。
- [ ] 运行 `node --test tests/markdownFileScope.test.js tests/knowledgeScope.test.js tests/customerServiceApi.test.js`（前端目录）。不为上述提示语新增逐字匹配测试。

**完成标准：** 产品入口格式与解析能力一致；用户能区分上传成功、索引就绪和发布成功。

### T09：有针对性的核验与文档同步

**文件：** 本计划与原检查报告；只有实际行为说明受影响时才更新 README 的相应段落，不改写简历指标。

- [ ] 检查 diff，只保留本计划对应修改；记录此前已有的修改，不重置和清理其他工作。
- [ ] 使用 JDK 21 运行后端限定测试类；不执行依赖真实数据库、Docker、ES 或模型的集成测试。

```powershell
# 在项目根目录执行，仅供后续实施使用。
mvn -f milan-ai-robot-springboot/pom.xml "-Dtest=GlobalExceptionHandlerSecurityTest,EnterpriseRequestValidationTest,KnowledgeDocumentServiceTest" test
```

- [ ] 运行本次涉及的前端测试和一次构建；新增代码未变更后不反复重跑。

```powershell
# 在 F:/code/qiufengm-ai-robot/project/milan-ai-robot-vue3 执行。
node --test tests/adminAccess.test.js tests/apiErrors.test.js tests/chatLifecycle.test.js tests/chatInput.test.js tests/markdownFileScope.test.js tests/knowledgeScope.test.js tests/customerServiceApi.test.js
npm run build
```

- [ ] 用真实页面核对三处 Form 的提交、输入法与换行、清空日期、版本按钮及发布提示。已有页面和服务未启动时，明确记录“浏览器联调未执行”；不能仅凭构建通过认定交互通过，也不自动恢复服务或导入数据。
- [ ] 对并发问题保留可控 Promise 测试证据，必要时用浏览器网络延迟观察同一时序；不以随机重复点击替代稳定回归用例。
- [ ] 在检查报告追加每个编号的修改文件、验证命令、实际结果、仍未验证的部分；保留原静态结论和检查日期。更新本计划勾选状态。

## 四、最终验收记录模板

实施后在下表填写实际证据，不填写推测结果。“未运行”不是失败，也不等于通过。

| 项目 | 当前状态 | 实施后必须提供的证据 |
| --- | --- | --- |
| F01 三处表单 | 尚未修复 / 未运行 | 真实 Form 提交到对应请求的观察记录；非法值零请求 |
| F02 会话与取消 | 尚未修复 / 未复现 | 创建响应晚到、取消返回、新轮次不被旧 finally 影响的断言 |
| F03 成员归属 | 尚未修复 / 未复现 | A/B 逆序后显示 B；旧 A 行不能触发针对 B 的撤销 |
| F04 校验脱敏 | 尚未修复 / 未运行 | 响应与 Log4j2 日志不含虚构拒绝原值，HTTP 400 保持 |
| F05 生命周期 | 尚未修复 / 未运行 | 前端状态矩阵和后端原有服务测试结果 |
| F06 片段预览 | 尚未修复 / 未复现 | 版本 / 文档 / 库 / 开关变化使旧结果失效 |
| F07 HTTP 错误 | 尚未修复 / 未运行 | 结构化 4xx 可解释，silent / 401 / HTML / 5xx 等行为符合约定 |
| F08 日期清空 | 尚未修复 / 未复现 | null 不抛错，日期不传，loading 能复位 |
| F09 文件格式 | 尚未修复 / 未运行 | 视频不读 MD5、不请求上传，合法格式仍走原流程 |
| A01 输入草稿 | 尚未修复 / 未运行 | 4000 / 4001 边界和失败恢复，不覆盖后写草稿 |
| A02 账号规则 | 尚未修复 / 未运行 | 账号、显示名和中英文密码边界与后端共同约束一致 |
| A03 发布提示 | 尚未调整 | 人工检查清楚区分处理完成、索引就绪与发布 |
| 构建与受限测试 | 本轮未运行 | 后续实际命令、退出码和错误摘要，不写未经验证的通过率 |

## 五、停止条件与交付范围

实施遇到与静态分析不同的实际行为，先记录输入、异步顺序、状态及响应，修正根因判断后再改代码。遇到必须修改数据库、模型、后端业务权限或新增页面才能解决的情形，先记录为范围变化，不沿用本计划自动扩展。

九项问题及三个小项分别通过对应检查，真实 Form 验证有记录，构建 / 限定测试状态如实同步后，才可把本轮标为完成。没有执行的项目必须保留在记录中；知识库编辑页面不属于本轮完成条件。

**本次文档编写状态：计划已形成，修复实施未开始。**
