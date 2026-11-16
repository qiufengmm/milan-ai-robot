package com.milanai.robot.service.impl;

import com.milanai.robot.config.RagExecutorConfig;
import com.milanai.robot.config.RagProperties;
import com.milanai.robot.service.EsChunkService;
import com.milanai.robot.service.FileDocumentGuard;
import com.milanai.robot.service.KnowledgeDocumentService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RagRetrievalExecutorWiringTest {
    @Test
    void missingPublicationServicePreventsRetrievalBeanFromStarting() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(VectorStore.class, () -> mock(VectorStore.class));
            context.registerBean(EsChunkService.class, () -> mock(EsChunkService.class));
            context.registerBean(FileDocumentGuard.class, () -> mock(FileDocumentGuard.class));
            context.registerBean(RagProperties.class);
            context.register(RagExecutorConfig.class, HybridRetrievalServiceImpl.class);

            assertThatThrownBy(context::refresh)
                    .isInstanceOf(org.springframework.beans.factory.UnsatisfiedDependencyException.class);
        }
    }

    @Test
    void qualifiersSelectDedicatedExecutorDespiteOtherApplicationExecutor() throws Exception {
        VectorStore vectors = mock(VectorStore.class);
        EsChunkService keywords = mock(EsChunkService.class);
        KnowledgeDocumentService publications = mock(KnowledgeDocumentService.class);
        when(publications.currentPublishedFileIds(1L)).thenReturn(List.of(42L));
        CountDownLatch routesStarted = new CountDownLatch(2);
        ConcurrentLinkedQueue<String> threads = new ConcurrentLinkedQueue<>();
        List<Document> documents = List.of(new Document("a", "Warranty", Map.of("knowledge_base_id", "1")));
        when(vectors.similaritySearch(any(SearchRequest.class))).thenAnswer(call -> {
            threads.add(Thread.currentThread().getName());
            routesStarted.countDown();
            assertThat(routesStarted.await(2, TimeUnit.SECONDS)).isTrue();
            return documents;
        });
        when(keywords.searchByKeyword("warranty", 10, 1L, Set.of(42L))).thenAnswer(call -> {
            threads.add(Thread.currentThread().getName());
            routesStarted.countDown();
            assertThat(routesStarted.await(2, TimeUnit.SECONDS)).isTrue();
            return documents;
        });

        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean("otherExecutor", Executor.class, () -> Runnable::run);
            context.registerBean(VectorStore.class, () -> vectors);
            context.registerBean(EsChunkService.class, () -> keywords);
            context.registerBean(FileDocumentGuard.class, () -> (recalled, id, allowedFileIds) -> recalled);
            context.registerBean(KnowledgeDocumentService.class, () -> publications);
            context.registerBean(RagProperties.class);
            context.register(RagExecutorConfig.class, HybridRetrievalServiceImpl.class);
            context.refresh();

            assertThat(context.getBean(HybridRetrievalServiceImpl.class).search("warranty", 10, 1L))
                    .extracting(Document::getId).containsExactly("a");
            assertThat(threads).hasSize(2).allMatch(name -> name.startsWith("rag-retrieval-"));
        }
    }
}
