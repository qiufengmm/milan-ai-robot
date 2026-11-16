package com.milanai.robot.service.impl;

import com.milanai.robot.config.RagProperties;
import com.milanai.robot.service.RerankUnavailableException;
import com.sun.net.httpserver.HttpServer;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.document.Document;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RerankServiceImplTest {
    private final ObjectMapper json = JsonMapper.builder().build();
    private final RagProperties properties = new RagProperties();
    private final AtomicReference<String> response = new AtomicReference<>("{\"output\":{\"results\":[{\"index\":1,\"relevance_score\":0.9},{\"index\":0,\"relevance_score\":0.2}]}}");
    private final AtomicReference<JsonNode> requestBody = new AtomicReference<>();
    private final AtomicReference<String> requestPath = new AtomicReference<>();
    private final AtomicReference<String> authorization = new AtomicReference<>();
    private final AtomicInteger status = new AtomicInteger(200);
    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicInteger delayMillis = new AtomicInteger();
    private final List<Document> candidates = List.of(
            new Document("a", "无理由退货七天", Map.of("mdStorageId", 42L, "knowledge_base_id", "7")),
            new Document("b", "质量问题退货三十天", Map.of("mdStorageId", 43L, "knowledge_base_id", "7")));
    private HttpServer server;
    private java.util.concurrent.ExecutorService httpExecutor;

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        httpExecutor = Executors.newCachedThreadPool();
        server.setExecutor(httpExecutor);
        server.createContext("/", exchange -> {
            calls.incrementAndGet();
            requestPath.set(exchange.getRequestURI().getPath());
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            requestBody.set(json.readTree(exchange.getRequestBody()));
            try {
                Thread.sleep(delayMillis.get());
                byte[] body = response.get().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(status.get(), body.length);
                exchange.getResponseBody().write(body);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
        httpExecutor.shutdownNow();
    }

    @Test
    void nativeRequestReordersOriginalDocumentsAndUsesConfiguredCredentials() {
        properties.getRerank().setApiUrl(origin() + "/custom-rerank");
        properties.getRerank().setApiKey("rerank-test-key");
        properties.getRerank().setTopN(1);

        List<Document> result = service("fallback-test-key", "https://unused.example/compatible-api/v1").rerank("质量问题", candidates, 0.0);

        assertThat(result).containsExactly(candidates.get(1), candidates.get(0));
        assertThat(result.getFirst()).isSameAs(candidates.get(1));
        assertThat(authorization).hasValue("Bearer rerank-test-key");
        assertThat(requestPath).hasValue("/custom-rerank");
        JsonNode body = requestBody.get();
        assertThat(body.path("model").asText()).isEqualTo("qwen3-rerank");
        assertThat(body.path("input").path("query").asText()).isEqualTo("质量问题");
        assertThat(body.path("input").path("documents").get(0).asText()).isEqualTo("无理由退货七天");
        assertThat(body.path("parameters").path("top_n").asInt()).isEqualTo(2);
        assertThat(body.path("parameters").path("return_documents").asBoolean()).isFalse();
    }

    @Test
    void defaultEndpointComesFromOpenAiOriginAndUsesFallbackKey() {
        List<Document> result = service("fallback-test-key", origin() + "/compatible-api/v1/").rerank("退货", candidates, 0.0);

        assertThat(result.getFirst()).isSameAs(candidates.get(1));
        assertThat(requestPath).hasValue("/api/v1/services/rerank/text-rerank/text-rerank");
        assertThat(authorization).hasValue("Bearer fallback-test-key");
    }

    @Test
    void zeroThresholdKeepsOnlyScoredCandidatesFromPartialResults() {
        response.set("{\"output\":{\"results\":[{\"index\":1,\"relevance_score\":0.9}]}}");

        assertThat(service("test", origin()).rerank("退货", candidates, 0.0))
                .containsExactly(candidates.get(1));
    }

    @Test
    void configuredRelevanceThresholdDropsLowScoringCandidates() {
        response.set("{\"output\":{\"results\":[{\"index\":1,\"relevance_score\":0.91},"
                + "{\"index\":0,\"relevance_score\":0.49}]}}");

        assertThat(service("test", origin()).rerank("质量问题", candidates, 0.5))
                .containsExactly(candidates.get(1));
    }

    @Test
    void explicitThresholdDeterminesWhichScoredCandidatesAreReturned() {
        properties.getRerank().setMinRelevanceScore(0.5);
        response.set("{\"output\":{\"results\":[{\"index\":1,\"relevance_score\":0.91},"
                + "{\"index\":0,\"relevance_score\":0.49}]}}");

        assertThat(service("test", origin()).rerank("质量问题", candidates, 0.0))
                .containsExactly(candidates.get(1), candidates.get(0));
    }

    @Test
    void configuredThresholdDoesNotAppendCandidatesWithoutScores() {
        response.set("{\"output\":{\"results\":[{\"index\":1,\"relevance_score\":0.91}]}}");

        assertThat(service("test", origin()).rerank("质量问题", candidates, 0.5))
                .containsExactly(candidates.get(1));
    }

    @Test
    void configuredThresholdReportsUnavailableWhenRerankDoesNotReturnScores() {
        response.set("{\"output\":{\"results\":[{\"index\":1}]}}");

        assertThatThrownBy(() -> service("test", origin()).rerank("质量问题", candidates, 0.5))
                .isInstanceOf(RerankUnavailableException.class);
    }

    @Test
    void configuredThresholdReportsUnavailableWhenRerankEndpointFails() {
        status.set(503);

        assertThatThrownBy(() -> service("test", origin()).rerank("质量问题", candidates, 0.5))
                .isInstanceOf(RerankUnavailableException.class);
    }

    @Test
    void zeroThresholdStillRejectsRemoteFailureInsteadOfReturningUnscoredCandidates() {
        status.set(503);

        assertThatThrownBy(() -> service("test", origin()).rerank("质量问题", candidates, 0.0))
                .isInstanceOf(RerankUnavailableException.class);
    }

    @Test
    void zeroThresholdStillRejectsMissingScores() {
        response.set("{\"output\":{\"results\":[{\"index\":1}]}}");

        assertThatThrownBy(() -> service("test", origin()).rerank("质量问题", candidates, 0.0))
                .isInstanceOf(RerankUnavailableException.class);
    }

    @Test
    void configuredThresholdReturnsNoCandidatesWhenAllScoresAreBelowThreshold() {
        response.set("{\"output\":{\"results\":[{\"index\":0,\"relevance_score\":0.2},"
                + "{\"index\":1,\"relevance_score\":0.49}]}}");

        assertThat(service("test", origin()).rerank("质量问题", candidates, 0.5)).isEmpty();
    }

    @Test
    void configuredThresholdReportsUnavailableWhenRerankIsDisabledOrUnconfigured() {
        properties.getRerank().setEnabled(false);
        assertThatThrownBy(() -> service("test", origin()).rerank("质量问题", candidates, 0.5))
                .isInstanceOf(RerankUnavailableException.class);

        properties.getRerank().setEnabled(true);
        assertThatThrownBy(() -> service("", origin()).rerank("质量问题", candidates, 0.5))
                .isInstanceOf(RerankUnavailableException.class);
        assertThat(calls).hasValue(0);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"output\":{\"results\":[{\"index\":1},{\"index\":1}]}}",
            "{\"output\":{\"results\":[{\"index\":2}]}}",
            "{\"output\":{\"results\":[{\"index\":-1}]}}",
            "{\"output\":{\"results\":[{\"index\":\"1\"}]}}",
            "{\"output\":{\"results\":[{\"index\":1.1}]}}",
            "{\"output\":{\"results\":[{}]}}",
            "{\"output\":{\"results\":[]}}",
            "{\"results\":[{\"index\":1}]}",
            "not-json"
    })
    void invalidResponseFailsWithoutReturningUnscoredCandidates(String body) {
        response.set(body);

        assertThatThrownBy(() -> service("test", origin()).rerank("退货", candidates, 0.5))
                .isInstanceOf(RerankUnavailableException.class);
        assertThat(calls).hasValue(1);
    }

    @Test
    void httpFailureRejectsUnscoredCandidates() {
        status.set(503);
        response.set("upstream unavailable");

        assertThatThrownBy(() -> service("test", origin()).rerank("退货", candidates, 0.5))
                .isInstanceOf(RerankUnavailableException.class);
        assertThat(calls).hasValue(1);
    }

    @Test
    void boundedTimeoutRejectsUnscoredCandidates() {
        properties.getRerank().setTimeoutSeconds(1);
        delayMillis.set(1800);
        long started = System.nanoTime();

        assertThatThrownBy(() -> service("test", origin()).rerank("退货", candidates, 0.5))
                .isInstanceOf(RerankUnavailableException.class);
        assertThat(calls).hasValue(1);
        assertThat((System.nanoTime() - started) / 1_000_000).isLessThan(1600);
    }

    @Test
    void zeroThresholdDoesNotBypassDisabledOrMissingCredentials() {
        properties.getRerank().setEnabled(false);
        assertThatThrownBy(() -> service("test", origin()).rerank("退货", candidates, 0.0))
                .isInstanceOf(RerankUnavailableException.class);
        properties.getRerank().setEnabled(true);
        assertThatThrownBy(() -> service("", origin()).rerank("退货", candidates, 0.0))
                .isInstanceOf(RerankUnavailableException.class);
        assertThat(service("test", origin()).rerank("退货", List.of(), 0.5)).isEmpty();
        assertThat(calls).hasValue(0);
    }

    private RerankServiceImpl service(String fallbackKey, String baseUrl) {
        return new RerankServiceImpl(new OkHttpClient(), json, properties, fallbackKey, baseUrl);
    }

    private String origin() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }
}
