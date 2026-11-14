package com.milanai.robot.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.milanai.robot.config.RagProperties;
import com.milanai.robot.domain.dos.AiCustomerServiceFileStorageDO;
import com.milanai.robot.domain.dos.RagIndexTaskDO;
import com.milanai.robot.domain.mapper.AiCustomerServiceFileStorageMapper;
import com.milanai.robot.domain.mapper.RagIndexTaskMapper;
import com.milanai.robot.enums.AiCustomerServiceFileStatusEnum;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.ai.document.Document;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.sql.ResultSet;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RagIndexServiceTest {
    private final RagIndexTaskMapper tasks = mock(RagIndexTaskMapper.class);
    private final AiCustomerServiceFileStorageMapper files = mock(AiCustomerServiceFileStorageMapper.class);
    private final EsChunkService es = mock(EsChunkService.class);
    private final RagProperties properties = new RagProperties();
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    private final RagIndexService service = new RagIndexService(tasks, files, es, properties, jdbc,
            JsonMapper.builder().build(), new TransactionTemplate(transactionManager));

    @BeforeAll
    static void registerEntityMapping() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "index-tests"), AiCustomerServiceFileStorageDO.class);
    }

    @BeforeEach
    void enableRealTransactionCallbacks() {
        when(transactionManager.getTransaction(any())).thenAnswer(call -> new SimpleTransactionStatus());
    }

    @Test
    void enqueuePreservesFileLibraryAndOperationWithoutReadingRemovedOwner() {
        service.enqueueUpsert(42L, 7L);
        service.enqueueDelete(43L, 8L);

        verify(tasks).enqueue(42L, 7L, "UPSERT");
        verify(tasks).enqueue(43L, 8L, "DELETE");
        verifyNoInteractions(files, es, jdbc);
    }

    @Test
    @SuppressWarnings("unchecked")
    void upsertReplacesOnlyItsFileThenMapsExistingVectorIdsAndOwnedMetadata() throws Exception {
        RagIndexTaskDO task = task("UPSERT");
        when(tasks.lockNext()).thenReturn(task, null);
        when(files.selectById(42L)).thenReturn(owner(42L, 7L, AiCustomerServiceFileStatusEnum.COMPLETED));
        when(jdbc.query(anyString(), any(RowMapper.class), eq("42"), eq("7"))).thenAnswer(call -> {
            assertThat((String) call.getArgument(0)).contains("metadata->>'mdStorageId'=?",
                    "metadata->>'knowledge_base_id'=?", "ORDER BY id");
            RowMapper<Document> rowMapper = call.getArgument(1);
            ResultSet row = mock(ResultSet.class);
            when(row.getString("id")).thenReturn("stable-vector-id");
            when(row.getString("content")).thenReturn("保修一年");
            when(row.getString("metadata")).thenReturn("{\"mdStorageId\":42,\"knowledge_base_id\":\"7\",\"originalFileName\":\"warranty.md\"}");
            return List.of(rowMapper.mapRow(row, 0));
        });

        service.synchronizePending();

        ArgumentCaptor<List<Document>> documents = ArgumentCaptor.forClass(List.class);
        verify(es).upsert(documents.capture());
        assertThat(documents.getValue()).singleElement().satisfies(document -> {
            assertThat(document.getId()).isEqualTo("stable-vector-id");
            assertThat(document.getText()).isEqualTo("保修一年");
            assertThat(document.getMetadata()).containsEntry("knowledge_base_id", "7")
                    .containsEntry("originalFileName", "warranty.md");
            assertThat(((Number) document.getMetadata().get("mdStorageId")).longValue()).isEqualTo(42L);
        });
        InOrder sequence = inOrder(tasks, es, files, jdbc);
        sequence.verify(tasks).lockNext();
        sequence.verify(es).ensureIndex();
        sequence.verify(es).deleteByFileId(42L);
        sequence.verify(files).selectById(42L);
        sequence.verify(jdbc).query(anyString(), any(RowMapper.class), eq("42"), eq("7"));
        sequence.verify(es).upsert(anyList());
        sequence.verify(tasks).deleteById(42L);
        verify(files, never()).lockById(anyLong());
        verify(files, never()).updateById(any(AiCustomerServiceFileStorageDO.class));
    }

    @Test
    void deleteStillCompletesAfterTheOwnerHasBeenRemoved() {
        when(tasks.lockNext()).thenReturn(task("DELETE"), null);
        when(files.selectById(42L)).thenReturn(null);

        service.synchronizePending();

        verify(es).deleteByFileId(42L);
        verify(tasks).deleteById(42L);
        verify(es, never()).upsert(anyList());
        verifyNoInteractions(jdbc);
    }

    @Test
    void deletedMovedAndUnreadyOwnersRemoveStaleEsChunksWithoutRecreatingThem() {
        when(tasks.lockNext()).thenReturn(task("UPSERT"), null, task("UPSERT"), null, task("UPSERT"), null);
        when(files.selectById(42L)).thenReturn(null,
                owner(42L, 8L, AiCustomerServiceFileStatusEnum.COMPLETED),
                owner(42L, 7L, AiCustomerServiceFileStatusEnum.VECTORIZING));

        service.synchronizePending();
        service.synchronizePending();
        service.synchronizePending();

        verify(es, times(3)).deleteByFileId(42L);
        verify(tasks, times(3)).deleteById(42L);
        verify(es, never()).upsert(anyList());
        verifyNoInteractions(jdbc);
    }

    @Test
    @SuppressWarnings("unchecked")
    void failedUpsertKeepsItsOriginalScopeAndCanSucceedOnALaterRetry() {
        RagIndexTaskDO task = task("UPSERT");
        when(tasks.lockNext()).thenReturn(task, null, task, null);
        when(files.selectById(42L)).thenReturn(owner(42L, 7L, AiCustomerServiceFileStatusEnum.COMPLETED));
        when(jdbc.query(anyString(), any(RowMapper.class), eq("42"), eq("7")))
                .thenReturn(List.of(new Document("stable", "保修", java.util.Map.of("knowledge_base_id", "7"))));
        doThrow(new IllegalStateException("sensitive upstream body must not be stored")).doNothing().when(es).upsert(anyList());
        LocalDateTime before = LocalDateTime.now();

        service.synchronizePending();

        verify(tasks, never()).deleteById(42L);
        verify(tasks).updateById(task);
        assertThat(task.getAttempts()).isEqualTo(1);
        assertThat(task.getLastError()).isEqualTo("IllegalStateException");
        assertThat(task.getNextAttemptTime()).isAfterOrEqualTo(before.plusSeconds(5));
        assertThat(task.getOperation()).isEqualTo("UPSERT");
        assertThat(task.getKnowledgeBaseId()).isEqualTo(7L);

        service.synchronizePending();

        verify(tasks).deleteById(42L);
        verify(es, times(2)).upsert(anyList());
    }

    @Test
    void deleteFailureRetainsTheTombstoneAndCappedBackoffEvenWithoutAnOwner() {
        RagIndexTaskDO task = task("DELETE");
        task.setAttempts(20);
        when(tasks.lockNext()).thenReturn(task, null, task, null);
        doThrow(new IllegalStateException("ES unavailable")).doNothing().when(es).deleteByFileId(42L);
        LocalDateTime before = LocalDateTime.now();

        service.synchronizePending();

        verify(tasks, never()).deleteById(42L);
        verify(tasks).updateById(task);
        assertThat(task.getOperation()).isEqualTo("DELETE");
        assertThat(task.getFileId()).isEqualTo(42L);
        assertThat(task.getKnowledgeBaseId()).isEqualTo(7L);
        assertThat(task.getAttempts()).isEqualTo(21);
        assertThat(task.getNextAttemptTime()).isBetween(before.plusSeconds(60), LocalDateTime.now().plusSeconds(60));

        service.synchronizePending();

        verify(tasks).deleteById(42L);
        verify(es, never()).upsert(anyList());
        verifyNoInteractions(jdbc);
    }

    @Test
    @SuppressWarnings("unchecked")
    void completedOwnerWithNoVectorsKeepsUpsertForRepairRatherThanClaimingSynced() {
        RagIndexTaskDO task = task("UPSERT");
        when(tasks.lockNext()).thenReturn(task, null);
        when(files.selectById(42L)).thenReturn(owner(42L, 7L, AiCustomerServiceFileStatusEnum.COMPLETED));
        when(jdbc.query(anyString(), any(RowMapper.class), eq("42"), eq("7"))).thenReturn(List.of());

        service.synchronizePending();

        verify(tasks, never()).deleteById(42L);
        verify(tasks).updateById(task);
        assertThat(task.getAttempts()).isEqualTo(1);
        verify(es, never()).upsert(anyList());
    }

    @Test
    void disabledIndexingLeavesPendingWorkUntouchedAndDoesNotStartATransaction() {
        properties.getElasticsearch().setEnabled(false);

        service.synchronizePending();

        verifyNoInteractions(tasks, files, es, jdbc, transactionManager);
    }

    @Test
    void unavailableQueueStopsWithoutTouchingAnOwnerOrEs() {
        when(tasks.lockNext()).thenThrow(new IllegalStateException("database unavailable"));

        service.synchronizePending();

        verify(tasks, times(1)).lockNext();
        verifyNoInteractions(files, es, jdbc);
        verify(transactionManager).rollback(any());
    }

    @Test
    void statusSeparatesParsingReadinessDisabledSyncAndRetryFailure() {
        AiCustomerServiceFileStorageDO file = owner(42L, 7L, AiCustomerServiceFileStatusEnum.PENDING);
        assertThat(service.status(file)).isEqualTo("NOT_READY");
        verifyNoInteractions(tasks);
        file.setStatus(AiCustomerServiceFileStatusEnum.COMPLETED.getCode());
        properties.getElasticsearch().setEnabled(false);
        assertThat(service.status(file)).isEqualTo("DISABLED");
        verifyNoInteractions(tasks);
        properties.getElasticsearch().setEnabled(true);
        assertThat(service.status(file)).isEqualTo("SYNCED");
        RagIndexTaskDO task = task("UPSERT");
        when(tasks.selectById(42L)).thenReturn(task);
        assertThat(service.status(file)).isEqualTo("PENDING");
        task.setAttempts(1);
        assertThat(service.status(file)).isEqualTo("FAILED");
    }

    @Test
    @SuppressWarnings("unchecked")
    void reindexEnqueuesOnlyCompletedFilesFromTheSelectedLibrary() {
        when(files.selectList(any(LambdaQueryWrapper.class))).thenAnswer(call -> {
            LambdaQueryWrapper<AiCustomerServiceFileStorageDO> query = call.getArgument(0);
            assertThat(query.getSqlSegment()).contains("knowledge_base_id =", "status =");
            assertThat(query.getParamNameValuePairs().values()).containsExactlyInAnyOrder(
                    7L, AiCustomerServiceFileStatusEnum.COMPLETED.getCode());
            return List.of(owner(42L, 7L, AiCustomerServiceFileStatusEnum.COMPLETED),
                    owner(43L, 7L, AiCustomerServiceFileStatusEnum.COMPLETED));
        });

        assertThat(service.reindex(7L)).isEqualTo(2);

        verify(tasks).enqueue(42L, 7L, "UPSERT");
        verify(tasks).enqueue(43L, 7L, "UPSERT");
        verifyNoInteractions(es, jdbc);
    }

    private RagIndexTaskDO task(String operation) {
        RagIndexTaskDO task = new RagIndexTaskDO();
        task.setFileId(42L);
        task.setKnowledgeBaseId(7L);
        task.setOperation(operation);
        task.setAttempts(0);
        return task;
    }

    private AiCustomerServiceFileStorageDO owner(long id, long library, AiCustomerServiceFileStatusEnum status) {
        return AiCustomerServiceFileStorageDO.builder().id(id).knowledgeBaseId(library).status(status.getCode()).build();
    }
}
