## 问题1：米兰AI 是什么项目？

米兰AI 是一个基于 Spring AI 构建的 AI 智能客服应用，核心能力是「检索增强生成（RAG）」：

- 你把 Markdown 格式的问答文档上传到后台，系统自动解析、切分、向量化并存入向量库；
- 用户提问时，系统先从向量库检索出最相关的文档片段，拼进提示词交给大模型；
- 大模型基于这些上下文作答，并通过 SSE 流式返回给前端，实现打字机效果。

除知识库问答外，还支持多轮对话记忆和联网搜索。

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

目前只支持 **Markdown（.md）** 格式。

上传后系统会按标题层级和分割线自动切分文档，切出来的每个片段都会带上分类、标题、关联文件 ID 等元数据，方便后续按文件维度做删除和检索。

需要注意的是：从富文本编辑器或网页复制出来的 Markdown 往往带转义（比如 `\*\*`、`\---`），这些会被解析器当成普通文字，不会触发切分。建议上传前先确认标题是真正的 `## 标题` 格式。

---

## 问题4：上传的文件多久能处理完？

上传接口会先把文件落盘、写数据库记录（状态为「待处理」），然后立刻返回，向量化是**异步**执行的。

向量化过程中状态会变成「向量化中」，完成后变成「已完成」，失败则是「失败」。

**重要提示**：如果服务在向量化途中中断或重启，这条记录会永久停留在「向量化中」。此时普通删除会被拒绝，需要在删除确认框里选择「强制删除」来清理。

---

## 问题5：同一个文件重复上传会怎样？

不会产生重复数据。

系统对文档正文内容做 SHA-256 哈希，用哈希值作为向量 ID。只要内容不变，重复上传就会命中同一个 ID 从而执行更新，而不是新增。但文件存储表里仍会新增一条记录，也就是说：表里可能有两条记录，向量库里只有一份。

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
4. `mvn spring-boot:run`，默认端口 8080

**前端**

1. `npm install`
2. `npm run dev`，默认端口 5173
3. Vite 已配置 `/api` 代理到 `localhost:8080`，前端请求直接写 `/api/xxx` 即可

---

## 问题7：怎么调整模型的温度和名称？

在 `application-dev.yml` 的 `customer-service` 节点下：

- `model`：模型名称
- `temperature`：温度值，客服场景建议保持 0.0，保证回答稳定可复现

修改后需要重启后端生效。

---

## 问题8：配置项里有哪些容易踩的坑？

- **`application-secret.yml` 不要提交**：里面是 API Key，已加入 `.gitignore`。如果不小心推上去了，请立刻去模型服务商控制台重新生成。
- **Markdown 存储路径要用绝对路径**，Windows 下注意用双反斜杠转义。
- **分页参数有上限**：每页最多 100 条，超过会被参数校验拦下。
- **前端直接写死 `localhost:8080` 的地方**要改成走 `/api` 代理，否则部署到服务器后会请求到用户本机。
