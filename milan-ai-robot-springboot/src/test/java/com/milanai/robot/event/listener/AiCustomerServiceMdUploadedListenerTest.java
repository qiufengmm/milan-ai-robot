package com.milanai.robot.event.listener;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.milanai.robot.domain.dos.AiCustomerServiceMdStorageDO;
import com.milanai.robot.domain.mapper.AiCustomerServiceMdStorageMapper;
import com.milanai.robot.event.AiCustomerServiceMdUploadedEvent;
import com.milanai.robot.reader.TxtReader;
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

class AiCustomerServiceMdUploadedListenerTest {

    @TempDir
    Path directory;

    private final AiCustomerServiceMdStorageMapper mapper = mock(AiCustomerServiceMdStorageMapper.class);
    private final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    private final Map<String, Document> vectors = new HashMap<>();
    private final List<Integer> statuses = new ArrayList<>();
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
            AiCustomerServiceMdStorageDO file = invocation.getArgument(0);
            statuses.add(file.getStatus());
            return 1;
        }).when(mapper).updateById(any(AiCustomerServiceMdStorageDO.class));
        when(mapper.selectOne(any(Wrapper.class)))
                .thenReturn(AiCustomerServiceMdStorageDO.builder().id(1L).build());
        when(transactionManager.getTransaction(any())).thenAnswer(invocation -> new SimpleTransactionStatus());

        listener = new AiCustomerServiceMdUploadedListener();
        ReflectionTestUtils.setField(listener, "txtReader", new TxtReader());
        ReflectionTestUtils.setField(listener, "vectorStore", vectorStore);
        ReflectionTestUtils.setField(listener, "aiCustomerServiceMdStorageMapper", mapper);
        ReflectionTestUtils.setField(listener, "transactionTemplate", new TransactionTemplate(transactionManager));
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
        assertThat(statuses).containsExactly(1, 3);
    }

    @Test
    void shortMeaningfulTextIsIndexed() throws Exception {
        listener.vectorizing(upload(1L, "short.txt", "保修一年"));

        assertThat(vectors.values()).singleElement()
                .extracting(Document::getText).isEqualTo("保修一年");
        assertThat(statuses.getLast()).isEqualTo(2);
    }

    @Test
    @SuppressWarnings("unchecked")
    void fileDeletedBeforeWorkerStartsDoesNotCreateOrphanVectors() throws Exception {
        when(mapper.selectOne(any(Wrapper.class))).thenReturn(null);

        listener.vectorizing(upload(1L, "deleted.txt", "A deleted file must never leave searchable knowledge chunks behind."));

        assertThat(vectors).isEmpty();
        assertThat(statuses).doesNotContain(2);
    }

    @Test
    void transactionCommitFailureMarksFileFailed() throws Exception {
        doThrow(new TransactionSystemException("simulated commit failure"))
                .when(transactionManager).commit(any());
        var event = upload(1L, "commit.txt", "A database failure during commit must leave the file in a failed state.");

        assertDoesNotThrow(() -> listener.vectorizing(event));

        assertThat(statuses.getLast()).isEqualTo(3);
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
