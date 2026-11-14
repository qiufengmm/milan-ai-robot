package com.milanai.robot.event.listener;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.milanai.robot.domain.dos.AiCustomerServiceFileStorageDO;
import com.milanai.robot.domain.mapper.AiCustomerServiceFileStorageMapper;
import com.milanai.robot.enums.AiCustomerServiceFileStatusEnum;
import com.milanai.robot.event.AiCustomerServiceMdUploadedEvent;
import com.milanai.robot.reader.TxtReader;
import com.milanai.robot.service.RagIndexService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

class AiCustomerServiceMdUploadedListenerTest {

    @TempDir
    Path directory;

    private final AiCustomerServiceFileStorageMapper mapper = mock(AiCustomerServiceFileStorageMapper.class);
    private final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    private final Map<String, Document> vectors = new HashMap<>();
    private final List<Integer> statuses = new ArrayList<>();
    private final RagIndexService indexService = mock(RagIndexService.class);
    private AiCustomerServiceMdUploadedListener listener;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        VectorStore vectorStore = mock(VectorStore.class);
        doAnswer(invocation -> {
            List<Document> documents = invocation.getArgument(0);
            documents.forEach(document -> vectors.put(document.getId(), document));
            return null;
        }).when(vectorStore).add(any());
        doAnswer(invocation -> {
            AiCustomerServiceFileStorageDO file = invocation.getArgument(0);
            statuses.add(file.getStatus());
            return 1;
        }).when(mapper).updateById(any(AiCustomerServiceFileStorageDO.class));
        when(mapper.selectOne(any(Wrapper.class)))
                .thenAnswer(invocation -> AiCustomerServiceFileStorageDO.builder().knowledgeBaseId(1L).build());
        when(transactionManager.getTransaction(any())).thenAnswer(invocation -> new SimpleTransactionStatus());

        listener = new AiCustomerServiceMdUploadedListener();
        ReflectionTestUtils.setField(listener, "txtReader", new TxtReader());
        ReflectionTestUtils.setField(listener, "vectorStore", vectorStore);
        ReflectionTestUtils.setField(listener, "aiCustomerServiceFileStorageMapper", mapper);
        ReflectionTestUtils.setField(listener, "transactionTemplate", new TransactionTemplate(transactionManager));
        ReflectionTestUtils.setField(listener, "ragIndexService", indexService);
    }

    @Test
    @SuppressWarnings("unchecked")
    void authoritativeOwnerOverridesUntrustedEventMetadataAndQueuesIndex() throws Exception {
        when(mapper.selectOne(any(Wrapper.class)))
                .thenReturn(AiCustomerServiceFileStorageDO.builder().id(1L).knowledgeBaseId(7L).fileName("owned.txt").build());
        var event = upload(1L, "owned.txt", "Independent library information.");
        event.setMetadatas(Map.of("mdStorageId", 999L, "knowledge_base_id", "999", "originalFileName", "owned.txt"));

        listener.vectorizing(event);

        assertThat(vectors.values()).singleElement().satisfies(doc -> {
            assertThat(doc.getMetadata().get("knowledge_base_id")).isEqualTo("7");
            assertThat(doc.getMetadata().get("mdStorageId")).isEqualTo(1L);
        });
        verify(indexService).enqueueUpsert(1L, 7L);
    }

    @Test
    void identicalContentInDifferentUploadsKeepsIndependentVectorOwnership() throws Exception {
        var first = upload(1L, "first.txt", "This product has a twelve month warranty and includes its original accessories.");
        var second = upload(2L, "second.txt", "This product has a twelve month warranty and includes its original accessories.");

        listener.vectorizing(first);
        listener.vectorizing(second);

        assertThat(vectors).hasSize(2);
        assertThat(vectors.values()).extracting(document -> document.getMetadata().get("mdStorageId"))
                .containsExactlyInAnyOrder(1L, 2L);

        // Retrying the same file record must not introduce duplicate vectors.
        listener.vectorizing(first);
        assertThat(vectors).hasSize(2);
    }

    @Test
    void whitespaceDocumentIsFailedRatherThanCompletedWithoutVectors() throws Exception {
        listener.vectorizing(upload(1L, "blank.txt", "  \n\t \n"));

        assertThat(vectors).isEmpty();
        assertThat(statuses).containsExactly(
                AiCustomerServiceFileStatusEnum.VECTORIZING.getCode(),
                AiCustomerServiceFileStatusEnum.FAILED.getCode());
    }

    @Test
    void shortMeaningfulTextIsIndexed() throws Exception {
        listener.vectorizing(upload(1L, "short.txt", "保修一年"));

        assertThat(vectors.values()).singleElement()
                .extracting(Document::getText).isEqualTo("保修一年");
        assertThat(statuses.getLast()).isEqualTo(AiCustomerServiceFileStatusEnum.COMPLETED.getCode());
    }

    @Test
    @SuppressWarnings("unchecked")
    void fileDeletedBeforeWorkerStartsDoesNotCreateOrphanVectors() throws Exception {
        when(mapper.selectOne(any(Wrapper.class))).thenReturn(null);

        listener.vectorizing(upload(1L, "deleted.txt", "A deleted file must never leave searchable knowledge chunks behind."));

        assertThat(vectors).isEmpty();
        assertThat(statuses).doesNotContain(AiCustomerServiceFileStatusEnum.COMPLETED.getCode());
    }

    @Test
    void transactionCommitFailureMarksFileFailed() throws Exception {
        doThrow(new TransactionSystemException("simulated commit failure"))
                .when(transactionManager).commit(any());
        var event = upload(1L, "commit.txt", "A database failure during commit must leave the file in a failed state.");

        assertDoesNotThrow(() -> listener.vectorizing(event));

        assertThat(statuses.getLast()).isEqualTo(AiCustomerServiceFileStatusEnum.FAILED.getCode());
    }

    private AiCustomerServiceMdUploadedEvent upload(long id, String filename, String text) throws Exception {
        Path path = directory.resolve(filename);
        Files.writeString(path, text);
        return AiCustomerServiceMdUploadedEvent.builder()
                .id(id)
                .filePath(path.toString())
                .metadatas(Map.of("mdStorageId", id, "originalFileName", filename))
                .build();
    }
}
