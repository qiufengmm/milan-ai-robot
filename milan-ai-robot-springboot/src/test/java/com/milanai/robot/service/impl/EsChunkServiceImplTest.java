package com.milanai.robot.service.impl;

import com.milanai.robot.config.RagProperties;
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
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

class EsChunkServiceImplTest {
    private final ObjectMapper mapper = JsonMapper.builder().build();
    private final BlockingQueue<Reply> replies = new LinkedBlockingQueue<>();
    private final BlockingQueue<CapturedRequest> requests = new LinkedBlockingQueue<>();
    private final OkHttpClient client = new OkHttpClient();
    private HttpServer server;
    private RagProperties properties;
    private EsChunkServiceImpl service;

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.add(new CapturedRequest(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                    exchange.getRequestURI().getQuery(), exchange.getRequestHeaders().getFirst("Content-Type"),
                    new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            Reply reply = replies.poll();
            if (reply == null) reply = new Reply(500, "{\"error\":\"unexpected request\"}");
            byte[] body = reply.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(reply.status(), exchange.getRequestMethod().equals("HEAD") ? -1 : body.length);
            if (!exchange.getRequestMethod().equals("HEAD")) exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        properties = new RagProperties();
        properties.getElasticsearch().setUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.getElasticsearch().setIndexName("test_chunks");
        service = new EsChunkServiceImpl(client, mapper, properties);
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
        client.connectionPool().evictAll();
        client.dispatcher().executorService().shutdown();
    }

    @Test
    void createsMissingIndexWithIkBm25AndKeywordOwnership() {
        reply(404, "");
        reply(200, "{\"acknowledged\":true,\"shards_acknowledged\":true,\"index\":\"test_chunks\"}");

        service.ensureIndex();

        assertThat(request().method()).isEqualTo("HEAD");
        CapturedRequest create = request();
        assertThat(create.method()).isEqualTo("PUT");
        assertThat(create.path()).isEqualTo("/test_chunks");
        JsonNode fields = json(create.body()).path("mappings").path("properties");
        assertThat(fields.path("content").path("type").asString()).isEqualTo("text");
        assertThat(fields.path("content").path("analyzer").asString()).isEqualTo("ik_max_word");
        assertThat(fields.path("content").path("search_analyzer").asString()).isEqualTo("ik_smart");
        assertThat(fields.path("content").path("similarity").asString()).isEqualTo("BM25");
        assertThat(fields.path("title").path("type").asString()).isEqualTo("text");
        assertThat(fields.path("mdStorageId").path("type").asString()).isEqualTo("keyword");
        assertThat(fields.path("knowledge_base_id").path("type").asString()).isEqualTo("keyword");
        assertThat(fields.path("metadata").path("enabled").asBoolean(true)).isFalse();
    }

    @Test
    void validatesExistingIndexMappingWithoutOverwritingIt() {
        reply(200, "");
        reply(200, mapping("keyword", "ik_max_word"));

        service.ensureIndex();

        assertThat(request().method()).isEqualTo("HEAD");
        CapturedRequest read = request();
        assertThat(read.method()).isEqualTo("GET");
        assertThat(read.path()).isEqualTo("/test_chunks/_mapping");
        assertThat(requests).isEmpty();
    }

    @Test
    void rejectsExistingMappingThatCannotEnforceKnowledgeOwnership() {
        reply(200, "");
        reply(200, mapping("text", "ik_max_word"));
        assertThatIllegalStateException().isThrownBy(service::ensureIndex);
    }

    @Test
    void rejectsExistingIndexWithWrongContentAnalyzer() {
        reply(200, "");
        reply(200, mapping("keyword", "standard"));
        assertThatIllegalStateException().isThrownBy(service::ensureIndex);
    }

    @Test
    void doesNotCreateIndexAfterFailedExistenceProbe() {
        reply(503, "{\"error\":\"unavailable\"}");
        assertThatIllegalStateException().isThrownBy(service::ensureIndex);
        assertThat(request().method()).isEqualTo("HEAD");
        assertThat(requests).isEmpty();
    }

    @Test
    void rejectsUnacknowledgedIndexCreation() {
        reply(404, "");
        reply(200, "{\"acknowledged\":false}");
        assertThatIllegalStateException().isThrownBy(service::ensureIndex);
    }

    @Test
    void validatesMappingWhenConcurrentCallerAlreadyCreatedIndex() {
        reply(404, "");
        reply(400, "{\"error\":{\"type\":\"resource_already_exists_exception\"},\"status\":400}");
        reply(200, mapping("keyword", "ik_max_word"));
        service.ensureIndex();
        request();
        request();
        assertThat(request().path()).isEqualTo("/test_chunks/_mapping");
    }

    @Test
    void bulkOverwritePreservesDocumentIdsMetadataAndCanonicalStringOwnership() {
        reply(200, bulkSuccess());
        reply(200, bulkSuccess());
        Document document = document("chunk-1", Map.of("mdStorageId", 42L, "knowledge_base_id", "7",
                "title", "键盘保修", "originalFileName", "保修.md", "page", 2));

        service.upsert(List.of(document));
        service.upsert(List.of(document));

        CapturedRequest first = request();
        assertThat(first.method()).isEqualTo("POST");
        assertThat(first.path()).isEqualTo("/test_chunks/_bulk");
        assertThat(first.query()).contains("refresh=wait_for");
        assertThat(first.contentType()).startsWith("application/x-ndjson");
        assertThat(first.body()).endsWith("\n");
        String[] lines = first.body().split("\n");
        assertThat(lines).hasSize(2);
        assertThat(json(lines[0]).path("index").path("_id").asString()).isEqualTo("chunk-1");
        JsonNode source = json(lines[1]);
        assertThat(source.path("content").asString()).isEqualTo("机械键盘保修一年。\n保留图片 https://example.com/p.png");
        assertThat(source.path("title").asString()).isEqualTo("键盘保修");
        assertThat(source.path("mdStorageId").isString()).isTrue();
        assertThat(source.path("mdStorageId").asString()).isEqualTo("42");
        assertThat(source.path("knowledge_base_id").asString()).isEqualTo("7");
        assertThat(source.path("metadata").path("page").asInt()).isEqualTo(2);
        assertThat(source.path("metadata").path("originalFileName").asString()).isEqualTo("保修.md");
        assertThat(request().body()).isEqualTo(first.body());
        assertThat(document.getMetadata()).containsEntry("mdStorageId", 42L);
    }

    @Test
    void bulkRemainsValidNdjsonWhenApplicationMapperUsesPrettyPrinting() {
        ObjectMapper prettyMapper = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();
        service = new EsChunkServiceImpl(client, prettyMapper, properties);
        reply(200, bulkSuccess());

        service.upsert(List.of(ownedDocument()));

        String[] lines = request().body().split("\n");
        assertThat(lines).hasSize(2);
        assertThat(json(lines[0]).path("index").path("_id").asString()).isEqualTo("chunk-1");
        assertThat(json(lines[1]).path("mdStorageId").asString()).isEqualTo("42");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"errors\":true,\"items\":[{\"index\":{\"_id\":\"chunk-1\",\"status\":429,\"error\":{\"type\":\"es_rejected_execution_exception\"}}}]}",
            "{\"errors\":false,\"items\":[{\"index\":{\"_id\":\"chunk-1\",\"status\":400}}]}",
            "{\"errors\":false,\"items\":[]}",
            "{\"items\":[{\"index\":{\"_id\":\"chunk-1\",\"status\":201}}]}",
            "{\"errors\":false,\"items\":[{\"index\":{\"_id\":\"wrong-id\",\"status\":201}}]}",
            "{\"errors\":false,\"items\":[{\"index\":{\"_id\":\"chunk-1\",\"status\":201,\"error\":{\"reason\":\"failed\"}}}]}",
            "{\"errors\":false,\"items\":[{\"index\":{\"_id\":\"chunk-1\",\"status\":201,\"_shards\":{\"failed\":1}}}]}",
            "not-json"
    })
    void propagatesBulkPartialFailuresAndMalformedAcknowledgments(String response) {
        reply(200, response);
        assertThatIllegalStateException().isThrownBy(() -> service.upsert(List.of(ownedDocument())));
    }

    @Test
    void propagatesBulkHttpFailureForDurableRetry() {
        reply(503, "{\"error\":\"unavailable\"}");
        assertThatIllegalStateException().isThrownBy(() -> service.upsert(List.of(ownedDocument())));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "abc", "1.5", "9223372036854775808", ""})
    void rejectsInvalidWriteOwnershipBeforeAnyHttpRequest(String owner) {
        assertThatIllegalArgumentException().isThrownBy(() -> service.upsert(List.of(
                document("chunk-1", Map.of("mdStorageId", 42L, "knowledge_base_id", owner)))));
        assertThat(requests).isEmpty();
    }

    @Test
    void validatesEntireWriteBatchBeforeSendingAnything() {
        Document invalid = document("chunk-2", Map.of("knowledge_base_id", "7"));
        assertThatIllegalArgumentException().isThrownBy(() -> service.upsert(List.of(ownedDocument(), invalid)));
        assertThatIllegalArgumentException().isThrownBy(() -> service.upsert(List.of(
                document("chunk-2", Map.of("mdStorageId", 42L)))));
        assertThat(requests).isEmpty();
    }

    @Test
    void rejectsDocumentIdsBeyondElasticsearchByteLimit() {
        Document document = document("汉".repeat(180), Map.of("mdStorageId", 42L, "knowledge_base_id", "7"));
        assertThatIllegalArgumentException().isThrownBy(() -> service.upsert(List.of(document)));
        assertThat(requests).isEmpty();
    }

    @Test
    void nullBatchIsRejectedAndEmptyBatchMakesNoRequest() {
        assertThatIllegalArgumentException().isThrownBy(() -> service.upsert(null));
        service.upsert(List.of());
        assertThat(requests).isEmpty();
    }

    @Test
    void fallsBackToOriginalFilenameForSearchableTitle() {
        reply(200, bulkSuccess());
        service.upsert(List.of(document("chunk-1", Map.of("mdStorageId", "42", "knowledge_base_id", 7L,
                "originalFileName", "键盘使用指南.md"))));
        JsonNode source = json(request().body().split("\n")[1]);
        assertThat(source.path("title").asString()).isEqualTo("键盘使用指南.md");
    }

    @Test
    void nativeBm25MatchFiltersKnowledgeBeforeRecallAndRestoresMetadata() {
        reply(200, searchResponse("7"));

        List<Document> found = service.searchByKeyword("键盘保修", 3, 7L, Set.of(42L));

        CapturedRequest search = request();
        assertThat(search.method()).isEqualTo("POST");
        assertThat(search.path()).isEqualTo("/test_chunks/_search");
        JsonNode body = json(search.body());
        assertThat(body.path("size").asInt()).isEqualTo(3);
        assertThat(body.path("query").path("bool").path("must").get(0)
                .path("match").path("content").path("query").asString()).isEqualTo("键盘保修");
        assertThat(body.path("query").path("bool").path("filter").get(0)
                .path("term").path("knowledge_base_id").asString()).isEqualTo("7");
        assertThat(found).hasSize(1);
        assertThat(found.getFirst().getId()).isEqualTo("chunk-1");
        assertThat(found.getFirst().getText()).isEqualTo("机械键盘保修一年");
        assertThat(found.getFirst().getScore()).isEqualTo(2.5);
        assertThat(found.getFirst().getMetadata()).containsEntry("mdStorageId", "42")
                .containsEntry("knowledge_base_id", "7").containsEntry("title", "键盘保修")
                .containsEntry("originalFileName", "保修.md").containsEntry("page", 2);
    }

    @Test
    void keywordQueryFiltersToCurrentPublishedFileIdsBeforeTopK() {
        reply(200, searchResponse("7"));

        service.searchByKeyword("键盘保修", 3, 7L, Set.of(42L, 43L));

        JsonNode filters = json(request().body()).path("query").path("bool").path("filter");
        assertThat(filters.get(0).path("term").path("knowledge_base_id").asString()).isEqualTo("7");
        JsonNode ids = filters.get(1).path("terms").path("mdStorageId");
        assertThat(ids.isArray()).isTrue();
        assertThat(ids.size()).isEqualTo(2);
        assertThat(ids.get(0).asString()).isEqualTo("42");
        assertThat(ids.get(1).asString()).isEqualTo("43");
    }

    @Test
    void emptyPublishedFileSetNeverCallsElasticsearch() {
        assertThat(service.searchByKeyword("键盘保修", 3, 7L, Set.of())).isEmpty();
        assertThat(requests).isEmpty();
    }

    @Test
    void missingSearchKnowledgeIsRejectedBeforeHttp() {
        reply(200, searchResponse("1"));
        assertThatIllegalArgumentException().isThrownBy(() -> service.searchByKeyword("报销", 10, null, Set.of(42L)));
        assertThat(requests).isEmpty();
    }

    @Test
    void missingPublishedFileSetCannotIssueUnfilteredKeywordQuery() {
        reply(200, searchResponse("7"));
        assertThatIllegalArgumentException().isThrownBy(() -> service.searchByKeyword("报销", 10, 7L, null));
        assertThat(requests).isEmpty();
    }

    @Test
    void rejectsInvalidSearchScopeAndLimitBeforeRecall() {
        assertThatIllegalArgumentException().isThrownBy(() -> service.searchByKeyword("报销", 10, 0L, Set.of(42L)));
        assertThatIllegalArgumentException().isThrownBy(() -> service.searchByKeyword("报销", 10, -1L, Set.of(42L)));
        assertThatIllegalArgumentException().isThrownBy(() -> service.searchByKeyword("报销", 0, 1L, Set.of(42L)));
        assertThat(requests).isEmpty();
    }

    @Test
    void blankSearchDoesNotCallElasticsearch() {
        assertThat(service.searchByKeyword("  ", 10, 1L, Set.of(42L))).isEmpty();
        assertThat(service.searchByKeyword(null, 10, 1L, Set.of(42L))).isEmpty();
        assertThat(requests).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"timed_out\":true,\"_shards\":{\"failed\":0},\"hits\":{\"hits\":[]}}",
            "{\"timed_out\":false,\"_shards\":{\"failed\":1},\"hits\":{\"hits\":[]}}",
            "{}", "{\"hits\":{\"hits\":{}}}", "not-json"
    })
    void refusesIncompleteOrMalformedSearchResults(String response) {
        reply(200, response);
        assertThatIllegalStateException().isThrownBy(() -> service.searchByKeyword("报销", 10, 7L, Set.of(42L)));
    }

    @Test
    void refusesSearchHitFromAnotherKnowledgeBase() {
        reply(200, searchResponse("8"));
        assertThatIllegalStateException().isThrownBy(() -> service.searchByKeyword("保修", 10, 7L, Set.of(42L)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"_id", "_source", "_score"})
    void rejectsSearchHitsMissingRequiredFields(String field) {
        var response = (tools.jackson.databind.node.ObjectNode) json(searchResponse("7"));
        ((tools.jackson.databind.node.ObjectNode) response.path("hits").path("hits").get(0)).remove(field);
        reply(200, mapper.writeValueAsString(response));
        assertThatIllegalStateException().isThrownBy(() -> service.searchByKeyword("保修", 10, 7L, Set.of(42L)));
    }

    @Test
    void propagatesSearchHttpFailureToHybridFallback() {
        reply(503, "{\"error\":\"unavailable\"}");
        assertThatIllegalStateException().isThrownBy(() -> service.searchByKeyword("报销", 10, 7L, Set.of(42L)));
    }

    @Test
    void successfulZeroHitResponseReturnsEmptyCandidates() {
        reply(200, "{\"timed_out\":false,\"_shards\":{\"failed\":0},\"hits\":{\"hits\":[]}}");
        assertThat(service.searchByKeyword("无匹配", 10, 7L, Set.of(42L))).isEmpty();
    }

    @Test
    void deletesOnlyTheRequestedFileWithRefreshAndValidatesAcknowledgment() {
        reply(200, deleteSuccess());
        service.deleteByFileId(42L);
        CapturedRequest deletion = request();
        assertThat(deletion.method()).isEqualTo("POST");
        assertThat(deletion.path()).isEqualTo("/test_chunks/_delete_by_query");
        assertThat(deletion.query()).contains("refresh=true", "wait_for_completion=true");
        assertThat(json(deletion.body()).path("query").path("term").path("mdStorageId").asString()).isEqualTo("42");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"timed_out\":true,\"deleted\":1,\"version_conflicts\":0,\"failures\":[]}",
            "{\"timed_out\":false,\"deleted\":0,\"version_conflicts\":1,\"failures\":[]}",
            "{\"timed_out\":false,\"deleted\":0,\"version_conflicts\":0,\"failures\":[{\"reason\":\"failed\"}]}",
            "{}", "not-json"
    })
    void propagatesIncompleteDeleteForDurableRetry(String response) {
        reply(200, response);
        assertThatIllegalStateException().isThrownBy(() -> service.deleteByFileId(42L));
    }

    @Test
    void rejectsInvalidFileDeleteScope() {
        assertThatIllegalArgumentException().isThrownBy(() -> service.deleteByFileId(null));
        assertThatIllegalArgumentException().isThrownBy(() -> service.deleteByFileId(0L));
        assertThat(requests).isEmpty();
    }

    @Test
    void disabledElasticsearchSkipsRecallButDoesNotSilentlyAcknowledgeWrites() {
        properties.getElasticsearch().setEnabled(false);
        service = new EsChunkServiceImpl(client, mapper, properties);
        service.ensureIndex();
        assertThat(service.searchByKeyword("报销", 10, 7L, Set.of(42L))).isEmpty();
        assertThatIllegalStateException().isThrownBy(() -> service.upsert(List.of(ownedDocument())));
        assertThatIllegalStateException().isThrownBy(() -> service.deleteByFileId(42L));
        assertThat(requests).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"*", "a,b", "../other", "UPPER", "_all", ".", "..", "-index", "a b"})
    void rejectsUnsafeIndexNamesBeforeHttp(String indexName) {
        properties.getElasticsearch().setIndexName(indexName);
        assertThatIllegalArgumentException().isThrownBy(() -> new EsChunkServiceImpl(client, mapper, properties));
        assertThat(requests).isEmpty();
    }

    @Test
    void rejectsInvalidTimeoutAndEndpointConfiguration() {
        properties.getElasticsearch().setTimeoutSeconds(0);
        assertThatIllegalArgumentException().isThrownBy(() -> new EsChunkServiceImpl(client, mapper, properties));
        properties.getElasticsearch().setTimeoutSeconds(1);
        properties.getElasticsearch().setUrl("http://127.0.0.1:9200?index=other");
        assertThatIllegalArgumentException().isThrownBy(() -> new EsChunkServiceImpl(client, mapper, properties));
    }

    private Document ownedDocument() {
        return document("chunk-1", Map.of("mdStorageId", 42L, "knowledge_base_id", "7"));
    }

    private Document document(String id, Map<String, Object> metadata) {
        return Document.builder().id(id).text("机械键盘保修一年。\n保留图片 https://example.com/p.png")
                .metadata(new HashMap<>(metadata)).build();
    }

    private void reply(int status, String body) { replies.add(new Reply(status, body)); }
    private CapturedRequest request() { return requests.remove(); }
    private JsonNode json(String body) { return mapper.readTree(body); }

    private String mapping(String knowledgeType, String analyzer) {
        return """
                {"test_chunks":{"mappings":{"properties":{
                  "content":{"type":"text","analyzer":"%s","search_analyzer":"ik_smart","similarity":"BM25"},
                  "title":{"type":"text","analyzer":"ik_max_word","search_analyzer":"ik_smart"},
                  "mdStorageId":{"type":"keyword"},"knowledge_base_id":{"type":"%s"},
                  "metadata":{"type":"object","enabled":false}
                }}}}
                """.formatted(analyzer, knowledgeType);
    }

    private String bulkSuccess() {
        return "{\"took\":1,\"errors\":false,\"items\":[{\"index\":{\"_index\":\"test_chunks\",\"_id\":\"chunk-1\",\"status\":201,\"result\":\"created\",\"_shards\":{\"total\":1,\"successful\":1,\"failed\":0}}}]}";
    }

    private String searchResponse(String knowledgeId) {
        return """
                {"took":1,"timed_out":false,"_shards":{"total":1,"successful":1,"skipped":0,"failed":0},
                 "hits":{"total":{"value":1,"relation":"eq"},"max_score":2.5,"hits":[{
                   "_index":"test_chunks","_id":"chunk-1","_score":2.5,
                   "_source":{"content":"机械键盘保修一年","title":"键盘保修","mdStorageId":"42","knowledge_base_id":"%s",
                     "metadata":{"originalFileName":"保修.md","page":2,"knowledge_base_id":"untrusted"}}
                 }]}}
                """.formatted(knowledgeId);
    }

    private String deleteSuccess() {
        return "{\"took\":1,\"timed_out\":false,\"total\":1,\"deleted\":1,\"batches\":1,\"version_conflicts\":0,\"noops\":0,\"retries\":{\"bulk\":0,\"search\":0},\"throttled_millis\":0,\"requests_per_second\":-1.0,\"throttled_until_millis\":0,\"failures\":[]}";
    }

    private record Reply(int status, String body) {}
    private record CapturedRequest(String method, String path, String query, String contentType, String body) {}
}
