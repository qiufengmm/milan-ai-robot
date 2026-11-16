package com.milanai.robot.service.impl;

import com.milanai.robot.config.RagProperties;
import com.milanai.robot.service.RerankService;
import com.milanai.robot.service.RerankUnavailableException;
import lombok.extern.slf4j.Slf4j;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
public class RerankServiceImpl implements RerankService {
    private static final String NATIVE_PATH = "/api/v1/services/rerank/text-rerank/text-rerank";
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private final OkHttpClient client;
    private final ObjectMapper json;
    private final RagProperties properties;
    private final String openAiApiKey;
    private final String openAiBaseUrl;

    public RerankServiceImpl(OkHttpClient client, ObjectMapper json, RagProperties properties,
                            @Value("${spring.ai.openai.api-key:}") String openAiApiKey,
                            @Value("${spring.ai.openai.base-url:}") String openAiBaseUrl) {
        this.client = client;
        this.json = json;
        this.properties = properties;
        this.openAiApiKey = openAiApiKey;
        this.openAiBaseUrl = openAiBaseUrl;
    }

    @Override
    public List<Document> rerank(String query, List<Document> candidates, double minRelevanceScore) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        long started = System.nanoTime();
        RagProperties.Rerank config = properties.getRerank();
        if (!Double.isFinite(minRelevanceScore) || minRelevanceScore < 0.0 || minRelevanceScore > 1.0) {
            log.warn("RAG rerank mode=unavailable reason=invalid-score-threshold candidateCount={}", candidates.size());
            throw new RerankUnavailableException();
        }
        if (!config.isEnabled()) {
            throw unavailable(candidates.size(), "disabled", null, started);
        }
        try {
            String apiKey = hasText(config.getApiKey()) ? config.getApiKey() : openAiApiKey;
            HttpUrl endpoint = endpoint(config.getApiUrl());
            if (!hasText(apiKey) || endpoint == null || !hasText(query) || !hasText(config.getModel())) {
                throw unavailable(candidates.size(), "incomplete-configuration", null, started);
            }
            List<String> texts = candidates.stream().map(Document::getText).toList();
            Map<String, Object> payload = Map.of("model", config.getModel(),
                    "input", Map.of("query", query, "documents", texts),
                    "parameters", Map.of("top_n", candidates.size(), "return_documents", false));
            Request request = new Request.Builder().url(endpoint).header("Authorization", "Bearer " + apiKey)
                    .post(RequestBody.create(json.writeValueAsString(payload), JSON)).build();
            int timeout = Math.max(1, Math.min(config.getTimeoutSeconds(), 120));
            OkHttpClient boundedClient = client.newBuilder().callTimeout(timeout, TimeUnit.SECONDS)
                    .connectTimeout(timeout, TimeUnit.SECONDS).readTimeout(timeout, TimeUnit.SECONDS)
                    .writeTimeout(timeout, TimeUnit.SECONDS).build();
            try (Response response = boundedClient.newCall(request).execute()) {
                if (!response.isSuccessful() || response.body() == null) {
                    throw unavailable(candidates.size(), "http-status-" + response.code(),
                            response.code(), started);
                }
                JsonNode results = json.readTree(response.body().string()).path("output").path("results");
                List<Document> reordered = reorder(results, candidates, minRelevanceScore);
                log.info("RAG rerank mode=reranked candidateCount={} selectedCount={} minScore={} elapsedMs={}",
                        candidates.size(), reordered.size(), minRelevanceScore, elapsedMs(started));
                return reordered;
            }
        } catch (RerankUnavailableException exception) {
            throw exception;
        } catch (Exception exception) {
            // Exception text and response bodies can contain upstream secrets or user content.
            throw unavailable(candidates.size(), exception.getClass().getSimpleName(), null, started);
        }
    }

    private HttpUrl endpoint(String configuredUrl) {
        if (hasText(configuredUrl)) {
            return HttpUrl.get(configuredUrl);
        }
        if (!hasText(openAiBaseUrl)) {
            return null;
        }
        return HttpUrl.get(openAiBaseUrl).newBuilder().encodedPath(NATIVE_PATH)
                .query(null).fragment(null).username("").password("").build();
    }

    private List<Document> reorder(JsonNode results, List<Document> candidates, double minRelevanceScore) {
        if (!results.isArray() || results.isEmpty() || results.size() > candidates.size()) {
            throw new IllegalArgumentException("Invalid rerank result structure");
        }
        Set<Integer> seen = new HashSet<>();
        List<Document> reordered = new ArrayList<>(candidates.size());
        for (JsonNode result : results) {
            JsonNode index = result.path("index");
            if (!index.isIntegralNumber() || !index.canConvertToInt()) {
                throw new IllegalArgumentException("Invalid rerank index type");
            }
            int position = index.intValue();
            if (position < 0 || position >= candidates.size() || !seen.add(position)) {
                throw new IllegalArgumentException("Invalid or repeated rerank index");
            }
            JsonNode score = result.path("relevance_score");
            if (!score.isNumber() || !Double.isFinite(score.doubleValue())
                    || score.doubleValue() < 0.0 || score.doubleValue() > 1.0) {
                throw new IllegalArgumentException("Invalid rerank relevance score");
            }
            if (score.doubleValue() >= minRelevanceScore) {
                reordered.add(candidates.get(position));
            }
        }
        return List.copyOf(reordered);
    }

    private RerankUnavailableException unavailable(int candidateCount, String reason, Integer status, long started) {
        log.warn("RAG rerank mode=unavailable reason={} status={} candidateCount={} elapsedMs={}",
                reason, status, candidateCount, elapsedMs(started));
        return new RerankUnavailableException();
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static long elapsedMs(long started) {
        return (System.nanoTime() - started) / 1_000_000;
    }
}
