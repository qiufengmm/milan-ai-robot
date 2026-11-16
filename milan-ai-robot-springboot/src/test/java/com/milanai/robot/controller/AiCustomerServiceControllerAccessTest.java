package com.milanai.robot.controller;

import com.milanai.robot.model.vo.customerService.EnterpriseChatCompletionReqVO;
import com.milanai.robot.model.vo.customerService.UploadChunkReqVO;
import com.milanai.robot.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class AiCustomerServiceControllerAccessTest {
    private final CustomerService files = mock(CustomerService.class);
    private final KnowledgeAccessService access = mock(KnowledgeAccessService.class);
    private final KnowledgeBaseService libraries = mock(KnowledgeBaseService.class);
    private final EnterpriseConversationService conversations = mock(EnterpriseConversationService.class);
    private final EnterpriseKnowledgeChatService chat = mock(EnterpriseKnowledgeChatService.class);
    private final AiCustomerServiceController controller =
            new AiCustomerServiceController(files, libraries, access, conversations, chat);

    @Test
    void unauthorizedUploadStopsBeforeCustomerServiceIsCalled() {
        doThrow(new AccessDeniedException("editor only")).when(access).requireEdit(2L);
        UploadChunkReqVO request = UploadChunkReqVO.builder().knowledgeBaseId(2L).build();
        assertThatThrownBy(() -> controller.uploadChunk(request)).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(files);
    }

    @Test
    void unauthorizedConversationStopsBeforeKnowledgeLookupOrModelCall() {
        when(conversations.beginRequest("foreign-chat", "request-1", "政策是什么"))
                .thenThrow(new AccessDeniedException("conversation owner only"));
        EnterpriseChatCompletionReqVO request = new EnterpriseChatCompletionReqVO();
        request.setChatId("foreign-chat");
        request.setRequestId("request-1");
        request.setMessage("政策是什么");
        assertThatThrownBy(() -> controller.chat(request)).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(libraries, chat);
    }
}
