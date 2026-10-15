## 问题1：米兰AI 是什么项目？

米兰AI 是一个基于 Spring AI 构建的全栈 AI 应用，包含两条相互独立的业务线：

- **智能客服（RAG 知识库问答）**：你把 Markdown 格式的问答文档上传到后台，系统自动解析、切分、向量化并存入向量库；用户提问时，先从向量库检索出最相关的文档片段，拼进提示词交给大模型，大模型基于这些上下文作答，通过 SSE 流式返回，实现打字机效果。
- **智能对话（多轮聊天）**：不依赖知识库，直接和大模型多轮对话，支持会话历史、会话列表、重命名、删除，以及联网搜索。

两条业务线共用同一个模型服务和数据库，但接口前缀不同：客服是 `/customer-service/**`，对话是 `/chat/**`。

---

## 问题2：用到了哪些技术栈？

**后端**

- Spring Boot 4.1.0、JDK 21
- Spring AI 2.0.1（ChatClient、Advisor、VectorStore、DocumentReader）
- PostgreSQL + pgvector（向量存储，HNSW 索引，COSINE_DISTANCE，维度 1024）
- MyBatis-Plus 3.5.16、HikariCP、p6spy
- Log4j2、OkHttp、Jsoup、Hutool、Guava

**前端**

- Vue 3.5、Vite 6
- Ant Design Vue 4.2、TailwindCSS 4
- Pinia、Axios、Vue Router
- `@microsoft/fetch-event-source`（SSE 流式接收）

**模型服务**

- 阿里云百炼（OpenAI 兼容接口）
- 对话模型与向量化模型可分别配置

---

## 问题3：支持哪几种文件格式做知识库？

目前只支持 **Markdown（.md）** 格式。上传时会校验扩展名，非 `.md` 文件会被直接拒绝。

上传后系统会按标题层级和分割线自动切分文档。切分出来的每个片段都会带上分类、标题、关联文件 ID、原始文件名等元数据，这样后续才能按文件维度做删除和检索。

**常见的"上传成功却检索不到"的原因**：从富文本编辑器或网页复制出来的 Markdown 往往带转义（比如 `\*\*`、`\---`），这些会被解析器当成普通文字，不会触发切分。建议上传前先确认标题是真正的 `## 标题` 格式，分割线是真正的 `---`。

---

## 问题4：上传的文件有哪几种状态？多久能处理完？

上传接口会先把文件落盘、写数据库记录（状态为「待处理」），然后**立刻返回**，向量化是异步执行的。

状态枚举：

| 值 | 含义 |
| --- | --- |
| 0 | 待处理 |
| 1 | 向量化中 |
| 2 | 已完成 |
| 3 | 失败 |

处理耗时主要取决于文档长度和向量化模型的响应速度，一般几秒到几十秒。

**重要提示**：如果服务在向量化途中中断或重启，这条记录会**永久停留在「向量化中」**。此时普通删除会被拒绝，需要在删除确认框里选择「强制删除」来清理。

---

## 问题5：同一个文件重复上传会怎样？

不会产生重复的向量数据。

系统对文档正文内容做 SHA-256 哈希，用哈希值作为向量 ID。只要内容不变，重复上传就会命中同一个 ID 从而执行更新，而不是新增。

但文件存储表里仍会新增一条记录，也就是说：**表里可能有两条记录，向量库里只有一份**。两条记录的落盘文件名不同（各自带独立的 UUID 前缀），删除其中一条不会影响另一条。

---

## 问题6：怎么启动这个项目？

**前置条件**

- JDK 21、Maven 3.9+
- Node.js 20+
- PostgreSQL 15+，并安装 pgvector 扩展
- 一个 OpenAI 兼容的模型服务（本项目默认用阿里云百炼）

**后端**

1. 建库并启用扩展：`CREATE EXTENSION IF NOT EXISTS vector;`
2. 把 API Key 写进 `src/main/resources/application-secret.yml`（该文件已加入 `.gitignore`，不会提交）
3. 在 `application-dev.yml` 里配好数据库连接和 Markdown 存储路径
4. `mvn spring-boot:run`，默认端口 8080，或直接运行 `MilanAiRobotSpringbootApplication`

**前端**

1. `npm install`
2. `npm run dev`，默认端口 5173
3. Vite 已配置 `/api` 代理到 `localhost:8080`，前端请求直接写 `/api/xxx` 即可

---

## 问题7：怎么调整模型的名称和温度？

在 `application-dev.yml` 的 `customer-service` 节点下：

- `model`：模型名称
- `temperature`：温度值，客服场景建议保持 `0.0`，保证回答稳定可复现

修改后需要**重启后端**生效。

---

## 问题8：几个配置文件之间有优先级吗？

有，而且和直觉相反。实测优先级从高到低是：

```
application-dev.yml  >  application-secret.yml（被 import）  >  application.yml
```

由此得出一条很重要的规律：

- 想让它**能被 `application-secret.yml` 覆盖**的属性，默认值必须写在 `application.yml` 里；
- 如果默认值写在了 `application-dev.yml` 里，它会把 `application-secret.yml` 压住，**覆盖就会失效**。

本项目已经按这个规律安排好了：`spring.ai.openai.base-url` 和 `customer-service.md-storage-path` 的默认值都放在 `application.yml`，可以直接被覆盖。

---

## 问题9：配置文件里有哪些容易踩的坑？

- **`application-secret.yml` 不要提交**：里面是 API Key，已加入 `.gitignore`。如果不小心推上去了，请立刻去模型服务商控制台重新生成。
- **向量维度必须三处一致**：`spring.ai.openai.embedding.dimensions`、`spring.ai.vectorstore.pgvector.dimensions`、`t_vector_store` 建表时的 `VECTOR(n)`。任意一处不一致都会导致写入或检索报错。
- **Markdown 存储路径建议用绝对路径**，Windows 下注意用双反斜杠转义。该目录必须与数据库 `file_path` 里记录的前缀一致，否则新上传的文件会落到另一个目录，出现「文件分裂成两处」。
- **分页参数有上限**：每页最多 100 条，超过会被参数校验拦下。
- **前端不要写死 `localhost:8080`**，要改成走 `/api` 代理，否则部署到服务器后会请求到用户本机。

---

## 问题10：项目里有哪些数据表？分别存什么？

一共 4 张表：

| 表名 | 用途 |
| --- | --- |
| `t_ai_customer_service_md_storage` | 知识库文件表：原始文件名、落盘文件名、绝对路径、文件大小、处理状态、备注、创建/更新时间 |
| `t_chat` | 会话表：会话 uuid、标题（首轮消息摘要）、创建/更新时间 |
| `t_chat_message` | 消息表：所属会话 uuid、消息正文、角色（user/assistant/system）、模型的思考过程 |
| `t_vector_store` | 向量表：文档片段正文、元数据（标题、分类、关联文件 ID、原始文件名）、1024 维向量 |

---

## 问题11：客服回答时是怎么用上知识库的？为什么感觉检索不准？

用户提问后，`CustomerServiceAdvisor` 会先拿着问题去向量库做相似度检索，**只取最相似的 3 条片段**（代码中 `topK(3)`），把它们拼进提示词再交给大模型。

所以如果答案不对，通常有这几个排查方向：

1. **知识点根本没被切出来**：回到问题 3，检查 Markdown 的标题和分割线是不是被转义了。
2. **切分粒度不合适**：一个片段里塞了太多主题，向量就会"平均化"，导致检索命中率下降。建议一个二级标题只讲一件事。
3. **只取 3 条**：如果答案分散在 4 个以上片段里，必然有遗漏。需要更多上下文时可以调大 `topK`。
4. **提问方式和文档表述差太远**：向量检索看的是语义相似度，口语化提问和书面化文档之间往往有距离。可以在文档里补充同义说法。

---

## 问题12：删除文件提示"正在处理中"，怎么办？

这是保护机制：状态为「待处理」（0）或「向量化中」（1）的文件不允许删除，避免在写入过程中产生脏数据。

如果一条记录**卡在「向量化中」一直不动**，多半是服务在向量化途中被中断或重启了，属于僵死记录。此时前端会弹二次确认，选择**强制删除**即可；接口层面对应传 `force: true`。

强制删除会跳过状态检查，同时清理数据库记录、对应的向量和本地磁盘文件。

---

## 问题13：删除知识库文件会连带清理哪些东西？

三处，缺一不可：

1. `t_ai_customer_service_md_storage` 里的数据库记录；
2. `t_vector_store` 里 `mdStorageId` 等于该文件 ID 的所有向量；
3. 本地磁盘上的那个 Markdown 文件。

如果磁盘文件因为权限等原因删不掉，接口只会记一条错误日志，不会让整个删除失败——这种情况下需要手动清理残留文件。

---

## 问题14：备注字段有什么限制？怎么清空？

- 长度上限 **500 个字符**，数据库、后端校验、前端输入框三处都是 500。
- 允许为空。在编辑框里**留空提交即可清空**原有备注，不需要逐字删除。
- 超出 500 字符时输入框会直接拦住打不进去；如果是绕过前端直接调接口，后端会返回 `10001` 参数错误并附带精确的长度提示。

---

## 问题15：更换向量化模型需要注意什么？

向量化模型一旦更换，**输出维度往往也跟着变**，而已经入库的旧向量是按旧维度算出来的，两者无法混用。

正确做法：

1. 同时修改 `spring.ai.openai.embedding.dimensions` 和 `spring.ai.vectorstore.pgvector.dimensions`；
2. 重建 `t_vector_store` 表（或改 `embedding` 列的维度）；
3. 把所有知识库文件重新上传一遍，重新向量化。

只改配置不重建表，会在写入时直接报维度不匹配的错误。

---

## 问题16：联网搜索是怎么工作的？没有启动会怎样？

联网搜索通过 SearXNG 提供，在 `application-dev.yml` 里配置：

- `searxng.url`：SearXNG 的搜索接口地址，默认 `http://localhost:8888/search`
- `searxng.count`：单次抓取的结果条数，默认 10

SearXNG 是可选依赖。**没有启动 SearXNG 时，联网相关能力会降级，不影响知识库问答和基础对话**。

---

## 问题17：前端是怎么接收流式输出的？两个接口有什么不同？

两个业务线都用 SSE（Server-Sent Events），前端通过 `@microsoft/fetch-event-source` 接收，但**返回体格式不同，不能照抄**：

- **客服端** `/customer-service/chat/completion`：返回**纯文本**，直接追加即可。
- **对话端** `/chat/completion`：返回 **JSON**，形如 `{"v":"...","reasoning":"..."}`，其中 `v` 是正文，`reasoning` 是模型的思考过程。前端需要先 `JSON.parse` 再分别渲染。

---

## 问题18：会话是怎么管理的？

`/chat` 下提供了一整套会话管理能力：

- `/new`：新建会话，返回会话 `uuid`，之后的消息都带着这个 uuid
- `/list`：会话列表分页，标题默认是首轮消息的摘要
- `/message/list`：查看某个会话内的历史消息，分页返回
- `/summary/rename`：给会话重命名
- `/delete`：删除会话

会话与消息通过 `uuid` 关联，而不是自增 ID，这样前端可以直接持有 uuid 发起请求，也便于后续做分享链接。

---

## 问题19：密钥应该放在哪里？

放在 `src/main/resources/application-secret.yml`，**不要**写进 `application-dev.yml`。该文件的定位就是"本地私有配置"：

```yaml
spring:
  ai:
    openai:
      api-key: ${DASHSCOPE_API_KEY:你的密钥}
```

写成 `${环境变量:默认值}` 的形式更好——部署时可以通过环境变量注入，本地开发则回退到默认值。

`.gitignore` 已经把 `application-secret.yml` 忽略了，但**提交前仍建议用 `git status` 复核一遍**。万一推上去了，请立刻去模型服务商控制台重新生成密钥。

---

## 问题20：端口起不来怎么办？

常见原因和排查顺序：

1. **端口被占用**：换端口（启动参数加 `--server.port=8090`）或结束占用进程。
2. **环境变量劫持**：有些终端环境会注入 `SERVER_PORT` 之类的环境变量，Spring Boot 的松散绑定会把它解读成 `server.port`，优先级高于配置文件，导致应用去监听一个你没配过的端口。启动时显式传 `--server.port=8080` 可以覆盖。
3. **数据库连不上**：检查 PostgreSQL 是否启动、`application-dev.yml` 里的连接串和账号密码是否正确、pgvector 扩展是否已安装。
4. **缺少 `application-secret.yml`**：该文件用 `optional:` 前缀导入，缺失不会导致启动失败，但会因为拿不到 api-key 而在首次调用模型时报错。
