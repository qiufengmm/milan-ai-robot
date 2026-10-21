# bench · 性能与效果实测探针

这里存放的是**简历里那些数字的原始出处**。不是事后编的，是跑出来的。

所有探针都是独立的 `main` 程序，不注册为 Spring Bean，不参与应用启动，不影响生产代码。

---

## 一、TokenBench：上下文 Token 压缩比

### 它在量什么

联网搜索会把抓到的网页正文拼进提示词。网页是 HTML，直接塞进去有大量 `<script>`、`<style>`、标签、空白，全是无效 Token。

这个探针对比两组的 Token 体积：

| 组 | 内容 |
|---|---|
| 对照组 | 抓到的**原始 HTML 全文**，直接按生产模板拼进提示词 |
| 实验组 | `Jsoup.parse(html).text()` **清洗后的正文**，按同一模板拼进提示词 |

两组都套同一个模板（复刻生产链路 `NetworkSearchAdvisor.buildContext()` 的结构），所以比出来的差额，就是「Jsoup 清洗到底省了多少 Token」。

### 为什么用 jtokkit 而不是 tiktoken

- **tiktoken 是 OpenAI 的 Python 库**，本项目是 JDK 21 服务，进程内没有 Python 运行时，调不了。
- **jtokkit 是 tiktoken 的 Java 移植**，jar 里直接打包了官方的 `.tiktoken` 词表文件，编码口径与 tiktoken 的 `cl100k_base` / `o200k_base` 一致。
- 它是 Spring AI 的传递依赖（`spring-ai-*` → `com.knuddels:jtokkit:1.1.0`），`pom.xml` 里没写一行，**词表在 jar 内，不需要联网下载**。

### 怎么证明真是 jtokkit 在算

`--selftest` 会打印运行时自证。这些值来自 JVM 反射和 classpath 资源，不是手写常量：

```
=========== 分词器运行时自证 ===========
Java 版本              : 21.0.10 (Oracle Corporation)
Encoding 接口全限定名  : com.knuddels.jtokkit.api.Encoding
Encoding 接口所在 jar  : file:/C:/Users/qiufengm/.m2/repository/com/knuddels/jtokkit/1.1.0/jtokkit-1.1.0.jar
运行时实现类           : com.knuddels.jtokkit.EncodingFactory$Cl100kGptBytePairEncoding
运行时实现类所在 jar   : file:/C:/Users/qiufengm/.m2/repository/com/knuddels/jtokkit/1.1.0/jtokkit-1.1.0.jar
词表 cl100k_base : jar!/com/knuddels/jtokkit/cl100k_base.tiktoken  字节数=1681126
词表 o200k_base : jar!/com/knuddels/jtokkit/o200k_base.tiktoken  字节数=3613922
--- 分词自检（同一段文本，两套词表结果应不同）---
  "hello world"  cl100k=2, o200k=2 token
  "你好世界"         cl100k=5, o200k=2 token
=======================================
```

三点值得注意：

1. **实现类叫 `EncodingFactory$Cl100kGptBytePairEncoding`** —— 明确是 jtokkit 的 cl100k 实现。
2. **jar 路径打印的是 `.m2` 里的 `jtokkit-1.1.0.jar`** —— 物理文件，骗不了人。
3. **`"你好世界"` 在 cl100k 是 5 token、在 o200k 是 2 token** —— 两套词表结果不同，说明确实是两份不同词表各自编码，不是同一份缓存糊弄。
4. **`"hello world"` 在 cl100k 得 2 token** —— 与 tiktoken 官方 cl100k_base 的公开参照值一致，说明词表加载正确。

### 实测结果

3 个 query / 29 个成功抓取的页面：

| 口径 | 原始 | 清洗后 | 降低 |
|---|---|---|---|
| jtokkit `cl100k_base` | 739,451 token | 61,109 token | **91.7%** |
| jtokkit `o200k_base` | 731,896 token | 52,422 token | **92.8%** |
| 字符数（参照） | — | — | 94.1% |

**Token 口径比字符口径低 2.4 个百分点**，所以两个数不能混用。简历上写的是 Token 口径的 91.7%。

### 口径局限性（必须自己心里有数）

`cl100k_base` / `o200k_base` 是 **OpenAI 系词表，不是 DeepSeek 官方 tokenizer**。实际调用的模型是 `deepseek-v4-flash-0731`，它的官方分词器词表不同，中文切分粒度和 OpenAI 有差异。

因此：

- **压缩比 91.7% 是可信的** —— 相对值，同口径前后对比，词表差异不会显著影响压缩比。
- **739,451 → 61,109 这两个绝对值只是近似** —— 按 OpenAI BPE 词表算的，不是 DeepSeek 厂商口径。

想拿 DeepSeek 官方 tokenizer 做交叉验证，需要从 HuggingFace 下载，本机网络不通（`curl` 返回 HTTP 000），所以没做。**这一点不要含糊过去，被问到就直说。**

### 怎么跑

```powershell
# PowerShell，只做分词器自证（不联网、不需要 Docker）
.\bench\run-bench.ps1 -Selftest

# 完整跑（需要 SearXNG 在 localhost:8888）
.\bench\run-bench.ps1
```

```bash
# Git Bash
./bench/run-bench.sh --selftest
./bench/run-bench.sh
```

`run-bench.ps1` 会自动做三件事：导出 Maven 依赖 classpath → 编译 → 运行。依赖没变时不会重复导出。

---

## 二、其他探针（待迁入）

`SearchBench.java`（联网搜索耗时）、`EngineProbe.java`（SearXNG 引擎可用性探测）目前还在系统临时目录：
`C:\Users\qiufengm\AppData\Local\Temp\probe\`

这两个的数字（耗时 17.65s → 5.46s、引擎从 14 个精简到 `bing + sogou wechat`、抓取成功率 72.5% → 82.5%）同样需要可复现的证据，后续一并搬进来。

---

## 三、不要把产物提交进仓库

`.classpath.txt`、`classes/`、`*.class` 都是本地产物，已在 `.gitignore` 中忽略。
