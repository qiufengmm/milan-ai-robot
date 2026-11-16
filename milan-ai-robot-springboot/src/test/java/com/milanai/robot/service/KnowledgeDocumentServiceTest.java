package com.milanai.robot.service;

import com.milanai.robot.domain.dos.AiCustomerServiceFileStorageDO;
import com.milanai.robot.domain.dos.KnowledgeDocumentDO;
import com.milanai.robot.domain.mapper.AiCustomerServiceFileStorageMapper;
import com.milanai.robot.domain.mapper.KnowledgeDocumentMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doAnswer;

class KnowledgeDocumentServiceTest {
    private final KnowledgeDocumentMapper documents = mock(KnowledgeDocumentMapper.class);
    private final AiCustomerServiceFileStorageMapper files = mock(AiCustomerServiceFileStorageMapper.class);
    private final KnowledgeBaseService libraries = mock(KnowledgeBaseService.class);
    private final RagIndexService indexes = mock(RagIndexService.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final KnowledgeDocumentService service =
            new KnowledgeDocumentService(documents, files, libraries, indexes, jdbc);

    @Test
    void firstUploadCreatesLogicalDocumentAndDraftVersionOne() {
        when(libraries.require(2L)).thenReturn(com.milanai.robot.domain.dos.KnowledgeBaseDO.builder().id(2L).build());
        doAnswer(invocation -> {
            ((KnowledgeDocumentDO) invocation.getArgument(0)).setId(90L);
            return 1;
        }).when(documents).insert(any(KnowledgeDocumentDO.class));
        when(files.selectMaxVersion(90L)).thenReturn(0);
        when(files.updateById(any(AiCustomerServiceFileStorageDO.class))).thenReturn(1);

        service.attachDraftToUpload(44L, 2L, "VPN Guide", null, 7L);

        verify(documents).insert(org.mockito.ArgumentMatchers.<KnowledgeDocumentDO>argThat(doc ->
                doc.getKnowledgeBaseId().equals(2L) && doc.getTitle().equals("VPN Guide")
                        && doc.getCreateBy().equals(7L)));
        verify(files).updateById(org.mockito.ArgumentMatchers.<AiCustomerServiceFileStorageDO>argThat(file ->
                file.getId().equals(44L) && file.getDocumentId().equals(90L)
                        && file.getVersionNo().equals(1) && file.getPublishStatus().equals("DRAFT")));
    }

    @Test
    void crossLibraryReplacementIsRejectedBeforeAttachingFile() {
        when(documents.selectById(90L)).thenReturn(KnowledgeDocumentDO.builder()
                .id(90L).knowledgeBaseId(1L).build());

        assertThatThrownBy(() -> service.requireSameLibrary(90L, 2L))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void publishesOnlyACompletedAndIndexedVersionAndArchivesThePriorOne() {
        KnowledgeDocumentDO document = KnowledgeDocumentDO.builder()
                .id(9L).knowledgeBaseId(2L).title("IT Password Reset").currentFileId(31L).build();
        when(documents.lockById(9L)).thenReturn(document);
        when(files.selectById(32L)).thenReturn(AiCustomerServiceFileStorageDO.builder()
                .id(32L).knowledgeBaseId(2L).documentId(9L).versionNo(2)
                .status(3).publishStatus("DRAFT").build());
        when(files.selectById(31L)).thenReturn(AiCustomerServiceFileStorageDO.builder()
                .id(31L).knowledgeBaseId(2L).documentId(9L).versionNo(1)
                .status(3).publishStatus("PUBLISHED").build());
        when(indexes.status(any(AiCustomerServiceFileStorageDO.class))).thenReturn("SYNCED");

        service.publish(9L, 32L);

        verify(files).updateById(org.mockito.ArgumentMatchers.<AiCustomerServiceFileStorageDO>argThat(file ->
                file.getId().equals(31L) && file.getPublishStatus().equals("ARCHIVED")));
        verify(files).updateById(org.mockito.ArgumentMatchers.<AiCustomerServiceFileStorageDO>argThat(file ->
                file.getId().equals(32L) && file.getPublishStatus().equals("PUBLISHED")));
        verify(documents).updateById(org.mockito.ArgumentMatchers.<KnowledgeDocumentDO>argThat(row -> row.getCurrentFileId().equals(32L)));
    }

    @Test
    void failedOrUnsyncedReplacementCannotDisplaceCurrentVersion() {
        KnowledgeDocumentDO document = KnowledgeDocumentDO.builder()
                .id(9L).knowledgeBaseId(2L).currentFileId(31L).build();
        when(documents.lockById(9L)).thenReturn(document);
        when(files.selectById(32L)).thenReturn(AiCustomerServiceFileStorageDO.builder()
                .id(32L).knowledgeBaseId(2L).documentId(9L).versionNo(2)
                .status(4).publishStatus("DRAFT").build());

        assertThatThrownBy(() -> service.publish(9L, 32L))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("statusCode").isEqualTo(HttpStatus.CONFLICT);
        verify(documents, never()).updateById(any(KnowledgeDocumentDO.class));
        verify(files, never()).updateById(any(AiCustomerServiceFileStorageDO.class));
    }

    @Test
    void publishedOrHistoricalVersionCannotBeDeleted() {
        assertThatThrownBy(() -> service.assertDraftCanBeDeleted(AiCustomerServiceFileStorageDO.builder()
                        .id(31L).publishStatus("ARCHIVED").build()))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void archivedVersionCannotBeRepublishedAsRollback() {
        when(documents.lockById(9L)).thenReturn(KnowledgeDocumentDO.builder()
                .id(9L).knowledgeBaseId(2L).currentFileId(32L).build());
        when(files.selectById(31L)).thenReturn(AiCustomerServiceFileStorageDO.builder()
                .id(31L).knowledgeBaseId(2L).documentId(9L).versionNo(1).status(3)
                .publishStatus("ARCHIVED").build());
        when(indexes.status(any(AiCustomerServiceFileStorageDO.class))).thenReturn("SYNCED");

        assertThatThrownBy(() -> service.publish(9L, 31L))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("历史版本");
        verify(documents, never()).updateById(any(KnowledgeDocumentDO.class));
    }
}
