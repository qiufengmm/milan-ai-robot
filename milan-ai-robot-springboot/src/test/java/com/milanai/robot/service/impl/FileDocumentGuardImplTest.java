package com.milanai.robot.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.milanai.robot.domain.dos.AiCustomerServiceFileStorageDO;
import com.milanai.robot.domain.mapper.AiCustomerServiceFileStorageMapper;
import com.milanai.robot.enums.AiCustomerServiceFileStatusEnum;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class FileDocumentGuardImplTest {
    private final AiCustomerServiceFileStorageMapper mapper = mock(AiCustomerServiceFileStorageMapper.class);
    private final FileDocumentGuardImpl guard = new FileDocumentGuardImpl(mapper);

    @BeforeAll
    static void registerEntityMapping() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "guard-tests"), AiCustomerServiceFileStorageDO.class);
    }

    @Test
    @SuppressWarnings("unchecked")
    void stringAndNumericMetadataIdsUseOneReadyOwnerQueryAndRetainOriginalDocuments() {
        Document stringFile = doc("string", "42", "7");
        Document longFile = doc("long", 43L, 7L);
        Document stale = doc("stale", 44L, "7");
        when(mapper.selectList(any(LambdaQueryWrapper.class))).thenAnswer(call -> {
            LambdaQueryWrapper<AiCustomerServiceFileStorageDO> query = call.getArgument(0);
            assertThat(query.getSqlSegment()).contains("id IN", "knowledge_base_id =", "status =");
            assertThat(query.getParamNameValuePairs().values()).containsExactlyInAnyOrder(
                    42L, 43L, 44L, 7L, AiCustomerServiceFileStatusEnum.COMPLETED.getCode());
            return List.of(owner(42L, 7L), owner(43L, 7L));
        });

        List<Document> result = guard.retainOwned(List.of(stringFile, longFile, stale), 7L);

        assertThat(result).containsExactly(stringFile, longFile);
        assertThat(result.getFirst()).isSameAs(stringFile);
        verify(mapper, times(1)).selectList(any(LambdaQueryWrapper.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void wrongLibraryMetadataAndMalformedFileIdsAreRemovedBeforeOwnerLookup() {
        when(mapper.selectList(any(LambdaQueryWrapper.class))).thenAnswer(call -> {
            LambdaQueryWrapper<AiCustomerServiceFileStorageDO> query = call.getArgument(0);
            query.getSqlSegment();
            assertThat(query.getParamNameValuePairs().values()).containsExactlyInAnyOrder(
                    42L, 7L, AiCustomerServiceFileStatusEnum.COMPLETED.getCode());
            return List.of(owner(42L, 7L));
        });
        Document valid = doc("valid", 42L, "7");

        assertThat(guard.retainOwned(List.of(valid, doc("other-library", 99L, "8"),
                doc("malformed", "42x", "7"), doc("decimal", 42.5, "7"),
                new Document("missing-file", "text", Map.of("knowledge_base_id", "7")),
                new Document("missing-library", "text", Map.of("mdStorageId", 42L))), 7L))
                .containsExactly(valid);
    }

    @Test
    @SuppressWarnings("unchecked")
    void missingUnreadyAndDifferentLibraryOwnersCannotAuthorizeStaleChunks() {
        when(mapper.selectList(any(LambdaQueryWrapper.class))).thenAnswer(call -> {
            LambdaQueryWrapper<AiCustomerServiceFileStorageDO> query = call.getArgument(0);
            assertThat(query.getSqlSegment()).contains("knowledge_base_id =", "status =");
            assertThat(query.getParamNameValuePairs().values()).contains(7L, AiCustomerServiceFileStatusEnum.COMPLETED.getCode());
            // The database scope/status predicates exclude missing, other-library, and unready owners.
            return List.of();
        });

        assertThat(guard.retainOwned(List.of(doc("deleted", 42L, "7"), doc("unready", 43L, "7"),
                doc("owner-moved", 44L, "7")), 7L)).isEmpty();
    }

    @Test
    void absentCandidatesAndInvalidOrUnmatchedScopesNeverQueryAllOwners() {
        assertThat(guard.retainOwned(null, 7L)).isEmpty();
        assertThat(guard.retainOwned(List.of(), 7L)).isEmpty();
        assertThat(guard.retainOwned(List.of(doc("a", 42L, "7")), 0L)).isEmpty();
        assertThat(guard.retainOwned(List.of(doc("a", 42L, "7")), -1L)).isEmpty();
        assertThat(guard.retainOwned(List.of(doc("foreign", 42L, "8")), 7L)).isEmpty();
        verifyNoInteractions(mapper);
    }

    private Document doc(String id, Object fileId, Object kb) {
        return new Document(id, "政策 " + id, Map.of("mdStorageId", fileId, "knowledge_base_id", kb));
    }

    private AiCustomerServiceFileStorageDO owner(long id, long kb) {
        return AiCustomerServiceFileStorageDO.builder().id(id).knowledgeBaseId(kb)
                .status(AiCustomerServiceFileStatusEnum.COMPLETED.getCode()).build();
    }
}
