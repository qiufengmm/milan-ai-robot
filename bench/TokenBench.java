import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingRegistry;
import com.knuddels.jtokkit.api.EncodingType;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.jsoup.Jsoup;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

/**
 * Token 压缩比实测探针（Java + jtokkit）。
 *
 * <h3>这个探针在证明什么</h3>
 * 对比「联网搜索结果注入提示词」在清洗前后的 Token 体积：
 * <pre>
 *   对照组 = 把抓到的原始 HTML 全文直接拼进提示词（不做 Jsoup 清洗）
 *   实验组 = Jsoup.parse(html).text() 清洗后的正文拼进提示词
 * </pre>
 * 两者都按生产链路 NetworkSearchAdvisor.buildContext() 的模板结构拼装后再分词，
 * 这样比出来的才是「清洗到底省了多少 Token」，而不是随便拿两段文本对比。
 *
 * <h3>为什么是 jtokkit 而不是 tiktoken</h3>
 * tiktoken 是 OpenAI 的 Python 库，本项目是 JDK 21 服务，进程内没有 Python 运行时。
 * jtokkit 是 tiktoken 的 Java 移植，jar 里直接打包了官方的 .tiktoken 词表文件，
 * 编码口径与 tiktoken 的 cl100k_base / o200k_base 相同。
 *
 * <h3>怎么证明真是 jtokkit 在算</h3>
 * 加 --selftest 参数，会在分词前打印运行时自证信息：实现类、jar 物理路径、
 * 词表资源路径与字节数。这些值来自 JVM 运行时反射与 classpath 资源，无法伪造。
 *
 * <h3>口径局限性（不要隐瞒）</h3>
 * cl100k_base / o200k_base 是 OpenAI 系词表，不是 DeepSeek 官方 tokenizer。
 * 压缩比是相对值，同口径前后对比成立；绝对 token 数只是按 OpenAI 词表算的近似值。
 *
 * <h3>用法</h3>
 * <pre>
 *   java -cp &lt;classpath&gt; TokenBench                       # 跑默认 3 个 query
 *   java -cp &lt;classpath&gt; TokenBench "Spring AI 框架介绍"    # 跑指定 query
 *   java -cp &lt;classpath&gt; TokenBench --selftest             # 只做分词器自证，不联网
 * </pre>
 */
public class TokenBench {

    static final String SEARXNG = "http://localhost:8888/search";
    static final String ENGINES = System.getProperty("bench.engines", "bing,sogou wechat");
    static final int COUNT = 10;
    static final ObjectMapper OM = new ObjectMapper();

    static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build();

    static final ExecutorService HTTP_POOL = Executors.newFixedThreadPool(20);
    static final ExecutorService CLEAN_POOL = Executors.newFixedThreadPool(8);

    static final EncodingRegistry REGISTRY = Encodings.newDefaultEncodingRegistry();
    static final Encoding CL100K = REGISTRY.getEncoding(EncodingType.CL100K_BASE);
    static final Encoding O200K = REGISTRY.getEncoding(EncodingType.O200K_BASE);

    record Hit(String url, double score) {}
    record Page(String url, String html) {}

    public static void main(String[] args) throws Exception {
        List<String> queries = new ArrayList<>(List.of(
                "Spring AI 框架介绍",
                "Java 21 虚拟线程",
                "pgvector 向量检索"
        ));
        boolean selftestOnly = false;

        for (String a : args) {
            if ("--selftest".equals(a)) {
                selftestOnly = true;
            } else {
                queries = new ArrayList<>(List.of(args));
                break;
            }
        }

        // 无论跑哪个模式，都先把「谁在算 token」这件事打出来
        printTokenizerEvidence();

        if (selftestOnly) {
            System.exit(0);
        }

        long totRawCl = 0, totCleanCl = 0, totRawO = 0, totCleanO = 0;
        long totRawChar = 0, totCleanChar = 0;
        int totPages = 0;

        for (String q : queries) {
            List<Hit> hits = search(q);
            List<Page> pages = batchFetch(hits);

            List<Page> ok = pages.stream().filter(p -> p.html() != null && !p.html().isBlank()).toList();
            if (ok.isEmpty()) {
                System.out.printf("[%s] 无成功抓取的页面%n", q);
                continue;
            }

            // 实验组：Jsoup 清洗后的正文
            List<String> cleaned = CLEAN_POOL.submit(() ->
                    ok.stream().map(p -> Jsoup.parse(p.html()).text()).collect(Collectors.toList())
            ).get();

            // 按生产链路 buildContext() 的结构拼装
            StringBuilder rawCtx = new StringBuilder();
            StringBuilder cleanCtx = new StringBuilder();
            for (int i = 0; i < ok.size(); i++) {
                rawCtx.append(String.format("### 来源 %d | 相关性: %s%n- 页面链接: %s %n- 页面文本:%n%s%n%n",
                        i + 1, ok.get(i).url(), ok.get(i).url(), ok.get(i).html()));
                cleanCtx.append(String.format("### 来源 %d | 相关性: %s%n- 页面链接: %s %n- 页面文本:%n%s%n%n",
                        i + 1, ok.get(i).url(), ok.get(i).url(), cleaned.get(i)));
            }

            String rawStr = rawCtx.toString();
            String cleanStr = cleanCtx.toString();

            int rawCl = CL100K.countTokens(rawStr);
            int cleanCl = CLEAN_POOL.submit(() -> CL100K.countTokens(cleanStr)).get();
            int rawO = O200K.countTokens(rawStr);
            int cleanO = CLEAN_POOL.submit(() -> O200K.countTokens(cleanStr)).get();

            totRawCl += rawCl; totCleanCl += cleanCl;
            totRawO += rawO; totCleanO += cleanO;
            totRawChar += rawStr.length(); totCleanChar += cleanStr.length();
            totPages += ok.size();

            System.out.printf("[%s] 成功 %d/%d 页 | cl100k: %d -> %d token (降 %.1f%%) | o200k: %d -> %d token (降 %.1f%%) | 字符: %d -> %d (降 %.1f%%)%n",
                    q, ok.size(), pages.size(),
                    rawCl, cleanCl, pct(rawCl, cleanCl),
                    rawO, cleanO, pct(rawO, cleanO),
                    rawStr.length(), cleanStr.length(), pct(rawStr.length(), cleanStr.length()));
        }

        System.out.println("================ 汇总 ================");
        System.out.printf("样本: %d 个 query, %d 个成功抓取的页面%n", queries.size(), totPages);
        System.out.printf("[jtokkit cl100k_base] 原始 %d -> 清洗后 %d token, 降低 %.1f%%%n",
                totRawCl, totCleanCl, pct(totRawCl, totCleanCl));
        System.out.printf("[jtokkit o200k_base]  原始 %d -> 清洗后 %d token, 降低 %.1f%%%n",
                totRawO, totCleanO, pct(totRawO, totCleanO));
        System.out.printf("[字符数口径参照]       原始 %d -> 清洗后 %d 字符, 降低 %.1f%%%n",
                totRawChar, totCleanChar, pct(totRawChar, totCleanChar));

        HTTP_POOL.shutdownNow();
        CLEAN_POOL.shutdownNow();
        System.exit(0);
    }

    /**
     * 打印分词器的运行时自证信息。
     * <p>
     * 这些值全部来自 JVM 运行时（反射 + classpath 资源），不依赖任何我手写的常量，
     * 所以它能证明「token 到底是谁算的」，而不是只凭一句注释。
     */
    static void printTokenizerEvidence() throws Exception {
        System.out.println("=========== 分词器运行时自证 ===========");
        System.out.println("Java 版本              : " + System.getProperty("java.version")
                + " (" + System.getProperty("java.vendor") + ")");

        // Encoding 是 jtokkit 的接口，它的 protectionDomain 直接指向承载它的 jar
        Class<?> encodingInterface = Encoding.class;
        System.out.println("Encoding 接口全限定名  : " + encodingInterface.getName());
        System.out.println("Encoding 接口所在 jar  : "
                + encodingInterface.getProtectionDomain().getCodeSource().getLocation());

        // 实际使用的实现类
        System.out.println("运行时实现类           : " + CL100K.getClass().getName());
        System.out.println("运行时实现类所在 jar   : "
                + CL100K.getClass().getProtectionDomain().getCodeSource().getLocation());

        // 词表文件：打包在 jtokkit jar 内，不联网也能加载
        for (String name : List.of("cl100k_base", "o200k_base")) {
            String resource = "com/knuddels/jtokkit/" + name + ".tiktoken";
            try (InputStream in = Encoding.class.getClassLoader().getResourceAsStream(resource)) {
                if (in == null) {
                    System.out.println("词表 " + name + " : 未找到（异常）");
                } else {
                    System.out.println("词表 " + name + " : jar!/" + resource + "  字节数=" + in.available());
                }
            }
        }

        System.out.println("--- 分词自检（同一段文本，两套词表结果应不同）---");
        String probe = "hello world";
        String zhProbe = "你好世界";
        System.out.printf("  %-14s cl100k=%d, o200k=%d token%n", "\"" + probe + "\"",
                CL100K.countTokens(probe), O200K.countTokens(probe));
        System.out.printf("  %-14s cl100k=%d, o200k=%d token%n", "\"" + zhProbe + "\"",
                CL100K.countTokens(zhProbe), O200K.countTokens(zhProbe));
        System.out.println("  参照值：tiktoken 的 cl100k_base 对 \"hello world\" 得 2 token。");
        System.out.println("  若上面 cl100k 一行也是 2，说明词表加载正确、口径一致。");
        System.out.println("=======================================");
    }

    static double pct(long raw, long clean) {
        return raw == 0 ? 0 : (1.0 - (double) clean / raw) * 100;
    }

    static List<Hit> search(String q) {
        HttpUrl u = HttpUrl.parse(SEARXNG).newBuilder()
                .addQueryParameter("q", q)
                .addQueryParameter("format", "json")
                .addQueryParameter("engines", ENGINES)
                .build();
        try (Response r = CLIENT.newCall(new Request.Builder().url(u).get().build()).execute()) {
            if (!r.isSuccessful() || r.body() == null) {
                System.out.printf("[%s] SearXNG 返回 HTTP %d%n", q, r.code());
                return List.of();
            }
            JsonNode root = OM.readValue(r.body().string(), JsonNode.class);
            JsonNode results = root.get("results");
            if (results == null || results.isNull()) return List.of();
            return StreamSupport.stream(results.spliterator(), false)
                    .map(n -> new Hit(n.path("url").asText(""), n.path("score").asDouble(0.0)))
                    .filter(h -> h.url() != null && !h.url().isBlank())
                    .sorted(Comparator.comparingDouble(Hit::score).reversed())
                    .limit(COUNT)
                    .toList();
        } catch (Exception e) {
            // SearXNG 没起来时不要整个探针崩掉：明确提示，跳过这个 query。
            // （Docker Desktop 未启动是最常见的原因）
            System.out.printf("[%s] SearXNG 不可达：%s —— 确认 Docker 里 searxng 容器已启动%n",
                    q, e.getMessage());
            return List.of();
        }
    }

    static List<Page> batchFetch(List<Hit> hits) {
        List<CompletableFuture<Page>> futures = hits.stream()
                .map(h -> CompletableFuture
                        .supplyAsync(() -> fetchOne(h), HTTP_POOL)
                        .completeOnTimeout(new Page(h.url(), ""), 7, TimeUnit.SECONDS)
                        .exceptionally(e -> new Page(h.url(), "")))
                .toList();
        CompletableFuture<Void> all = CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
        return all.thenApply(v -> futures.stream().map(CompletableFuture::join).toList()).join();
    }

    static Page fetchOne(Hit h) {
        Request req = new Request.Builder()
                .url(h.url())
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .header("Accept", "text/html")
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            if (!resp.isSuccessful() || resp.body() == null) return new Page(h.url(), "");
            return new Page(h.url(), resp.body().string());
        } catch (Exception e) {
            return new Page(h.url(), "");
        }
    }
}
