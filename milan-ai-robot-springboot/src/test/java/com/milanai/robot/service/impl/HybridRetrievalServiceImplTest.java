package com.milanai.robot.service.impl;

import com.milanai.robot.config.RagProperties;
import com.milanai.robot.service.EsChunkService;
import com.milanai.robot.service.FileDocumentGuard;
import com.milanai.robot.service.KnowledgeDocumentService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class HybridRetrievalServiceImplTest {
    private final VectorStore vectors = mock(VectorStore.class);
    private final EsChunkService keywords = mock(EsChunkService.class);
    private final KnowledgeDocumentService documents = mock(KnowledgeDocumentService.class);
    private final RagProperties properties = new RagProperties();
    private final ExecutorService executor = Executors.newFixedThreadPool(2);
    private final AtomicInteger guardCalls = new AtomicInteger();
    private final FileDocumentGuard guard = (recalled, id, allowedFileIds) -> {
        guardCalls.incrementAndGet();
        return recalled.stream().filter(d -> Long.toString(id).equals(d.getMetadata().get("knowledge_base_id")))
                .filter(d -> allowedFileIds.contains(d.getMetadata().get("mdStorageId")))
                .filter(d -> Boolean.TRUE.equals(d.getMetadata().get("ready")))
                .filter(d -> Boolean.TRUE.equals(d.getMetadata().get("ownerExists"))).toList();
    };

    @BeforeEach
    void providePublishedFiles() {
        when(documents.currentPublishedFileIds(anyLong())).thenReturn(List.of(42L));
    }

    @AfterEach
    void closeExecutor() {
        executor.shutdownNow();
    }

    @Test
    void runsBothScopedRoutesInParallelAndGuardsBeforeFusion() {
        properties.getRetrieval().setVectorTopK(4);
        properties.getRetrieval().setKeywordTopK(5);
        CountDownLatch routesStarted = new CountDownLatch(2);
        when(vectors.similaritySearch(any(SearchRequest.class))).thenAnswer(call -> {
            SearchRequest request = call.getArgument(0);
            assertThat(request.getQuery()).isEqualTo("报销");
            assertThat(request.getTopK()).isEqualTo(4);
            assertThat(request.getFilterExpression()).isEqualTo(new FilterExpressionBuilder()
                    .and(new FilterExpressionBuilder().eq("knowledge_base_id", "2"),
                            new FilterExpressionBuilder().in("mdStorageId", List.of((Object) 42L))).build());
            awaitBoth(routesStarted);
            return List.of(doc("vector", "2", true, true), doc("foreign", "3", true, true),
                    doc("unfinished", "2", false, true), doc("orphan", "2", true, false));
        });
        when(keywords.searchByKeyword("报销", 5, 2L, Set.of(42L))).thenAnswer(call -> {
            awaitBoth(routesStarted);
            return List.of(doc("keyword", "2", true, true), doc("foreign", "3", true, true),
                    doc("unfinished", "2", false, true), doc("orphan", "2", true, false));
        });

        List<Document> result = service().search("报销", 10, 2L);

        assertThat(result).extracting(Document::getId).containsExactly("keyword", "vector");
        assertThat(guardCalls).hasValue(2);
    }

    @Test
    void missingKnowledgeBaseIsRejectedBeforeStartingEitherRoute() {
        assertThatThrownBy(() -> service().search("报销", 10, null))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(vectors, keywords, documents);
    }

    @Test
    void missingPublishedFileSetCannotStartUnfilteredRecall() {
        when(documents.currentPublishedFileIds(2L)).thenReturn(null);

        assertThatThrownBy(() -> service().search("VPN", 3, 2L))
                .isInstanceOf(RuntimeException.class);
        verifyNoInteractions(vectors, keywords);
    }

    @Test
    void keywordFailureRetainsOnlyGuardedVectorCandidatesInSelectedLibrary() {
        when(vectors.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(
                doc("a", "7", true, true), doc("other", "1", true, true), doc("gone", "7", true, false)));
        when(keywords.searchByKeyword("报销", 10, 7L, Set.of(42L))).thenThrow(new IllegalStateException("ES offline"));

        assertThat(service().search("报销", 10, 7L)).extracting(Document::getId).containsExactly("a");
    }

    @Test
    void vectorFailureRetainsOnlyScopedKeywordCandidates() {
        when(vectors.similaritySearch(any(SearchRequest.class))).thenThrow(new IllegalStateException("DB offline"));
        when(keywords.searchByKeyword("报销", 10, 7L, Set.of(42L))).thenReturn(List.of(doc("a", "7", true, true)));

        assertThat(service().search("报销", 10, 7L)).extracting(Document::getId).containsExactly("a");
    }

    @Test
    void twoFailuresProduceControlledFailureWithoutUnscopedRetry() {
        when(vectors.similaritySearch(any(SearchRequest.class))).thenThrow(new IllegalStateException("DB offline"));
        when(keywords.searchByKeyword("报销", 10, 7L, Set.of(42L))).thenThrow(new IllegalStateException("ES offline"));

        assertThatThrownBy(() -> service().search("报销", 10, 7L))
                .isInstanceOf(IllegalStateException.class).hasMessage("Knowledge retrieval is temporarily unavailable");
        verify(vectors, times(1)).similaritySearch(any(SearchRequest.class));
        verify(keywords, times(1)).searchByKeyword("报销", 10, 7L, Set.of(42L));
    }

    @Test
    void validatesPositiveScopeBeforeStartingRoutes() {
        assertThatThrownBy(() -> service().search("报销", 10, 0L)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service().search("报销", 10, -3L)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(vectors, keywords);
    }

    @Test
    void emptyPublishedLibraryReturnsNoAnswerEvidenceWithoutCallingEitherIndex() {
        when(documents.currentPublishedFileIds(2L)).thenReturn(List.of());

        assertThat(service().search("VPN", 3, 2L)).isEmpty();
        verifyNoInteractions(vectors, keywords);
    }

    @Test
    void vectorAndKeywordQueriesAreRestrictedToPublishedFileIdsBeforeTopK() {
        when(documents.currentPublishedFileIds(2L)).thenReturn(List.of(42L));
        when(vectors.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(doc("v", "2", true, true)));
        when(keywords.searchByKeyword("VPN", 10, 2L, Set.of(42L)))
                .thenReturn(List.of(doc("k", "2", true, true)));

        assertThat(service().search("VPN", 3, 2L)).extracting(Document::getId).containsExactly("k", "v");

        var captor = org.mockito.ArgumentCaptor.forClass(SearchRequest.class);
        verify(vectors).similaritySearch(captor.capture());
        assertThat(captor.getValue().getFilterExpression()).isEqualTo(new FilterExpressionBuilder()
                .and(new FilterExpressionBuilder().eq("knowledge_base_id", "2"),
                        new FilterExpressionBuilder().in("mdStorageId", List.of((Object) 42L))).build());
        verify(keywords).searchByKeyword("VPN", 10, 2L, Set.of(42L));
    }

    @Test
    void disabledKeywordRouteAndPoolLimitUseConfiguration() {
        properties.getElasticsearch().setEnabled(false);
        properties.getRetrieval().setFusedTopK(1);
        when(vectors.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(
                doc("a", "1", true, true), doc("b", "1", true, true)));

        assertThat(service().search("报销", 5, 1L)).extracting(Document::getId).containsExactly("a");
        verifyNoInteractions(keywords);
    }

    @Test
    void ownershipGuardFailureCannotLeakUnguardedRoute() {
        when(vectors.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(doc("a", "2", true, true)));
        when(keywords.searchByKeyword("报销", 10, 2L, Set.of(42L))).thenReturn(List.of(doc("b", "2", true, true)));
        FileDocumentGuard brokenGuard = (recalled, id, allowedFileIds) -> { throw new IllegalStateException("owner store unavailable"); };

        assertThatThrownBy(() -> new HybridRetrievalServiceImpl(vectors, keywords, brokenGuard, documents, properties, executor)
                .search("报销", 10, 2L)).isInstanceOf(IllegalStateException.class);
    }

    private HybridRetrievalServiceImpl service() {
        return new HybridRetrievalServiceImpl(vectors, keywords, guard, documents, properties, executor);
    }

    private Document doc(String id, String library, boolean ready, boolean ownerExists) {
        return new Document(id, "政策 " + id, Map.of("knowledge_base_id", library, "mdStorageId", 42L,
                "ready", ready, "ownerExists", ownerExists));
    }

    private void awaitBoth(CountDownLatch routesStarted) throws InterruptedException {
        routesStarted.countDown();
        assertThat(routesStarted.await(2, TimeUnit.SECONDS)).as("both retrieval routes run concurrently").isTrue();
    }
}
