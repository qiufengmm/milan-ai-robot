# 米兰AI 机器人 · Milan AI Robot

一个基于 **Spring Boot 4 + Spring AI 2 + Vue 3** 的 AI 机器人项目，包含两大能力：

- **智能对话**：多轮会话、SSE 流式输出、思考过程（推理内容）透出、历史会话管理、联网搜索
- **智能客服**：上传 Markdown / PDF / Word / TXT 知识库 → 自动向量化入库 → 基于 RAG 的流式问答，配套文件管理后台。当前演示场景为「米兰数码商城」电商客服：商品参数、选购建议、配送规则、退换货、发票、保修与常见售后问题

后端使用 Spring AI 对接 OpenAI 兼容接口（示例为阿里云百炼），向量存储使用 PostgreSQL + pgvector。

---

## 一、功能特性

### 智能对话（`/chat/**`）

| 能力 | 说明 |
| --- | --- |
| 多轮会话 | 每个会话一个 `uuid`，消息全部落库，可随时回看 |
| 流式输出 | `text/event-stream`，逐字返回 |
| 推理内容透出 | 返回体为 `{"v":"正文","reasoning":"思考过程"}`，前端可分开展示 |
| 历史管理 | 会话列表分页、消息分页、重命名标题、删除会话 |
| 联网搜索 | 接入 SearXNG，按需检索后注入上下文 |

### 智能客服（`/customer-service/**`）

| 能力 | 说明 |
| --- | --- |
| 知识库上传 | 上传 `.md` 文件，落盘 + 建记录，返回新记录 id |
| 异步向量化 | 事件驱动 + 独立线程池，不阻塞上传响应；失败自动回滚状态并清理文件 |
| 文件管理 | 分页 + 文件名模糊 + 时间范围条件查询、删除（含强制删除）、编辑备注 |
| 多知识库 | 商城为默认库，可创建独立知识库；文件上传、断点续传、检索和重建索引均按知识库隔离 |
| 混合 RAG | PGVector 语义召回 + Elasticsearch/IK 中文 BM25 召回，经 RRF 融合、百炼 Rerank 后取 TopN，SSE 返回 |
| 索引同步 | PostgreSQL outbox 跟踪 ES 写入、删除和重试；文件列表显示解析状态与检索索引状态 |

---

## 二、技术栈

**后端**

- Spring Boot `4.1.0` / Spring Framework `7.0.8` / JDK `21`
- Spring AI `2.0.1`（OpenAI 兼容模型接入、Markdown 文档读取、pgvector 向量库）
- MyBatis-Plus `3.5.16` + PostgreSQL `17`
- Elasticsearch `9.4.2` + analysis-ik `9.4.2`（BM25），RRF，百炼 `qwen3-rerank`
- Log4j2（已排除默认 Logback）、p6spy（SQL 打印）、Hutool、Guava
- OkHttp + Jsoup（联网检索）

**前端**

- Vue `3.5` + Vite `6` + Vue Router + Pinia
- Ant Design Vue `4.2`（按需自动引入）+ TailwindCSS `4`
- `@microsoft/fetch-event-source`（SSE）
- markdown-it + highlight.js（Markdown 渲染与代码高亮）

---

## 三、目录结构

```
project/
├── milan-ai-robot-springboot/          # 后端
│   ├── src/main/java/com/milanai/robot/
│   │   ├── MilanAiRobotSpringbootApplication.java
│   │   ├── controller/                 # AiCustomerServiceController、ChatController
│   │   ├── service/                    # 业务接口与实现
│   │   ├── advisor/                    # Spring AI Advisor（提示词拼装）
│   │   ├── listener/                   # 向量化异步事件监听
│   │   ├── mapper/  model/  utils/  config/
│   │   └── ...
│   ├── src/main/resources/
│   │   ├── application.yml             # 主配置（激活 dev、导入 secret）
│   │   ├── application-dev.yml         # 数据源 / 模型 / 存储路径
│   │   ├── application-secret.yml      # ⚠️ 敏感密钥，不进版本库
│   │   └── log4j2.xml
│   └── pom.xml
├── milan-ai-robot-vue3/                # 前端
│   ├── src/
│   │   ├── views/                      # ChatPage / CustomerServiceChatPage / Index
│   │   ├── components/                 # Sidebar、ChatInputBox、MarkdownFileDrawer 等
│   │   ├── api/                        # chat.js、customerService.js
│   │   ├── axios.js  router/  stores/
│   │   └── ...
│   ├── vite.config.js
│   └── package.json
├── sql/schema.sql                      # 数据库初始化脚本
├── test-data/                          # 演示用 Markdown 知识库
└── .gitignore
```

---

## 四、环境要求

| 组件 | 版本要求 |
| --- | --- |
| JDK | 21+ |
| Maven | 3.9+ |
| Node.js | 20+（推荐 22） |
| PostgreSQL | 16+ |
| pgvector | 0.7+ |
| Elasticsearch | 9.4.2（使用 docker/elasticsearch/ 中锁定的 IK 镜像） |
| SearXNG | 可选，联网搜索功能需要 |

---

## 五、快速开始

### 1. 准备数据库

```bash
# 建库
psql -U postgres -c "CREATE DATABASE robot;"

# 建扩展 + 建表
psql -U postgres -d robot -f sql/schema.sql
```

电商客服演示使用**独立库**，表结构与 `robot` 完全一致，避免电商知识库与课程/项目知识混在一起影响检索：

```bash
psql -U postgres -c "CREATE DATABASE milan_ecommerce;"
psql -U postgres -d milan_ecommerce -f sql/schema.sql
```

`application-dev.yml` 中 `datasource.url` 默认已指向 `milan_ecommerce`；想回到原来的通用知识库演示，把库名改回 `robot` 即可（原库数据不受影响）。

如果你已有第 14 章分片上传库，**不要再次执行初始化脚本覆盖表结构**。先单独备份数据库，再按顺序运行 `sql/migrate_chunk_upload.sql`、`sql/migrate_rag_knowledge_bases.sql`。脚本可重复运行；迁移后文件状态、ID、路径和向量归属会被保留。

### 启动项目专用 Elasticsearch

首次运行下载匹配版本的 IK 包并构建 ES 服务。服务只绑定本机 127.0.0.1:19200：

```powershell
Invoke-WebRequest -Uri "https://release.infinilabs.com/analysis-ik/stable/elasticsearch-analysis-ik-9.4.2.zip" -OutFile "docker/elasticsearch/elasticsearch-analysis-ik-9.4.2.zip"
docker compose -f docker/elasticsearch/compose.yml build
docker compose -f docker/elasticsearch/compose.yml up -d
Invoke-RestMethod "http://127.0.0.1:19200/_cluster/health"
```

Dockerfile 会核对 IK 官方 SHA-512 和 Elasticsearch 版本。ES 数据保存在独立命名卷 `milan_rag_elasticsearch_data_v1`。更多说明见 [docker/elasticsearch/README.md](docker/elasticsearch/README.md)。

### 2. 配置密钥

`application-dev.yml` 中**不包含任何密钥**。API Key 由 `application-secret.yml` 提供，该文件已加入 `.gitignore`，不会提交。

在 `milan-ai-robot-springboot/src/main/resources/` 下新建 `application-secret.yml`：

```yaml
spring:
  ai:
    openai:
      api-key: sk-你的密钥
```

`application.yml` 中通过 `optional:` 前缀导入，**文件缺失也能正常启动**（只是调用模型时会失败）：

```yaml
spring:
  config:
    import: optional:classpath:application-secret.yml
```

也支持用环境变量覆盖，优先级高于文件：

```bash
export DASHSCOPE_API_KEY=sk-你的密钥
```

> 在 `application-secret.yml` 中写成 `${DASHSCOPE_API_KEY:sk-默认值}` 即可实现两者兼容。

### 2.1 用 `application-secret.yml` 放本地私有配置

因为这个文件已被 `.gitignore` 忽略，除密钥外，**不想进版本库的配置都可以写在这里**。

但要注意配置优先级（高 → 低）：

```
application-dev.yml  >  application-secret.yml（被导入）  >  application.yml
```

由此得出一条规律：

- 想让它**能被 `application-secret.yml` 覆盖**的属性，默认值必须写在 `application.yml`（基础文件）里；
- 如果默认值写在 `application-dev.yml` 里，它会把 `application-secret.yml` 压住，**覆盖就会失效**。

本项目已经按这个规律安排好了：`spring.ai.openai.base-url` 和 `customer-service.file-storage-path`、`customer-service.chunk-path` 的默认值都放在 `application.yml`，可以直接被覆盖。

典型用法——使用模型服务商的专属部署地址，或把知识库文件固定存到某个目录：

```yaml
spring:
  ai:
    openai:
      api-key: sk-你的密钥
      base-url: https://你的专属网关.example.com/compatible-mode/v1

customer-service:
  file-storage-path: D:\\my-data\\milan-ai-robot
  chunk-path: D:\\my-data\\milan-ai-robot\\chunk
```

这样仓库里保留的是通用默认值，你的本地环境照常按自己的配置运行。

### 3. 启动后端

```bash
cd milan-ai-robot-springboot
./mvnw spring-boot:run
# 或以 IDE 方式运行 MilanAiRobotSpringbootApplication
```

默认端口 `8080`。

### 4. 启动前端

```bash
cd milan-ai-robot-vue3
npm install
npm run dev
```

默认端口 `5173`，浏览器打开 `http://localhost:5173`。

---

## 六、关键配置说明

`application-dev.yml` 中需要按自己的环境调整的项：

| 配置项 | 说明 |
| --- | --- |
| `spring.ai.openai.base-url` | OpenAI 兼容接口地址，默认阿里云百炼公共网关；若使用专属部署，改成自己的地址 |
| `spring.ai.openai.embedding.model` | 向量化模型，示例 `qwen3.7-text-embedding-flash` |
| `spring.ai.openai.embedding.dimensions` | 向量维度，**必须与 pgvector 配置和建表维度一致** |
| `spring.ai.vectorstore.pgvector.dimensions` | 同上，本项目为 `1024` |
| `spring.ai.vectorstore.pgvector.table-name` | 向量表名，默认 `t_vector_store` |
| `datasource.url / username / password` | 数据库连接，默认 `localhost:5432/robot` |
| `customer-service.file-storage-path` | 合并后的大文件落盘目录，默认 `./data/file-storage`（相对启动目录），也可填绝对路径 |
| `customer-service.chunk-path` | 分片小文件目录，默认 `./data/chunk`，每个文件一个以 MD5 命名的子目录，合并后自动清理 |
| `spring.servlet.multipart.max-file-size` | 单个分片大小上限，默认 `10MB`；**必须大于前端 `CHUNK_SIZE`（2MB）**，否则分片会被拒（413） |
| `customer-service.model` | 客服问答使用的模型 |
| 通用聊天模型菜单 | 默认 `qwen3.8-flash`；仍可在界面选择其他已配置模型 |
| `rag.elasticsearch.*` | ES 地址、索引名、IK 分词器与连接超时 |
| `rag.retrieval.*` | 向量/BM25 TopK、RRF 常数、完整评测证据日志开关；正文日志默认关闭 |
| `rag.rerank.*` | Rerank 模型 `qwen3-rerank`、最终 TopN 与超时；沿用现有百炼 Key |
| `customer-service.temperature` | 温度值，默认 `0.0` 保证答案稳定 |
| `searxng.url / count` | 联网搜索服务地址与单次结果条数 |

---

## 七、接口一览

### 智能客服 `/customer-service`

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | /knowledge-bases | 列出知识库 |
| POST | /knowledge-bases | 新建知识库 |
| PUT | /knowledge-bases/{id} | 编辑知识库名称和说明 |
| POST | /knowledge-bases/{id}/reindex | 按库重建 Elasticsearch 文档 |
| POST | /file/check | 检查当前库的秒传/续传状态；传 knowledgeBaseId、fileMd5 |
| POST | /file/upload-chunk | 上传分片；表单包含 knowledgeBaseId、chunk、fileMd5、fileName、fileSize、chunkNumber、totalChunks |
| POST | /file/merge-chunk | 合并并异步入库；传 knowledgeBaseId、fileMd5 |
| POST | /file/delete | 删除当前库文件；传 knowledgeBaseId、id、force |
| POST | /file/list | 当前知识库分页文件清单，含解析和索引状态 |
| POST | /file/update | 修改当前库文件备注 |
| POST | /chat/completion | 客服问答，传 knowledgeBaseId 和 message，SSE 返回纯文本 |

> 大文件上传走三步：`/file/check` → 并发调 `/file/upload-chunk` → `/file/merge-chunk`。
> 秒传（`exists=true` 且 `needUpload=false`）和断点续传（`uploadedChunks` 非空）分别跳过前两步和部分分片。

### 智能对话 `/chat`

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/new` | 新建会话，返回 `uuid` |
| POST | `/completion` | 对话，SSE 流式，返回 **JSON**（`{"v":"...","reasoning":"..."}`） |
| POST | `/list` | 会话列表分页 |
| POST | `/message/list` | 会话内消息分页 |
| POST | `/summary/rename` | 重命名会话标题 |
| POST | `/delete` | 删除会话 |

> ⚠️ 注意两个 SSE 接口的返回体格式不同：客服端是纯文本，对话端是 JSON。前端解析时不要照抄。

### 当前 AI 模型配置

| 用途 | 模型 | 说明 |
| --- | --- | --- |
| 通用聊天生成 | qwen3.8-flash | 前端首次进入及旧的失效默认项迁移到此模型；可切换菜单中的兼容模型 |
| 电商客服生成 | qwen3.8-flash | 0.0 温度，用户选定的知识库内回答 |
| 向量 Embedding | qwen3.7-text-embedding-flash | 1024 维；用于上传和实时查询 |
| 重排 | qwen3-rerank | 在 RRF 召回的前 10 个候选中重排，取前 3 个进入提示词 |

应用模型与 Codex 自身模型是两套设置；变更 Codex 模型不会替换本项目百炼配置。

---

## 八、演示数据

`test-data/` 目录下提供了可直接上传的 Markdown 知识库文件：

- `ecommerce/`：**电商客服演示知识库**（米兰数码商城，虚构），10 个文件共 90 个问答块，覆盖商品参数、支付发票、配送物流、退换货、保修与人工服务，配套 60 道评测题；资料清单、入库数据和评测结果见 `test-data/ecommerce/README.md`
- `米兰AI项目.md`：本项目自助知识库，20 问，覆盖技术栈、文件格式、处理状态、幂等、启动、配置优先级、数据表、检索原理、删除与强制删除、备注规则、向量维度、SearXNG、SSE 格式差异、会话管理、密钥与端口排查
- `customer-service-faq.md`：虚构的客服问答知识库（通用版，非电商场景）
- `pagination-test/`：10 个用于验证分页的 Markdown 文件

上传方式：进入「智能客服」页面 → 点击「上传问答文件」选择文件，页面会自动算 MD5、分片、并发上传并合并。小文件只有一个分片，也可以直接用接口传：

```bash
# 1. 先算文件 MD5 和字节数（Linux / macOS）
FILE=test-data/ecommerce/01-商品参数与选购.md
MD5=$(md5sum "$FILE" | cut -d' ' -f1)
SIZE=$(wc -c < "$FILE")

# 2. 上传分片（小于 2MB 的文件只有 1 个分片，chunkNumber=0、totalChunks=1）
curl -X POST -F "chunk=@$FILE" -F "fileMd5=$MD5" \
  -F "fileName=01-商品参数与选购.md" -F "fileSize=$SIZE" \
  -F "chunkNumber=0" -F "totalChunks=1" \
  http://localhost:8080/customer-service/file/upload-chunk

# 3. 合并分片，合并完成后自动触发向量化
curl -X POST -H "Content-Type: application/json" \
  -d "{\"fileMd5\":\"$MD5\"}" \
  http://localhost:8080/customer-service/file/merge-chunk
```

同一个文件再传一次会命中秒传（`/file/check` 返回 `needUpload=false`），不会重复入库、也不会重复向量化。

上传成功返回文件记录 ID，解析和向量化在后台执行；请在文件列表确认状态为「已完成」。空白文档或无法提取文字的扫描版 PDF 会标为失败，扫描件需先做 OCR。视频文件可以正常分片上传与合并（用于验证大文件链路），但解析不出文本，最终会停在「失败」，属预期行为。每次上传有独立向量归属，删除一个文件不会删除其他文件中相同正文的知识块。

---

## 九、注意事项

1. **向量维度必须三处一致**：`spring.ai.openai.embedding.dimensions`、`spring.ai.vectorstore.pgvector.dimensions`、`t_vector_store` 建表时的 `VECTOR(n)`。任意一处不一致都会导致写入或检索报错。
2. **更换向量化模型需要重建表**：维度变化后旧数据无法使用，需清空 `t_vector_store` 重新向量化。
3. **`application-secret.yml` 不要提交**：`.gitignore` 已做忽略，提交前可用 `git status` 复核。
4. **删除知识库文件会同时清理三处**：数据库记录、`t_vector_store` 中对应的向量、本地磁盘文件。
5. **联网搜索依赖 SearXNG**：未启动 SearXNG 时，联网相关能力会降级，不影响基础问答。
6. **客服链路只做「检索 + 拼提示词」**：`/customer-service/chat/completion` 没有挂对话记忆 Advisor，也没有消息落库，请求里的 `chatId` 目前未参与业务；因此客服问答是单轮的，刷新页面即丢失上下文。多轮记忆与消息持久化只在通用对话 `/chat/**` 上具备。
7. **客服没有接入订单、物流、售后等业务接口**：只能回答知识库中的商品与政策问题，查不到订单状态，也不能发起退款、改地址、转人工。提示词中已要求模型明确说明这些限制，不编造查询结果。
8. **提示词约束是回答策略，不是确定性校验**：它降低幻觉概率，但不等于政策规则引擎或幻觉拦截系统。
