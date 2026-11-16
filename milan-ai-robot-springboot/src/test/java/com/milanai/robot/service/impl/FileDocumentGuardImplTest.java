package com.milanai.robot.service.impl;

import com.milanai.robot.domain.dos.AiCustomerServiceFileStorageDO;
import com.milanai.robot.domain.mapper.AiCustomerServiceFileStorageMapper;
import com.milanai.robot.enums.AiCustomerServiceFileStatusEnum;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.mockito.Mockito.*;

class FileDocumentGuardImplTest {
    private final AiCustomerServiceFileStorageMapper mapper = mock(AiCustomerServiceFileStorageMapper.class);
    private final FileDocumentGuardImpl guard = new FileDocumentGuardImpl(mapper);

    @Test
    void missingPublishedFileSetCannotFallBackToCompletedFileChecks() {
        assertThatIllegalArgumentException().isThrownBy(() -> guard.retainOwned(List.of(doc("draft", 42L, "7")), 7L, null));
        verifyNoInteractions(mapper);
    }

    @Test
    void emptyPublishedFileSetNeverQueriesOwners() {
        assertThat(guard.retainOwned(List.of(doc("draft", 42L, "7")), 7L, Set.of())).isEmpty();
        verifyNoInteractions(mapper);
    }

    @Test
    void stringAndNumericMetadataIdsUseOneCurrentPublishedOwnerQuery() {
        Document stringFile = doc("string", "42", "7");
        Document longFile = doc("long", 43L, 7L);
        Document stale = doc("stale", 44L, "7");
        when(mapper.selectCurrentPublishedFiles(eq(7L), anyList())).thenAnswer(call -> {
            List<Long> ids = call.getArgument(1);
            assertThat(ids).containsExactlyInAnyOrder(42L, 43L, 44L);
            return List.of(owner(42L, 7L), owner(43L, 7L));
        });

        List<Document> result = guard.retainOwned(List.of(stringFile, longFile, stale), 7L, Set.of(42L, 43L, 44L));

        assertThat(result).extracting(Document::getId).containsExactly("string", "long");
        assertThat(result.getFirst().getMetadata()).containsEntry("mdStorageId", "42")
                .containsEntry("knowledge_base_id", "7");
        verify(mapper, times(1)).selectCurrentPublishedFiles(eq(7L), anyList());
    }

    @Test
    void wrongLibraryMetadataAndMalformedFileIdsAreRemovedBeforeOwnerLookup() {
        when(mapper.selectCurrentPublishedFiles(eq(7L), anyList())).thenAnswer(call -> {
            List<Long> ids = call.getArgument(1);
            assertThat(ids).containsExactly(42L);
            return List.of(owner(42L, 7L));
        });
        Document valid = doc("valid", 42L, "7");

        assertThat(guard.retainOwned(List.of(valid, doc("other-library", 99L, "8"), doc("unpublished", 45L, "7"),
                doc("malformed", "42x", "7"), doc("decimal", 42.5, "7"),
                new Document("missing-file", "text", Map.of("knowledge_base_id", "7")),
                new Document("missing-library", "text", Map.of("mdStorageId", 42L))), 7L, Set.of(42L, 99L)))
                .extracting(Document::getId).containsExactly("valid");
    }

    @Test
    void missingUnreadyAndDifferentLibraryOwnersCannotAuthorizeStaleChunks() {
        when(mapper.selectCurrentPublishedFiles(eq(7L), anyList())).thenReturn(List.of());

        assertThat(guard.retainOwned(List.of(doc("deleted", 42L, "7"), doc("unready", 43L, "7"),
                doc("owner-moved", 44L, "7")), 7L, Set.of(42L, 43L, 44L))).isEmpty();
    }

    @Test
    void absentCandidatesAndInvalidOrUnmatchedScopesNeverQueryAllOwners() {
        assertThat(guard.retainOwned(null, 7L, Set.of(42L))).isEmpty();
        assertThat(guard.retainOwned(List.of(), 7L, Set.of(42L))).isEmpty();
        assertThat(guard.retainOwned(List.of(doc("a", 42L, "7")), 0L, Set.of(42L))).isEmpty();
        assertThat(guard.retainOwned(List.of(doc("a", 42L, "7")), -1L, Set.of(42L))).isEmpty();
        assertThat(guard.retainOwned(List.of(doc("foreign", 42L, "8")), 7L, Set.of(42L))).isEmpty();
        verifyNoInteractions(mapper);
    }

    @Test
    void finalGuardRechecksCurrentPublicationPointerAfterBothSearchRoutes() {
        Document active = doc("active", 42L, "7");
        Document becameOld = doc("became-old", 43L, "7");
        Document notPublished = doc("not-published", 44L, "7");
        when(mapper.selectCurrentPublishedFiles(eq(7L), anyList()))
                .thenReturn(List.of(owner(42L, 7L)));

        List<Document> result = guard.retainOwned(List.of(active, becameOld, notPublished), 7L,
                Set.of(42L, 43L, 44L));

        assertThat(result).extracting(Document::getId).containsExactly("active");
        assertThat(result.getFirst().getMetadata()).containsEntry("mdStorageId", "42")
                .containsEntry("knowledge_base_id", "7");
        verify(mapper).selectCurrentPublishedFiles(eq(7L), anyList());
    }

    private Document doc(String id, Object fileId, Object kb) {
        return new Document(id, "政策 " + id, Map.of("mdStorageId", fileId, "knowledge_base_id", kb));
    }

    private AiCustomerServiceFileStorageDO owner(long id, long kb) {
        return AiCustomerServiceFileStorageDO.builder().id(id).knowledgeBaseId(kb)
                .status(AiCustomerServiceFileStatusEnum.COMPLETED.getCode()).build();
    }
}
