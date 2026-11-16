package com.milanai.robot.service.impl;

import com.milanai.robot.config.RagProperties;
import com.milanai.robot.service.EsChunkService;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ObjectWriter;
import tools.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** File-owned chunk storage and native BM25 retrieval over the Elasticsearch REST API. */
@Service
public class EsChunkServiceImpl implements EsChunkService {
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final MediaType NDJSON = MediaType.get("application/x-ndjson; charset=utf-8");
    private static final TypeReference<Map<String, Object>> METADATA_TYPE = new TypeReference<>() {};

    private final OkHttpClient client;
    private final ObjectMapper mapper;
    private final ObjectWriter bulkWriter;
    private final HttpUrl indexUrl;
    private final String indexName;
    private final String analyzer;
    private final String searchAnalyzer;
    private final boolean enabled;

    public EsChunkServiceImpl(OkHttpClient client, ObjectMapper mapper, RagProperties properties) {
        if (client == null || mapper == null || properties == null || properties.getElasticsearch() == null) {
            throw new IllegalArgumentException("Elasticsearch dependencies must be provided");
        }
        RagProperties.Elasticsearch settings = properties.getElasticsearch();
        indexName = settings.getIndexName();
        if (indexName == null || !indexName.matches("[a-z0-9][a-z0-9._-]*")
                || indexName.getBytes(StandardCharsets.UTF_8).length > 255) {
            throw new IllegalArgumentException("Elasticsearch index name must name one lowercase index");
        }
        HttpUrl baseUrl = settings.getUrl() == null ? null : HttpUrl.parse(settings.getUrl());
        if (baseUrl == null || baseUrl.query() != null || baseUrl.fragment() != null) {
            throw new IllegalArgumentException("Elasticsearch URL must be an HTTP endpoint without query or fragment");
        }
        if (settings.getTimeoutSeconds() <= 0) {
            throw new IllegalArgumentException("Elasticsearch timeout must be positive");
        }
        analyzer = requiredSetting(settings.getAnalyzer(), "analyzer");
        searchAnalyzer = requiredSetting(settings.getSearchAnalyzer(), "search analyzer");
        indexUrl = baseUrl.newBuilder().addPathSegment(indexName).build();
        enabled = settings.isEnabled();
        this.mapper = mapper;
        bulkWriter = mapper.writer().without(SerializationFeature.INDENT_OUTPUT);
        this.client = client.newBuilder()
                .connectTimeout(settings.getTimeoutSeconds(), TimeUnit.SECONDS)
                .readTimeout(settings.getTimeoutSeconds(), TimeUnit.SECONDS)
                .writeTimeout(settings.getTimeoutSeconds(), TimeUnit.SECONDS)
                .callTimeout(settings.getTimeoutSeconds(), TimeUnit.SECONDS)
                .retryOnConnectionFailure(false)
                .build();
    }

    @Override
    public void ensureIndex() {
        if (!enabled) return;
        HttpResult exists = execute(new Request.Builder().url(indexUrl).head().build());
        if (exists.status() == 200) {
            validateMapping();
            return;
        }
        if (exists.status() != 404) throw httpFailure(exists.status());

        Map<String, Object> textMapping = Map.of("type", "text", "analyzer", analyzer,
                "search_analyzer", searchAnalyzer, "similarity", "BM25");
        Map<String, Object> fields = Map.of("content", textMapping, "title", textMapping,
                "mdStorageId", Map.of("type", "keyword"), "knowledge_base_id", Map.of("type", "keyword"),
                "metadata", Map.of("type", "object", "enabled", false));
        HttpResult created = execute(jsonRequest(indexUrl, "PUT", Map.of("mappings", Map.of("properties", fields))));
        if (created.status() == 400 || created.status() == 409) {
            JsonNode error = parseObject(created.body());
            if ("resource_already_exists_exception".equals(error.path("error").path("type").asString())) {
                validateMapping();
                return;
            }
        }
        JsonNode response = successfulJson(created);
        if (!trueBoolean(response.path("acknowledged"))
                || (response.has("shards_acknowledged") && !trueBoolean(response.path("shards_acknowledged")))) {
            throw invalidResponse("index creation was not acknowledged");
        }
    }

    @Override
    public void upsert(List<Document> documents) {
        if (documents == null) throw new IllegalArgumentException("Documents must not be null");
        requireEnabled();
        if (documents.isEmpty()) return;

        // Validate the complete batch first so an invalid owner never causes a partial write.
        StringBuilder payload = new StringBuilder();
        List<String> ids = new ArrayList<>(documents.size());
        for (Document document : documents) {
            if (document == null) throw new IllegalArgumentException("Document must not be null");
            String id = document.getId();
            if (id == null || id.isBlank() || id.getBytes(StandardCharsets.UTF_8).length > 512) {
                throw new IllegalArgumentException("Document ID must contain 1 to 512 UTF-8 bytes");
            }
            if (document.getText() == null || document.getText().isBlank()) {
                throw new IllegalArgumentException("Document content must not be blank");
            }
            Map<String, Object> metadata = new LinkedHashMap<>(document.getMetadata());
            String fileId = positiveId(metadata.get("mdStorageId"), "mdStorageId");
            String knowledgeId = positiveId(metadata.get("knowledge_base_id"), "knowledge_base_id");
            Object title = metadata.get("title");
            if (!(title instanceof String text) || text.isBlank()) title = metadata.get("originalFileName");
            Map<String, Object> source = new LinkedHashMap<>();
            source.put("content", document.getText());
            source.put("title", title instanceof String ? title : "");
            source.put("mdStorageId", fileId);
            source.put("knowledge_base_id", knowledgeId);
            source.put("metadata", metadata);
            payload.append(bulkWriter.writeValueAsString(Map.of("index", Map.of("_id", id)))).append('\n');
            payload.append(bulkWriter.writeValueAsString(source)).append('\n');
            ids.add(id);
        }
        HttpUrl bulkUrl = endpoint("_bulk").newBuilder().addQueryParameter("refresh", "wait_for").build();
        JsonNode response = successfulJson(execute(new Request.Builder().url(bulkUrl)
                .post(RequestBody.create(payload.toString(), NDJSON)).build()));
        JsonNode items = response.path("items");
        if (!falseBoolean(response.path("errors")) || !items.isArray() || items.size() != ids.size()) {
            throw invalidResponse("bulk write did not acknowledge every document");
        }
        for (int i = 0; i < ids.size(); i++) {
            JsonNode result = items.get(i).path("index");
            JsonNode status = result.path("status");
            if (!result.isObject() || !status.isIntegralNumber()
                    || (status.asInt() != 200 && status.asInt() != 201)
                    || !result.path("_id").isString() || !ids.get(i).equals(result.path("_id").asString())
                    || result.hasNonNull("error")
                    || (result.has("_index") && !indexName.equals(result.path("_index").asString()))
                    || (result.has("_shards") && !zeroInteger(result.path("_shards").path("failed")))) {
                throw invalidResponse("bulk item failed or did not match the requested document");
            }
        }
    }

    @Override
    public List<Document> searchByKeyword(String query, int topK, Long knowledgeBaseId, Set<Long> allowedFileIds) {
        String knowledgeId = positiveId(knowledgeBaseId, "knowledge_base_id");
        if (topK <= 0) throw new IllegalArgumentException("Keyword result limit must be positive");
        if (allowedFileIds == null) throw new IllegalArgumentException("Published file IDs must be provided");
        if (allowedFileIds.isEmpty()) return List.of();
        if (!enabled || query == null || query.isBlank()) return List.of();

        List<Map<String, Object>> filters = new ArrayList<>();
        filters.add(Map.of("term", Map.of("knowledge_base_id", knowledgeId)));
        List<String> fileIds = allowedFileIds.stream().sorted().map(String::valueOf).toList();
        filters.add(Map.of("terms", Map.of("mdStorageId", fileIds)));
        Map<String, Object> scopedMatch = Map.of("bool", Map.of(
                "must", List.of(Map.of("match", Map.of("content", Map.of("query", query)))),
                "filter", filters));
        JsonNode response = successfulJson(execute(jsonRequest(endpoint("_search"), "POST",
                Map.of("size", topK, "query", scopedMatch))));
        if (!falseBoolean(response.path("timed_out")) || !zeroInteger(response.path("_shards").path("failed"))) {
            throw invalidResponse("keyword search returned incomplete results");
        }
        JsonNode hits = response.path("hits").path("hits");
        if (!hits.isArray()) throw invalidResponse("keyword search is missing its hit list");
        List<Document> documents = new ArrayList<>();
        for (JsonNode hit : hits) {
            JsonNode source = hit.path("_source");
            JsonNode score = hit.path("_score");
            String id = hit.path("_id").asString("");
            if (!hit.path("_id").isString() || id.isBlank() || !source.isObject()
                    || !source.path("content").isString() || source.path("content").asString().isBlank()
                    || !score.isNumber() || !Double.isFinite(score.asDouble()) || score.asDouble() < 0) {
                throw invalidResponse("keyword hit is missing its document ID, content or score");
            }
            String fileId = responseOwner(source.path("mdStorageId"), "mdStorageId");
            String hitKnowledgeId = responseOwner(source.path("knowledge_base_id"), "knowledge_base_id");
            if (!knowledgeId.equals(hitKnowledgeId)) throw invalidResponse("keyword hit has a different knowledge owner");
            JsonNode storedMetadata = source.path("metadata");
            if (!storedMetadata.isMissingNode() && !storedMetadata.isObject()) {
                throw invalidResponse("keyword hit metadata must be an object");
            }
            Map<String, Object> metadata = storedMetadata.isObject()
                    ? new LinkedHashMap<>(mapper.convertValue(storedMetadata, METADATA_TYPE)) : new LinkedHashMap<>();
            // Canonical fields override stale or untrusted copies in the stored metadata object.
            metadata.put("mdStorageId", fileId);
            metadata.put("knowledge_base_id", hitKnowledgeId);
            if (source.path("title").isString()) metadata.put("title", source.path("title").asString());
            documents.add(Document.builder().id(id).text(source.path("content").asString())
                    .metadata(metadata).score(score.asDouble()).build());
        }
        return List.copyOf(documents);
    }

    @Override
    public void deleteByFileId(Long fileId) {
        String owner = positiveId(fileId, "mdStorageId");
        requireEnabled();
        HttpUrl url = endpoint("_delete_by_query").newBuilder().addQueryParameter("refresh", "true")
                .addQueryParameter("wait_for_completion", "true").build();
        JsonNode response = successfulJson(execute(jsonRequest(url, "POST",
                Map.of("query", Map.of("term", Map.of("mdStorageId", owner))))));
        if (!falseBoolean(response.path("timed_out")) || !zeroInteger(response.path("version_conflicts"))
                || !response.path("deleted").isIntegralNumber() || response.path("deleted").asLong(-1) < 0
                || !response.path("failures").isArray() || !response.path("failures").isEmpty()) {
            throw invalidResponse("file deletion was incomplete");
        }
    }

    private void validateMapping() {
        JsonNode response = successfulJson(execute(new Request.Builder().url(endpoint("_mapping")).get().build()));
        JsonNode fields = response.path(indexName).path("mappings").path("properties");
        JsonNode content = fields.path("content");
        if (!"text".equals(content.path("type").asString())
                || !analyzer.equals(content.path("analyzer").asString())
                || !searchAnalyzer.equals(content.path("search_analyzer").asString())
                || (content.has("similarity") && !"BM25".equals(content.path("similarity").asString()))
                || !"text".equals(fields.path("title").path("type").asString())
                || !"keyword".equals(fields.path("mdStorageId").path("type").asString())
                || !"keyword".equals(fields.path("knowledge_base_id").path("type").asString())) {
            throw invalidResponse("existing index mapping is incompatible with chunk retrieval");
        }
    }

    private Request jsonRequest(HttpUrl url, String method, Object body) {
        return new Request.Builder().url(url).method(method, RequestBody.create(mapper.writeValueAsString(body), JSON)).build();
    }

    private HttpResult execute(Request request) {
        try (Response response = client.newCall(request).execute()) {
            return new HttpResult(response.code(), response.body() == null ? "" : response.body().string());
        } catch (IOException e) {
            throw new IllegalStateException("Elasticsearch request failed", e);
        }
    }

    private JsonNode successfulJson(HttpResult response) {
        if (response.status() < 200 || response.status() >= 300) throw httpFailure(response.status());
        JsonNode object = parseObject(response.body());
        if (object.hasNonNull("error")) throw invalidResponse("request returned an error");
        return object;
    }

    private JsonNode parseObject(String body) {
        try {
            JsonNode value = mapper.readTree(body);
            if (value == null || !value.isObject()) throw invalidResponse("expected a JSON object");
            return value;
        } catch (IllegalStateException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new IllegalStateException("Elasticsearch returned malformed JSON", e);
        }
    }

    private HttpUrl endpoint(String operation) { return indexUrl.newBuilder().addPathSegment(operation).build(); }

    private void requireEnabled() {
        if (!enabled) throw new IllegalStateException("Elasticsearch writes are disabled");
    }

    private static String positiveId(Object value, String field) {
        String text = value == null ? "" : value.toString();
        try {
            if (!text.matches("[0-9]+") || Long.parseLong(text) <= 0) throw new NumberFormatException();
            return Long.toString(Long.parseLong(text));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(field + " must be a positive integer");
        }
    }

    private static String responseOwner(JsonNode value, String field) {
        if (!value.isString()) throw invalidResponse("keyword hit is missing string ownership fields");
        try {
            return positiveId(value.asString(), field);
        } catch (IllegalArgumentException e) {
            throw invalidResponse("keyword hit has invalid ownership fields");
        }
    }

    private static String requiredSetting(String value, String label) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Elasticsearch " + label + " is required");
        return value;
    }

    private static boolean trueBoolean(JsonNode node) { return node.isBoolean() && node.asBoolean(); }
    private static boolean falseBoolean(JsonNode node) { return node.isBoolean() && !node.asBoolean(); }
    private static boolean zeroInteger(JsonNode node) { return node.isIntegralNumber() && node.asLong(-1) == 0; }
    private static IllegalStateException invalidResponse(String reason) { return new IllegalStateException("Elasticsearch " + reason); }
    private static IllegalStateException httpFailure(int status) { return new IllegalStateException("Elasticsearch HTTP request failed with status " + status); }
    private record HttpResult(int status, String body) {}
}
