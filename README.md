# 米兰AI 机器人 · Milan AI Robot

一个基于 **Spring Boot 4 + Spring AI 2 + Vue 3** 的 AI 机器人项目，包含两大能力：

- **智能对话**：多轮会话、SSE 流式输出、思考过程（推理内容）透出、历史会话管理、联网搜索
- **智能客服**：上传 Markdown 知识库 → 自动向量化入库 → 基于 RAG 的流式问答，配套文件管理后台

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
| RAG 问答 | 检索知识库后拼装提示词，流式返回 |

---

## 二、技术栈

**后端**

- Spring Boot `4.1.0` / Spring Framework `7.0.8` / JDK `21`
- Spring AI `2.0.1`（OpenAI 模型接入、Markdown 文档读取、pgvector 向量库）
- MyBatis-Plus `3.5.16` + PostgreSQL `17`
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

本项目已经按这个规律安排好了：`spring.ai.openai.base-url` 和 `customer-service.md-storage-path` 的默认值都放在 `application.yml`，可以直接被覆盖。

典型用法——使用模型服务商的专属部署地址，或把知识库文件固定存到某个目录：

```yaml
spring:
  ai:
    openai:
      api-key: sk-你的密钥
      base-url: https://你的专属网关.example.com/compatible-mode/v1

customer-service:
  md-storage-path: D:\\my-data\\milan-ai-robot
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
| `customer-service.md-storage-path` | Markdown 文件落盘目录，默认 `./data/md-storage`（相对启动目录），也可填绝对路径 |
| `customer-service.model` | 客服问答使用的模型 |
| `customer-service.temperature` | 温度值，默认 `0.0` 保证答案稳定 |
| `searxng.url / count` | 联网搜索服务地址与单次结果条数 |

---

## 七、接口一览

### 智能客服 `/customer-service`

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/md/upload` | 上传 Markdown 知识库文件（multipart/form-data，字段名 `file`） |
| POST | `/md/delete` | 删除文件，`{ id, force }`；`force=false` 时进行中的任务会被拒绝 |
| POST | `/md/list` | 分页查询，支持 `fileName` 模糊、`startDate` / `endDate` 时间范围 |
| POST | `/md/update` | 修改备注 |
| POST | `/chat/completion` | 客服问答，SSE 流式，返回**纯文本** |

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

---

## 八、演示数据

`test-data/` 目录下提供了可直接上传的 Markdown 知识库文件：

- `米兰AI项目.md`：本项目自助知识库，20 问，覆盖技术栈、文件格式、处理状态、幂等、启动、配置优先级、数据表、检索原理、删除与强制删除、备注规则、向量维度、SearXNG、SSE 格式差异、会话管理、密钥与端口排查
- `customer-service-faq.md`：虚构的客服问答知识库
- `pagination-test/`：10 个用于验证分页的 Markdown 文件

上传方式：进入「智能客服」页面 → 点击上传按钮选择文件。

---

## 九、注意事项

1. **向量维度必须三处一致**：`spring.ai.openai.embedding.dimensions`、`spring.ai.vectorstore.pgvector.dimensions`、`t_vector_store` 建表时的 `VECTOR(n)`。任意一处不一致都会导致写入或检索报错。
2. **更换向量化模型需要重建表**：维度变化后旧数据无法使用，需清空 `t_vector_store` 重新向量化。
3. **`application-secret.yml` 不要提交**：`.gitignore` 已做忽略，提交前可用 `git status` 复核。
4. **删除知识库文件会同时清理三处**：数据库记录、`t_vector_store` 中对应的向量、本地磁盘文件。
5. **联网搜索依赖 SearXNG**：未启动 SearXNG 时，联网相关能力会降级，不影响基础问答。
