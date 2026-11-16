package com.milanai.robot.controller;

import com.milanai.robot.domain.dos.KnowledgeBaseDO;
import com.milanai.robot.model.vo.customerService.KnowledgeBaseReqVO;
import com.milanai.robot.service.KnowledgeAccessService;
import com.milanai.robot.service.KnowledgeBaseService;
import com.milanai.robot.service.RagIndexService;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgeBaseControllerAccessTest {
    private final KnowledgeBaseService libraries = mock(KnowledgeBaseService.class);
    private final RagIndexService indexes = mock(RagIndexService.class);
    private final KnowledgeAccessService access = mock(KnowledgeAccessService.class);
    private final KnowledgeBaseController controller = new KnowledgeBaseController(libraries, indexes, access);

    @Test
    void enterpriseLibraryListUsesOnlyAccessibleLibraries() {
        when(access.listAccessibleKnowledgeBases()).thenReturn(List.of(KnowledgeBaseDO.builder().id(2L).build()));

        controller.list();

        verify(access).listAccessibleKnowledgeBases();
    }

    @Test
    void nonAdminCannotCreateLibrary() {
        org.mockito.Mockito.doThrow(new AccessDeniedException("admin only"))
                .when(access).requireAdministrator();

        assertThatThrownBy(() -> controller.create(new KnowledgeBaseReqVO()))
                .isInstanceOf(AccessDeniedException.class);
        verify(libraries, never()).create(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void editorAuthorizationPrecedesReindexWork() {
        org.mockito.Mockito.doThrow(new AccessDeniedException("editor only"))
                .when(access).requireEdit(3L);

        assertThatThrownBy(() -> controller.reindex(3L)).isInstanceOf(AccessDeniedException.class);
        verify(indexes, never()).reindex(3L);
    }
}
