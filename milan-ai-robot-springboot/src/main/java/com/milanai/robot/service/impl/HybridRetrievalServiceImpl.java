package com.milanai.robot.service.impl;

import com.milanai.robot.config.RagProperties;
import com.milanai.robot.service.EsChunkService;
import com.milanai.robot.service.FileDocumentGuard;
import com.milanai.robot.service.HybridRetrievalService;
import com.milanai.robot.utils.RrfFusion;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

@Service
@Slf4j
public class HybridRetrievalServiceImpl implements HybridRetrievalService {
    private final VectorStore vectors;
    private final EsChunkService keywords;
    private final FileDocumentGuard guard;
    private final RagProperties properties;
    private final Executor executor;

    public HybridRetrievalServiceImpl(VectorStore vectors, EsChunkService keywords, FileDocumentGuard guard,
                                      RagProperties properties,
                                      @Qualifier("ragRetrievalExecutor") Executor executor) {
        this.vectors = vectors;
        this.keywords = keywords;
        this.guard = guard;
        this.properties = properties;
        this.executor = executor;
    }

    @Override
    public List<Document> search(String query, int topK, Long knowledgeBaseId) {
        long libraryId = knowledgeBaseId == null ? 1L : knowledgeBaseId;
        if (libraryId < 1 || topK < 1) {
            throw new IllegalArgumentException("Knowledge base ID and retrieval limit must be positive");
        }
        if (query == null || query.isBlank()) {
            return List.of();
        }
        RagProperties.Retrieval config = properties.getRetrieval();
        if (config.getVectorTopK() < 1 || config.getKeywordTopK() < 1 || config.getFusedTopK() < 1
                || config.getRrfK() < 1) {
            throw new IllegalArgumentException("Retrieval configuration must use positive limits");
        }
        long started = System.nanoTime();
        SearchRequest request = SearchRequest.builder().query(query).topK(config.getVectorTopK())
                .filterExpression(new FilterExpressionBuilder().eq("knowledge_base_id", Long.toString(libraryId)).build())
                .build();
        CompletableFuture<RouteResult> vectorRoute = recall("vector", libraryId,
                () -> vectors.similaritySearch(request));
        boolean keywordEnabled = properties.getElasticsearch().isEnabled();
        CompletableFuture<RouteResult> keywordRoute = keywordEnabled
                ? recall("keyword", libraryId, () -> keywords.searchByKeyword(query, config.getKeywordTopK(), libraryId))
                : CompletableFuture.completedFuture(new RouteResult(List.of(), false));

        RouteResult vector = vectorRoute.join();
        RouteResult keyword = keywordRoute.join();
        if (!vector.available() && !keyword.available()) {
            log.warn("RAG retrieval mode=unavailable knowledgeBaseId={} elapsedMs={}", libraryId, elapsedMs(started));
            throw new IllegalStateException("Knowledge retrieval is temporarily unavailable");
        }
        long fusionStarted = System.nanoTime();
        List<Document> result = RrfFusion.fuse(List.of(vector.documents(), keyword.documents()),
                config.getRrfK(), Math.min(topK, config.getFusedTopK()));
        String mode = vector.available() && keyword.available() ? "hybrid"
                : vector.available() ? (keywordEnabled ? "vector-fallback" : "vector-only") : "keyword-fallback";
        log.info("RAG retrieval mode={} knowledgeBaseId={} vectorCount={} keywordCount={} fusedCount={} fusionMs={} elapsedMs={}",
                mode, libraryId, vector.documents().size(), keyword.documents().size(), result.size(),
                elapsedMs(fusionStarted), elapsedMs(started));
        return result;
    }

    private CompletableFuture<RouteResult> recall(String route, long libraryId, Supplier<List<Document>> search) {
        try {
            return CompletableFuture.supplyAsync(() -> {
                long started = System.nanoTime();
                try {
                    List<Document> recalled = search.get();
                    List<Document> owned = guard.retainOwned(recalled == null ? List.of() : recalled, libraryId);
                    RouteResult result = new RouteResult(List.copyOf(owned), true);
                    log.info("RAG recall route={} knowledgeBaseId={} count={} elapsedMs={}",
                            route, libraryId, result.documents().size(), elapsedMs(started));
                    return result;
                } catch (RuntimeException exception) {
                    // Do not log exception messages: drivers may include credentials or retrieved text.
                    log.warn("RAG recall unavailable route={} knowledgeBaseId={} reason={} elapsedMs={}",
                            route, libraryId, exception.getClass().getSimpleName(), elapsedMs(started));
                    return new RouteResult(List.of(), false);
                }
            }, executor);
        } catch (RuntimeException exception) {
            log.warn("RAG recall could not start route={} knowledgeBaseId={} reason={}",
                    route, libraryId, exception.getClass().getSimpleName());
            return CompletableFuture.completedFuture(new RouteResult(List.of(), false));
        }
    }

    private static long elapsedMs(long started) {
        return (System.nanoTime() - started) / 1_000_000;
    }

    private record RouteResult(List<Document> documents, boolean available) { }
}
