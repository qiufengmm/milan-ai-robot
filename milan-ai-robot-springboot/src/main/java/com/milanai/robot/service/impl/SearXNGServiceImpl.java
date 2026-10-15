package com.milanai.robot.service.impl;

import com.milanai.robot.model.dto.SearchResultDTO;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.milanai.robot.service.SearXNGService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

/**

 * @Version: v1.0.0
 * @Description: TODO
 **/
@Service
@Slf4j
public class SearXNGServiceImpl implements SearXNGService {

    @Resource
    private OkHttpClient  okHttpClient;
    @Resource
    private ObjectMapper objectMapper;
    @Value("${searxng.url}")
    private String searxngUrl;
    @Value("${searxng.count}")
    private int count;

    @Override
    public List<SearchResultDTO> search(String query) {
        // 构建 SearXNG API 请求 URL
        HttpUrl httpUrl = HttpUrl.parse(searxngUrl).newBuilder()
                .addQueryParameter("q", query) // 设置搜索关键词
                .addQueryParameter("format", "json") // 指定返回 JSON 格式
                .addQueryParameter("engines", "bing,sogou wechat") // 指定聚合的目标搜索引擎。逐个引擎实测（用 unresponsive_engines 字段区分"真失败"与"引擎名无效被兜底"）后只保留这两个：
                //   bing         — 约 0.2s 返回 10 条，稳定，是唯一的通用网页来源
                //   sogou wechat — 约 0.25s 返回 10 条，搜狗微信搜索，中文技术公众号文章质量好，
                //                  且结果 URL 同域名，抓取时连接可复用，把整体成功率从 83% 拉到 97%
                // 被排除的原因：
                //   sogou / baidu / quark — 国内网站本身可访问，但 SearXNG 抓取会触发反爬验证码，
                //                           随后被熔断为 "Suspended: CAPTCHA"，配了等于没配（这也是原著
                //                           配置里没放百度的原因，并非遗漏）
                //   360search — 能连通但快速失败、不返回结果
                //   presearch / seznam / mwmbl / encyclosearch / bpb / mojeek / crowdview / searchmysite /
                //   naver / yandex / fireball / mozhi / wolframalpha —— 实测返回 0 条
                //   另外原著配置里的 "right dao" 在 SearXNG 中并不存在，指定它会被兜底成默认引擎集，
                //   表现为"有结果"的假象，实际返回的是 bing/brave/google 等的混合结果
                .build();

        // 创建 HTTP GET 请求
        Request request = new Request.Builder()
                .url(httpUrl)
                .get()
                .build();

        // 发送 HTTP 请求
        try (Response response = okHttpClient.newCall(request).execute()) {
            // 判断请求是否成功
            if (response.isSuccessful()) {
                // 拿到返回结果
                String result = response.body().string();
                log.info("## SearXNG 搜索结果: {}", result);

                // 解析 JSON 响应
                JsonNode root = objectMapper.readValue(result, JsonNode.class);
                JsonNode results = root.get("results"); // 获取结果数组节点

                // 定义 Record 类型：用于临时存储分数和节点引用
                record NodeWithUrlAndScore(double score, JsonNode node) {}

                // 处理搜索结果流：
                // 1. 提取评分
                // 2. 按评分降序排序
                // 3. 限制返回结果数量
                List<NodeWithUrlAndScore> nodesWithScore = StreamSupport.stream(results.spliterator(), false)
                        .map(node -> {
                            // 只提取分数，避免构建完整对象
                            double score = node.path("score").asDouble(0.0); // 提取评分
                            return new NodeWithUrlAndScore(score, node);
                        })
                        .sorted(Comparator.comparingDouble(NodeWithUrlAndScore::score).reversed()) // 按评分降序
                        .limit(count) // 限制返回结果数量
                        .toList();

                // 转换为 SearchResult 对象集合
                return nodesWithScore.stream()
                        .map(n -> {
                            JsonNode node = n.node();
                            String originalUrl = node.path("url").asText(""); // 提取 URL
                            return SearchResultDTO.builder()
                                    .url(originalUrl)
                                    .score(n.score()) // 保留评分
                                    .build();
                        })
                        .collect(Collectors.toList());
            }
        } catch (Exception e) {
            log.error("", e);
        }
        // 返回空集合
        return Collections.emptyList() ;
    }

}

