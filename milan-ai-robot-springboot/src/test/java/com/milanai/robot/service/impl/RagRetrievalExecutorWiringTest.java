package com.milanai.robot.service.impl;

import com.milanai.robot.config.RagExecutorConfig;
import com.milanai.robot.config.RagProperties;
import com.milanai.robot.service.EsChunkService;
import com.milanai.robot.service.FileDocumentGuard;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RagRetrievalExecutorWiringTest {
    @Test
    void qualifiersSelectDedicatedExecutorDespiteOtherApplicationExecutor() throws Exception {
        VectorStore vectors = mock(VectorStore.class);
        EsChunkService keywords = mock(EsChunkService.class);
        CountDownLatch routesStarted = new CountDownLatch(2);
        ConcurrentLinkedQueue<String> threads = new ConcurrentLinkedQueue<>();
        List<Document> documents = List.of(new Document("a", "Warranty", Map.of("knowledge_base_id", "1")));
        when(vectors.similaritySearch(any(SearchRequest.class))).thenAnswer(call -> {
            threads.add(Thread.currentThread().getName());
            routesStarted.countDown();
            assertThat(routesStarted.await(2, TimeUnit.SECONDS)).isTrue();
            return documents;
        });
        when(keywords.searchByKeyword("warranty", 10, 1L)).thenAnswer(call -> {
            threads.add(Thread.currentThread().getName());
            routesStarted.countDown();
            assertThat(routesStarted.await(2, TimeUnit.SECONDS)).isTrue();
            return documents;
        });

        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean("otherExecutor", Executor.class, () -> Runnable::run);
            context.registerBean(VectorStore.class, () -> vectors);
            context.registerBean(EsChunkService.class, () -> keywords);
            context.registerBean(FileDocumentGuard.class, () -> (recalled, id) -> recalled);
            context.registerBean(RagProperties.class);
            context.register(RagExecutorConfig.class, HybridRetrievalServiceImpl.class);
            context.refresh();

            assertThat(context.getBean(HybridRetrievalServiceImpl.class).search("warranty", 10, null))
                    .extracting(Document::getId).containsExactly("a");
            assertThat(threads).hasSize(2).allMatch(name -> name.startsWith("rag-retrieval-"));
        }
    }
}
