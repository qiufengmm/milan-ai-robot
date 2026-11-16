# 企业知识库项目旧代码清理实施方案

> **实施记录：** 已按本方案完成。用户于 2026-11-15 明确授权本轮按计划移除多文件；旧资料先移至项目外归档并逐项校验 SHA-256，计划源码逐路径处理，没有使用递归删除。

**Goal:** 项目仅保留企业内部知识问答业务，完整移除通用聊天、联网搜索和电商演示的专用实现，以及残留引用、兼容分支、配置、测试与资源。

**Architecture:** 沿用当前企业控制器、会话服务、权限服务、文档生命周期和 RAG 链路。共享服务改为直接依赖企业权限与文档服务，消除“服务不存在就回到旧流程”的装配方式；启动配置统一到一套企业配置，账号和库权限始终启用。

**Tech Stack:** Java 21、Spring Boot 4.1.0、Spring AI 2.0.1、MyBatis-Plus、PostgreSQL/pgvector、Elasticsearch、RRF、Rerank、SSE、Vue 3。

**Status:** 2026-11-15 已完成本次企业唯一流程清理。计划内 45 个旧专用文件已移除、65 个旧资料文件已归档并校验；后端 190 项定向测试、前端 17 项测试、前后端构建及隔离 SQL 验证通过。新 JAR / dist 无旧专用类资源；4 个指定用例的 5 轮限定冒烟单独记录，未运行完整 60 题。VER03 回答部分正确，MT02 第二轮改写发生一次现有回退，均保留为已知问题。

**本次补充收尾（2026-11-15）：** 首轮归档 65 份资料后，又将 10 份旧课程资料逐份移至项目外归档，累计 75 份，均按 SHA-256 校验。另将两份检索测试中的 24 处“退款”样例替换为“报销”，移除 2 个无引用响应码、pom.xml 空 Jsoup 注释及无引用 logo.svg，并给历史方案/账本标注后续清理状态。本次只做静态核对，未运行测试、构建或接口请求。

**根目录：** `F:/code/qiufengm-ai-robot/project`。下文表格路径相对此目录。精确路径与文件数量以 [清理清单](2026-11-15-enterprise-only-cleanup-inventory.json) 为准。

## 1. 清理边界与“没有残余”的定义

完成后的运行源码、现行测试、启动配置、依赖和发行制品中，应满足：

- 不再存在旧通用聊天控制器、服务、Advisor、请求模型和响应协议。
- 不再存在 SearXNG、网页抓取、搜索专用线程池、联网搜索按钮和前端模型菜单。
- 不再存在商城系统提示词、商城默认库名、旧页面路由和电商专用运行脚本。
- 不再存在 `general-chat.enabled`、`enterpriseMode`、`simpleMode` 等为兼容旧业务保留的开关与分支。
- 不再存在权限服务缺失时跳过检查、文档服务缺失时放宽发布过滤的路径。
- 不再存在已删除类型的 import、只服务于旧功能的 Bean、孤立 DTO、无调用方法及无消费者依赖。
- 新构建的 JAR、前端资源中也不包含上述旧类和资源；不能只检查源码。

以下属于当前企业功能，保留其现有 API/数据库标识：`/chat/new`、`/chat/list`、`/customer-service/chat/completion`、文件接口、`t_chat`、`t_chat_message`、`mdStorageId`。这些名称已经被企业业务复用，本次不做接口、表、包名的全面改名。

企业历史评测、原实施记录、Git 历史用于追溯，不作为运行代码扫描目标。旧教程、旧电商评测脚本和探针统一归档到项目外，避免与当前说明混用。

本次清理不顺带修改问答策略、模型、温度、重排阈值或修复 CL01/SAFE05 等回答质量问题。它们继续作为已知不足记录，清理验收不以全题答对为条件。

## 2. 删除清单：45 个文件（已完成）

先迁移共享引用，再移除清单中的旧专用文件。45 个清单路径现均已逐项验证不存在于工作区；未清除企业业务数据库、Docker 容器、数据卷或本地私钥配置。

### 2.1 后端旧专用源码：24 个

以下路径位于 `milan-ai-robot-springboot/src/main/java/com/milanai/robot/`：

| 文件 | 删除原因 |
|---|---|
| `controller/ChatController.java` | 旧自由聊天及未按员工归属隔离的会话入口；企业接口由 EnterpriseConversationController 承担 |
| `service/ChatService.java` | 旧会话服务契约 |
| `service/impl/ChatServiceImpl.java` | 旧无员工归属的会话实现，当前仍声明为 Bean |
| `advisor/CustomChatMemoryAdvisor.java` | 旧通用机器人记忆链路 |
| `advisor/CustomStreamLoggerAndMessage2DBAdvisor.java` | 旧流式响应与推理内容落库链路 |
| `advisor/NetworkSearchAdvisor.java` | 联网提示词拼接链路 |
| `advisor/CustomerServiceAdvisor.java` | 旧商城客服提示词和生成链路 |
| `service/SearXNGService.java` | 聚合搜索接口 |
| `service/impl/SearXNGServiceImpl.java` | 聚合搜索实现 |
| `service/SearchResultContentFetcherService.java` | 搜索结果正文抓取接口 |
| `service/impl/SearchResultContentFetcherServiceImpl.java` | Jsoup 网页清洗与抓取实现 |
| `model/dto/SearchResultDTO.java` | 仅旧搜索链路使用 |
| `model/vo/chat/AiChatReqVO.java` | 旧前端选择模型、温度和联网开关的请求 |
| `model/vo/chat/AIResponse.java` | 旧 `v/reasoning` SSE 响应 |
| `model/vo/chat/NewChatReqVO.java` | 旧不绑定知识库的新会话请求 |
| `model/vo/chat/RenameChatReqVO.java` | 旧按数字 ID 重命名请求 |
| `model/vo/customerService/AiCustomerServiceChatReqVO.java` | 双模式请求；改用已有 EnterpriseChatCompletionReqVO |
| `config/ThreadPoolConfig.java` | 仅搜索抓取线程池；RagExecutorConfig 独立保留 |
| `config/DevelopmentSecurityConfig.java` | 旧演示放行安全链；企业权限链改为无条件启用 |
| `config/CorsConfig.java` | 旧演示任意来源 CORS 配置，当前企业链路未使用 |
| `config/ChatClientConfig.java` | 无注入消费者的 ChatClient Bean；企业服务直接使用已有 ChatModel |
| `config/JsonUtilInitializer.java` | 仅初始化无业务消费者的旧静态工具 |
| `utils/JsonUtil.java` | 当前除初始化器外无调用方 |
| `utils/StringUtil.java` | 当前仅旧 ChatServiceImpl 调用 |

### 2.2 前端旧专用文件：5 个

以下路径位于 `milan-ai-robot-vue3/`：

- `src/views/Index.vue`
- `src/views/ChatPage.vue`
- `src/stores/chatStore.js`
- `src/stores/persistedChatSelection.js`
- `tests/chatSelection.test.js`

### 2.3 失去消费者的 SVG：9 个

以下路径位于 `milan-ai-robot-vue3/src/assets/icons/`：

`network.svg`、`deepseek-logo.svg`、`qwen-logo.svg`、`kimi-logo.svg`、`down-arrow.svg`、`check.svg`、`customer-service.svg`、`back.svg`、`settings.svg`。

这些图标目前只由待删除页面、模型菜单、旧侧栏入口引用，或已经没有引用。移除前复查静态名称及动态名称绑定；最终构建的 SVG sprite 也须确认无这些资源。

补充收尾移除了未被引用的默认 Vue 资源 milan-ai-robot-vue3/src/assets/logo.svg；它不属于上面的 9 个旧页面专用图标。

### 2.4 旧测试：4 个

以下路径位于 `milan-ai-robot-springboot/src/test/java/com/milanai/robot/`：

| 文件 | 处理方式 |
|---|---|
| `advisor/CustomerServiceAdvisorTest.java` | 删除旧商城 Advisor 测试；企业 RAG 测试继续保留 |
| `controller/ChatControllerEnterpriseModeTest.java` | 原测试只证明开关返回410，删除；由新的企业唯一入口测试覆盖旧路由不存在 |
| `config/DevelopmentSecurityConfigTest.java` | 原测试验证旧开发模式；删除，新增无 profile 绕过认证的覆盖 |
| `MybatisPlusTests.java` | 课程插入样例，没有用户与知识库归属且会写数据库；移出当前测试集 |

### 2.5 配置文件：3 个

以下路径位于 `milan-ai-robot-springboot/src/main/resources/`：

- `application-dev.yml`：旧演示数据库及 SearXNG 配置。
- `application-prod.yml`：当前为空文件。
- `application-enterprise.yml`：先把现行企业参数完整合并进 `application.yml`，验证等价后再移除。

`application-secret.yml` 是本地账号与网关配置，保留且继续忽略提交；实施期间不输出密钥和密码。

## 3. 保留并精简的部分

| 模块 | 保留内容 | 必须剥离的旧内容 |
|---|---|---|
| `EnterpriseConversationController/Service` | 员工会话、完整历史、状态、幂等与取消 | 无效的 `.reasoning(null)` 响应字段和模式条件 |
| `EnterpriseKnowledgeChatService`、`QuestionRewriteService` | 企业问答、追问改写和失败回退 | 仅移除双模式装配限制，不更改问答策略 |
| `AiCustomerServiceController` | 现有文件接口及企业 completion URL | 旧商城生成分支、手工模型构建、旧 Advisor、仅旧分支使用的配置字段 |
| `CustomerServiceImpl` | 分片、秒传、合并、解析、删除草稿、状态查询 | 文档/权限服务可缺省的旧分支 |
| `KnowledgeBaseController` | 可访问库、创建/管理与重建索引 | 无权限服务时列出全部库/免校验的路径 |
| `HybridRetrievalServiceImpl` | pgvector、BM25、RRF、当前发布文件预过滤 | `allowedFileIds == null` 代表不过滤发布状态的兼容路径 |
| `EsChunkService/Impl` | 索引同步、按库和已发布文件集合查询 | 仅旧调用需要的不带允许文件集合的查询入口 |
| `FileDocumentGuard/Impl` | 再次核实候选所属库、文件状态和当前发布指针 | 无允许文件集合的旧入口，以及 null 集合转入旧入口的分支 |
| `RerankService/Impl` | 当前带阈值、分数校验和失败拒绝行为 | 旧无阈值入口及其专用未评分回退分支 |
| 权限、文档、来源与反馈服务 | 登录、角色、库成员、版本、来源卡、反馈处理 | 仅由旧业务造成的 profile 条件和可选注入 |
| `ChatDO/ChatMessageDO`、Mapper | 企业消息与会话表、原数据 | `reasoningContent` 映射；旧无归属分页便捷方法 |
| `NewChatRspVO` | 企业 createChat 仍实际使用 | 不得因名称近似旧 DTO 而误删 |
| 文件 Mapper | 按库及读写权限限制的文件分页 | 只被旧流程调用的不带库或不带发布范围的重载 |
| `ChatInputBox.vue` | 输入、自动高度、IME/回车、发送与禁用状态 | 模型菜单、联网按钮、chatStore、simpleMode 与旧 payload |
| `Sidebar.vue`、`Layout.vue` | 企业导航、本人历史、账号管理、退出 | enterpriseMode 分支、旧智能客服入口及旧跳转函数 |
| `authStore.js`、Pinia | 登录身份与会话初始化 | 删除仅旧 chatStore 需要的持久化插件 |
| 企业问答页、资料管理、来源卡与反馈 | 当前员工/维护者流程 | 商城占位文案、旧双模式 props |
| Markdown 渲染、LoadingDots、通用 SVG | 企业页面仍在使用的组件和图标 | 不以“最早用于旧项目”为理由删除共享组件 |

依赖处理：

- 删除 `pom.xml` 中直接声明的 Jsoup 依赖与版本属性；当前唯一使用方是网页抓取实现。
- 保留 OkHttp 和 OkHttpConfig：ES 与 Rerank 仍在使用。
- 保留 Guava：文件解析监听器仍有实际引用；保留 Jackson、Spring AI、各文档 Reader、RRF、Markdown 渲染及文件上传依赖。
- 删除 `pinia-plugin-persistedstate` 及 `main.js` 注册，更新 `package-lock.json`；authStore 当前不使用其持久化能力。
- 锁文件使用 `npm install --package-lock-only --ignore-scripts` 更新，不运行会批量移除 node_modules 文件的卸载或 prune 命令。node_modules 属于被忽略的本地依赖目录，不作为运行源码残余判据。
- 不执行带 `--force` 的依赖升级或无关版本更新。

## 4. 清除隐藏兼容分支

这是达到“代码无残余”的必要工作，不以删除页面代替。

### 4.1 completion 请求与控制器

保留请求地址：`POST /customer-service/chat/completion`。

使用现有 `EnterpriseChatCompletionReqVO`，唯一业务请求契约为：

```json
{
  "chatId": "会话UUID",
  "requestId": "本轮幂等键",
  "message": "本轮问题"
}
```

知识库从本人会话记录反查；不接受通过客户端字段切换模型、温度或联网能力。保留现有 `meta/sources/delta/done/error` 事件和失败状态记录。

控制器直接依赖 EnterpriseConversationService、EnterpriseKnowledgeChatService、KnowledgeAccessService；依赖缺失时启动失败，不回退到旧生成器。缺失/超长 chatId、requestId、message 由已有 DTO 约束返回400。保留 beginRequest 之后设置失败状态的现有异常处理。

### 4.2 上传与资料查询

- CustomerServiceImpl 直接注入 KnowledgeDocumentService 与 KnowledgeAccessService。
- 新文件建立草稿和上传者记录；替换文件必须校验同一知识库的逻辑文档。
- 删除前始终执行 `assertDraftCanBeDeleted`，历史版和当前版不能进入旧文件删除路径。
- 读者只看已发布资料，维护者查看本库草稿和历史版；取消缺少权限组件时走全量查询的分支。
- 去除 KnowledgeBaseService.resolveId 的 null→1 回退；CheckFileReqVO、UploadChunkReqVO、MergeChunkReqVO、DeleteMarkdownFileReqVO、FindMarkdownFilePageListReqVO、UpdateMarkdownFileReqVO 去除 `knowledgeBaseId = 1L` 和对应 Builder.Default，并对该字段执行非空、正数校验。文件上传的表单入口也启用 DTO 校验。
- 文件操作须显式携带已授权知识库 ID，completion 则继续从会话反查库；缺少库 ID 不会静默落入员工服务库。
- 调整现有 CustomerServiceChunkUploadTest 等测试夹具，显式提供所需服务，不通过重新增加可选参数保留旧模式。

### 4.3 检索与重排

- HybridRetrievalServiceImpl 构造器必须取得 KnowledgeDocumentService。
- 每次都获取非 null 的当前发布文件 ID 集合；集合为空时直接无资料，不放宽到全库向量。
- 向量和 ES 路径都使用同一集合预过滤；查询没有有效知识库 ID 时拒绝，不再默认为 ID 1。
- EsChunkService 只暴露显式携带允许文件集合的关键词检索方法；实现必须真正应用集合过滤，不保留默认方法转调无过滤重载。
- FileDocumentGuard 只保留显式携带允许文件集合的候选验证入口；null 集合按调用契约错误拒绝，空集合返回空结果，不能退回仅核对“文件处理完成”的旧校验。
- RerankService 保留 `rerank(query, candidates, minRelevanceScore)`，当前企业调用继续使用0.50阈值，异常不返回未评分候选。
- 移除 ChatMapper、ChatMessageMapper 的旧 selectPageList，保留企业服务实际使用的本人归属查询、行锁和状态更新。

### 4.4 统一启动与安全

- 将 application-enterprise.yml 的数据库、向量表、模型、存储路径、ES索引、Rerank参数、Cookie和管理员初始化参数并入 application.yml。
- 保持 `milan_enterprise`、`milan_enterprise_chunks_v1`、1024维、现有模型和当前存储目录的有效值；先做脱敏的配置对照表，确认私有配置覆盖后的有效值一致。
- 合并配置可能改变本地私有目录项的覆盖优先级；遇到此差异时只修正相应的旧目录覆盖值，保留账号、Key和网关内容，不将私有值写入清理报告。
- 移除 `spring.profiles.active` 的旧业务选择、`general-chat.enabled` 和 SearXNG 配置。
- 当前企业组件的 `@Profile("enterprise")` 条件移除，使认证、权限、账号状态过滤和文档规则始终装配；旧 `!enterprise` 实现按清单物理移除。
- 不再提供允许匿名访问业务接口的开发安全链。`/auth/csrf`、登录/退出维持现行契约；业务接口仍要求登录和CSRF。
- 不新增 SSO、部门树、多租户、限流或监控模块。

## 5. 前端具体收敛

- ChatInputBox 删除 showModelDropdown、showNetworkSearch、simpleMode 以及相关计算属性、事件监听和 dropdown DOM。
- 保留 `update:modelValue` 与 `sendMessage` 事件名；sendMessage 不再携带 selectedModel/isNetworkSearch，企业问答页当前不依赖这两个字段。
- Sidebar/Layout 移除 enterpriseMode 参数及对应条件，直接呈现企业导航；移除旧 Index/ChatPage 跳转。保留历史分页、UUID重命名、删除、登录过期处理。
- CustomerServiceChatPage 和 AdminAccessPage 删除双模式 props；保留发送前固定知识库、取消流、来源卡、服务端错误码及反馈。
- KnowledgeBaseSelector 的商城示例改为“员工服务 / IT支持”；MarkdownFileDrawer 移除商城默认对象，由父页面显式传入已授权的知识库。
- `src/api/customerService.js` 各函数取消 `knowledgeBaseId = 1` 默认参数，保留现有参数顺序；MarkdownFileDrawer 的编辑记录初始知识库设为空，打开编辑时从当前授权库和实际文件记录赋值。未选知识库时禁用对应操作，不提交默认库请求。
- 统一页面标题、欢迎语、占位文字为“企业知识库/员工知识问答”，保留现有登录、资料管理、来源和反馈流程。
- 移除本地模型选择读取和迁移逻辑。旧浏览器 localStorage 中不再被读取的 chat 键不影响新程序，不为清理旧键新增长期运行的迁移模块。

## 6. SQL、旧资料与说明文档

### 6.1 SQL 的处理

- 保留 `schema.sql`、`migrate_chunk_upload.sql`、`migrate_rag_knowledge_bases.sql` 和 `schema_enterprise.sql` 中仍为企业服务提供表结构的部分。
- 知识库初始化直接使用“员工服务”和“IT支持”，删除“先写商城再改名”的逻辑；继续使用 `ON CONFLICT DO NOTHING`，不覆盖管理员已修改的库名、授权和当前版本指针。
- 从全新建表定义删除仅旧通用聊天使用的 reasoning_content；同步移除 Java 映射、响应字段和 `.reasoning(null)`。现有数据库已存在的旧列保留其数据，不执行 DROP COLUMN。
- `scripts/verify_rag_fresh_schema.py` 改为验证企业完整建表入口、两库名称及核心表；`verify_rag_migrations.py` 保留分片/RAG幂等验证，删除电商预期值和旧模式说明。
- 仅在专用验收数据库运行初始化/重入检查，不把 SQL 验收指向当前三个业务数据库。

### 6.2 历史材料归档：首批 65 个，补充 10 个

精确文件清单在 inventory JSON 的 `legacyMaterialArchiveOutsideProject`。首轮已将 65 个文件逐项归档至 `F:/code/qiufengm-ai-robot-legacy-archive-20261115/materials`，并对每个目标文件验证 SHA-256：

- `test-data/ecommerce/` 中的资料、旧脚本、旧结果和本地产物。
- `test-data/` 根目录的旧客服/项目问答资料。
- `bench/` 的 Token/SearXNG旧探针及本地产物。
- `教程/` 的旧课程材料。
- 2026-11-14 的电商设计与教程15实施文档。
- 后续补充：test-data/pagination-test/ 的 10 份旧课程/测试资料，逐份归档至项目外 materials/test-data/pagination-test/；逐文件 SHA-256 记录见 [补充归档清单](../../../.superpowers/sdd/2026-11-15-enterprise-only-cleanup/pagination-test-followup.json)。

归档清单以本轮工作区快照为准，包含旧探针本地产物。归档后的材料不再从主 README 链接为当前项目能力。

保留企业 `test-data/enterprise/` 的语料、脚本、全部原始评测、人工评分和反馈/发布验收证据。原企业实施计划和进度记录保留历史事实，并在顶部链接本清理方案，说明旧模式保留安排已被本方案替代，不回写原始回答或虚增评测成绩。

### 6.3 当前文档

- 根 README 改为企业项目单一说明，清除旧通用聊天接口表、联网性能、商城模式启动方式、旧模型菜单与旧语料入口。
- 更新前端 README、docker/elasticsearch/README.md、企业资料 README、简历草稿和启动说明。
- 更新 `.gitignore` 中旧 bench/电商专用条目；保留密钥、日志、构建目录、运行数据与通用备份保护规则。
- 保留 Docker 中的 PG/ES 服务配置；不停止或删除现有数据库、容器、卷、已导入资料或本地密钥文件。

## 7. 实施顺序与交付检查点

### Task 1：冻结基线并准备边界验证

**Files:** 修改既有企业访问/上传/检索测试；新增 `milan-ai-robot-springboot/src/test/java/com/milanai/robot/controller/EnterpriseOnlyRuntimeIntegrationTest.java`。

- [x] 记录 Git 未提交文件、现行 JAR/进程、有效配置摘要、发布文件指针和评测快照；不暂存或覆盖其他工作。
- [x] 为默认配置及人为设置dev/prod时，认证/权限仍存在建立集成覆盖；使用现有 AuthenticationIntegrationTest 的模拟身份和Mapper夹具，避免真实模型和当前业务库。
- [x] 检查 HandlerMapping 只包含企业会话控制器，且不存在精确路径 `/chat/completion`。
- [x] 在已认证且CSRF正确的条件下，请求旧 POST `/chat/completion` 必须404或405且不调用模型；未登录返回401不能单独证明旧路由已消失。
- [x] 建立 completion 缺失requestId返回400、跨用户会话拒绝、无已发布文档不召回草稿的回归验证。
- [x] 建立文件JSON请求及分片表单缺少knowledgeBaseId返回400、未调用上传/默认库查询的覆盖；KnowledgeBaseServiceTest 更新为 null/非正数 ID 被拒绝。

示例断言约束（放入上述集成测试，复用现有MockMvc安全夹具）：

```java
// 只有确切旧路径为被拒对象；企业 GET /chat/{uuid} 仍需要存在。
mockMvc.perform(post("/chat/completion").with(csrf())
        .contentType(MediaType.APPLICATION_JSON).content("{}"))
    .andExpect(result -> assertThat(result.getResponse().getStatus()).isIn(404, 405));
```

此测试的前置条件是现有模拟员工已通过账号状态检查，且已设置真实的认证上下文；不能把登录失败当作路由检查通过。

**交付：** 清理前后要保持的企业接口和边界已有明确验证口径。

### Task 2：收敛后端运行流程和共享服务

**Files:** AiCustomerServiceController、KnowledgeBaseController、KnowledgeBaseService、六个文件请求DTO、CustomerServiceImpl、HybridRetrievalServiceImpl、EsChunkService/Impl、FileDocumentGuard/Impl、RerankService/Impl、ChatMapper、ChatMessageMapper、文件Mapper和对应既有测试。

- [x] 接入已有 EnterpriseChatCompletionReqVO，并直接调用企业会话与问答服务。
- [x] 删除旧商城分支、旧字段注入、可选权限和可选文档分支。
- [x] 删除失去业务调用的旧无过滤重载、旧分页方法及其 import。
- [x] 保留当前企业查询、错误状态、并发上传、发布过滤和Rerank行为；定向测试显式注入企业依赖。
- [x] 将清单中旧文件的引用清零后交接删除；不通过注释整段旧代码、返回固定错误或增加deprecated包装保留实现。

**交付：** 活跃控制器和共享服务只依赖企业业务；删除旧专用文件不会断开当前流程。

### Task 3：收敛前端和资源依赖

**Files:** ChatInputBox.vue、Sidebar.vue、Layout.vue、CustomerServiceChatPage.vue、AdminAccessPage.vue、KnowledgeBaseSelector.vue、MarkdownFileDrawer.vue、src/api/customerService.js、main.js、package.json/package-lock.json。

- [x] 删除模型菜单、联网状态、旧路由跳转和全部双模式props。
- [x] 保留输入、发送、停止、历史、来源和反馈，统一企业文案及默认知识库来源。
- [x] 移除持久化插件的注册、直接依赖和锁文件记录。
- [x] 在移除旧store前确认新页面无导入，在移除SVG前确认无动态名称消费者。

**交付：** 共享组件本身已成为企业组件，旧能力不再只是被CSS或条件隐藏。

### Task 4：配置、SQL与文件移除交接

**Files:** application.yml、pom.xml、企业组件的profile注解、SQL初始化脚本、两个verify脚本、清单中的45个移除文件和65个归档文件。

- [x] 完成配置合并及有效值对照，移除企业组件对旧模式选择的依赖，保留认证链无条件启用。
- [x] 完成企业SQL种子、推理字段和文档说明修改；保留当前业务库所有数据。
- [x] 用户明确授权本轮按计划移除；45 个源文件逐项从项目移除，65 个旧材料移至项目外归档，归档 SHA-256 全部匹配清单。
- [x] 逐项只读检查清单文件不存在、归档目标完整，并完成源码及构建产物残余扫描。
- [x] 物理移除完成；没有批量清除企业资料、评测记录或数据库内容。

**交付：** 旧专用文件实际移除，现有代码不再靠profile隐藏它们。

### Task 5：从无旧产物的目录构建并验证

**Files:** 现行测试、构建输出、运行服务；不修改业务规则。

- [x] 停止旧 JVM 后逐文件核对并清除旧后端 `target` 与前端 `dist` 生成物；没有使用 `mvn clean`、递归删除或批量目录移动。
- [x] 运行后端定向测试：EnterpriseOnlyRuntimeIntegrationTest、AuthenticationIntegrationTest、AiCustomerServiceControllerAccessTest、KnowledgeBaseControllerAccessTest、KnowledgeBaseServiceTest、EnterpriseConversationServiceTest、EnterpriseKnowledgeChatServiceTest、KnowledgeAccessServiceTest、KnowledgeDocumentServiceTest、CustomerServiceChunkUploadTest、HybridRetrievalServiceImplTest、FileDocumentGuardImplTest、EsChunkServiceImplTest、RerankServiceImplTest、KnowledgeDocumentReadersTest、AnswerSourceServiceTest、AnswerFeedbackServiceTest。
- [x] 对新增类型/DTO引用和构造器变化运行编译检查，再执行 `mvn -DskipTests package`；只从新制品启动。
- [x] 运行前端现行离线测试和 `npm run build -- --emptyOutDir=false`；目标dist已由用户清空，构建期间不触发目录清理。
- [x] 检查 JAR 条目不存在已删除类或旧配置，dist 无旧菜单、联网文案、旧页面 chunk；本机私有配置未打入 JAR。
- [x] 重启后记录最终 PID、新制品路径/摘要与启动时间；核对登录/退出、401/403、本人历史和来源、维护者授权文件列表。上传与发布写操作由对应服务测试覆盖，没有对现有语料做额外写入。
- [x] 在专用空库验证初始化及第二次执行不会改变人员/成员/发布指针；不触碰现有业务库做重建。
- [x] 若执行模型冒烟，只使用 HR08、IT05、MT02、VER03 共4个用例、5轮；VER03按既有定向fixture临时构造草稿并finally恢复。只对确认为TLS握手失败且无回答的请求重试一次，不因答错重复采样。
- [x] 不重跑完整60题，也不将现有CL01/SAFE05回答不足列为清理失败。

**交付：** 新构建与实际运行制品中无旧代码，企业流程及边界无回退。

### Task 6：同步现行说明与完成记录

**Files:** README.md、前端README、企业语料README、resume_draft.md、原计划/进度文件、清理结果记录。

- [x] 当前说明只讲企业知识库能力，API表与实际注册映射一致。
- [x] 旧历史文档标明归档位置；企业评测结果保留运行时间和对应版本，不修改旧结果凑分数。
- [x] 记录移除文件、归档材料、保留共享组件、构建验证、实际制品与少量冒烟结果。
- [x] 检查本地密钥未进入改动；保持用户未提交改动，不自动提交或推送。

**交付：** 文档、源码、配置和新制品说明一致。

## 8. 残余扫描与最终通过条件

在主源码/测试范围执行精确扫描；方案、历史报告、Git、node_modules、target和dist不混入源码计数。target/dist另做制品检查。

```powershell
rg -n '\b(ChatController|ChatService|ChatServiceImpl|AiChatReqVO|AIResponse|NewChatReqVO|RenameChatReqVO|AiCustomerServiceChatReqVO|NetworkSearchAdvisor|CustomChatMemoryAdvisor|CustomStreamLoggerAndMessage2DBAdvisor|CustomerServiceAdvisor|SearchResultDTO|SearXNGService|SearchResultContentFetcherService)\b' milan-ai-robot-springboot/src

rg -n 'SearXNG|searxng|general-chat|ObjectProvider|getIfAvailable|reasoningContent|@Profile' milan-ai-robot-springboot/src/main

rg -n 'useChatStore|persistedChatSelection|selectedModel|isNetworkSearch|showModelDropdown|showNetworkSearch|enterpriseMode|simpleMode|米兰数码商城|米兰AI.?机器人' milan-ai-robot-vue3/src

rg -n '米兰数码商城|searxng|application-dev|application-prod' sql scripts milan-ai-robot-springboot/src/main/resources

rg -n 'knowledgeBaseId\s*=\s*1L?|knowledgeBaseId:\s*1\b' milan-ai-robot-springboot/src/main/java milan-ai-robot-vue3/src
```

- [x] 被移除类型没有活跃引用；测试中若使用旧类名字符串做“不应注册”的反向断言，逐条登记允许项，不能import已删除类。
- [x] 所有源码命中逐条解释或移除，不能仅凭搜索命中数为零就断言运行安全。
- [x] 没有重复DTO、可选权限跳过、未经发布文件过滤的召回入口。
- [x] 当前企业核心DTO和共享依赖无误删，尤其是 NewChatRspVO、OkHttp、Guava及RAG线程池。
- [x] 新后端制品与前端产物中无旧类、旧页面和旧图标；实际进程使用该新制品。
- [x] 企业授权、会话归属、文档发布、检索、SSE、来源和反馈流程通过限定验收。
- [x] 原始业务数据、企业评测与本地密钥完整保留。
- [x] 清理结果不宣称修复问答模型本身的全部质量问题，不沿用已失效的旧联网性能数字。

清单文件移除、引用清零、配置收敛和新制品验证均已完成；限定冒烟中非完全正确的答案和一次追问改写回退已单独记录，不属于旧功能清理未完成项。
