# 米兰企业知识库

基于 Java 21、Spring Boot 4.1、Spring AI 2.0 和 Vue 3 的企业内部知识问答项目，面向员工服务与 IT 支持。运行入口统一为企业知识库，登录、账号状态和知识库授权校验始终启用，启动时无需选择 profile。

员工在已授权知识库中提问、查看本人历史和回答来源，并提交反馈；维护者管理本库资料及发布版本；管理员管理账号、知识库与成员授权。助手依据当前发布资料解释流程，尚未接入 OA、账号重置或工单系统，不能代办审批或执行 IT 操作。

## 功能与权限

| 角色/流程 | 能力 |
| --- | --- |
| 普通员工（READER） | 访问已授权库、连续问答、管理本人会话、查看本人回答来源、提交反馈 |
| HR / IT 维护者（EDITOR） | 在授权库中上传资料、查看解析片段、发布/撤回/替换版本、处理反馈、重建检索索引 |
| 管理员（ADMIN） | 创建/停用账号、创建/编辑知识库、分配或撤销 READER / EDITOR 授权，并管理资料 |
| 文档生命周期 | 分片与断点续传、异步解析、pgvector / ES 索引同步；新问题只检索当前发布文件，草稿与历史版不进入召回 |
| 检索与回答 | pgvector + Elasticsearch/IK BM25，经 RRF 融合和 Rerank；SSE 持久化回答状态及实际来源 |
| 会话与追问 | 会话绑定知识库与员工；最多使用最近 6 组完整问答、12,000 字符历史理解追问，每轮重新检索 |

后端使用 MyBatis-Plus、PostgreSQL/pgvector、Elasticsearch 9.4.2 / analysis-ik 9.4.2；前端使用 Vue Router、Pinia、Ant Design Vue、TailwindCSS 和 Markdown 渲染。

## 本地启动

准备 JDK 21、Maven、Node.js、PostgreSQL + pgvector、Elasticsearch，以及可用的 OpenAI 兼容模型网关。Elasticsearch 的构建、端口和资源说明见 [本地 ES 指南](docker/elasticsearch/README.md)。

在仓库根目录，为新的独立演示库初始化完整企业表结构：

```powershell
createdb -U postgres milan_enterprise
psql -U postgres -d milan_enterprise -f sql/schema_enterprise.sql
```

已有数据库应先备份并核对所需迁移，不应重建现有业务数据。默认连接 `localhost:5432/milan_enterprise`，可通过 `MILAN_ENTERPRISE_DB_HOST`、`MILAN_ENTERPRISE_DB_PORT`、`MILAN_ENTERPRISE_DB_NAME` 和 `MILAN_ENTERPRISE_DB_USERNAME` 覆盖。

设置本地凭据。首次创建管理员还需显式开启初始化；仅在用户表为空时生效，后续启动不会重置密码：

```powershell
$env:MILAN_ENTERPRISE_DB_PASSWORD = '<数据库口令>'
$env:DASHSCOPE_API_KEY = '<模型网关 Key>'
$env:ENTERPRISE_BOOTSTRAP_ADMIN_ENABLED = 'true'
$env:ENTERPRISE_BOOTSTRAP_ADMIN_USERNAME = '<管理员账号>'
$env:ENTERPRISE_BOOTSTRAP_ADMIN_DISPLAY_NAME = '<管理员显示名>'
$env:ENTERPRISE_BOOTSTRAP_ADMIN_PASSWORD = '<至少12位且不超过72个UTF-8字节的密码>'
cd milan-ai-robot-springboot
mvn spring-boot:run "-Dspring-boot.run.arguments=--spring.config.additional-location=optional:file:./src/main/resources/application-secret.yml"
```

管理员创建后，将 `ENTERPRISE_BOOTSTRAP_ADMIN_ENABLED` 设回 `false`；其默认值即为 `false`。后端默认监听 `8080`。另开终端启动前端：

```powershell
cd milan-ai-robot-vue3
npm install
npm run dev
```

打开 `http://localhost:5173`，登录后从“账号与知识库授权”创建账号并授权。前端开发代理与生产部署说明见 [前端 README](milan-ai-robot-vue3/README.md)。虚构资料的导入步骤见 [企业演示资料](test-data/enterprise/README.md)。

## 配置与模型

统一配置位于 [application.yml](milan-ai-robot-springboot/src/main/resources/application.yml)，通过 `optional:classpath:application-secret.yml` 导入私有覆盖。Maven 会把被忽略的 `application-secret.yml` 排除在编译输出和 JAR 外；本机若用该文件保存数据库口令、网关 Key 或地址，按上面的启动命令从文件系统加载。发布部署应从环境变量或外部密钥配置注入凭据，不要将真实凭据写进文档或提交。

| 配置 | 默认值/含义 |
| --- | --- |
| `customer-service.model / temperature` | `qwen3.8-flash` / `0.0`，由服务端固定 |
| `spring.ai.openai.embedding.model / dimensions` | `qwen3.7-text-embedding-flash` / `1024` |
| `spring.ai.vectorstore.pgvector` | `t_vector_store`、1024 维、HNSW / COSINE_DISTANCE；不自动初始化表结构 |
| `rag.elasticsearch.url / index-name` | `http://localhost:19200` / `milan_enterprise_chunks_v1` |
| `rag.retrieval` | 向量、BM25、融合候选各 TopK=10，RRF k=60；证据正文日志默认关闭 |
| `rag.rerank` | `qwen3-rerank`，TopN=3，最低相关分 `0.50` |
| `customer-service.file-storage-path / chunk-path` | `./data/enterprise/file-storage` / `./data/enterprise/chunk`，相对后端启动目录 |
| `enterprise.bootstrap-admin.*` | 显式启用且用户表为空时初始化管理员 |

Embedding 模型维度、pgvector 配置与 `t_vector_store` 的向量列必须一致；更换模型或维度需要单独规划数据迁移与重新索引。模型配置在服务端，界面不提供模型或联网开关。

## 接口契约

下表为后端路径；前端通过 `/api` 前缀代理访问。调用方先获取 `GET /auth/csrf`，保留 Session Cookie，并在写请求中携带返回的 CSRF 请求头。登录后重新获取 CSRF token。业务接口要求登录，并继续校验账号状态、知识库授权和会话归属。

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| GET | `/auth/csrf`、`/auth/me` | CSRF token、当前身份 |
| POST | `/auth/login`、`/auth/logout` | 登录、退出 |
| POST | `/chat/new` | 使用显式 `knowledgeBaseId` 创建本人会话 |
| GET | `/chat/{uuid}` | 读取本人会话 |
| POST | `/chat/list`、`/chat/message/list` | 本人会话、消息分页 |
| POST | `/chat/summary/rename`、`/chat/delete` | 本人会话重命名、删除 |
| POST | `/customer-service/chat/completion` | 企业问答 SSE |
| GET | `/customer-service/chat/sources/{answerId}` | 本人回答的来源快照，按当前权限校验 |
| GET / POST | `/customer-service/knowledge-bases` | 可访问库列表 / 管理员建库 |
| PUT | `/customer-service/knowledge-bases/{id}` | 管理员编辑知识库 |
| POST | `/customer-service/knowledge-bases/{id}/reindex` | 维护者重建本库 ES 索引 |
| POST | `/customer-service/file/check`、`/customer-service/file/upload-chunk`、`/customer-service/file/merge-chunk`、`/customer-service/file/list`、`/customer-service/file/update`、`/customer-service/file/delete` | 显式携带 `knowledgeBaseId` 的文件流程 |
| GET | `/customer-service/documents` | 维护者按 `knowledgeBaseId` 查看逻辑文档与版本 |
| POST | `/customer-service/documents/{documentId}/publish`、`/customer-service/documents/{documentId}/withdraw` | 发布所选 `fileId`、撤回当前版本 |
| GET | `/customer-service/documents/{documentId}/files/{fileId}/chunks` | 维护者查看解析片段 |
| GET / POST | `/admin/users` | 管理员列出/创建账号 |
| PATCH | `/admin/users/{userId}/enabled` | 启用/停用账号 |
| GET / PUT / DELETE | `/admin/knowledge-bases/{knowledgeBaseId}/members` | 查询/授予/撤销库授权 |
| POST | `/customer-service/chat/feedback/{answerId}`、`/customer-service/chat/feedback/{feedbackId}/resolve` | 本人提交反馈、维护者记录处理结果 |
| GET | `/customer-service/chat/feedback/mine`、`/customer-service/chat/feedback/knowledge-bases/{knowledgeBaseId}` | 本人反馈、本库反馈 |

Completion 请求只包含下列业务字段，知识库从本人会话反查：

```json
{
  "chatId": "<会话UUID>",
  "requestId": "<本轮幂等键>",
  "message": "<本轮问题>"
}
```

流事件为 `meta / sources / delta / done / error`；回答落库成功后才发送 `done`。文件请求必须明确指定已授权知识库，缺少 ID 不会自动选择某个库。资料上传并完成解析、PG/ES 索引后，还需维护者发布，才能用于新问答；删除操作只允许符合规则的草稿，不能绕过当前版和历史版保护。

## 演示资料与历史证据

[test-data/enterprise](test-data/enterprise/README.md) 保留 12 份逻辑资料、15 个文件版本的虚构语料及全部历史评测。2026-11-15 19:29 的历史 60 题结果为检索 54/55、答案 53/55、来源支持 52/52、权限 4/4；完整回答、版本和判分边界见 [历史全量报告](test-data/enterprise/eval/eval_report.md)。20:13 的 [8 题定向复测](test-data/enterprise/eval/issues_retest_report_20261115_201353.md) 独立保存，CL01、SAFE05 仍未通过，追问改写仍存在回退和部分来源外推断。这些数字对应清理前的服务版本，不能作为本次清理后的新成绩。

本次清理后另跑了 4 个指定用例、5 轮限定冒烟；4 个用例都收到完整回答，检索 5/5，答案 4/5 完全正确、1 题部分正确，来源支持 5/5。范围、VER03 状态恢复和改写回退记录见[限定冒烟报告](test-data/enterprise/eval/cleanup_smoke_report_20261115.md)，不能据此推算整体准确率。

旧教程与旧演示材料归档于项目外 `F:/code/qiufengm-ai-robot-legacy-archive-20261115/materials`，用于开发追溯。清理范围见 [企业唯一运行流程清理方案](docs/superpowers/plans/2026-11-15-enterprise-only-cleanup.md)。小规模虚构语料评测不代表生产准确率；模型回答仍需按问题和来源人工复核。
