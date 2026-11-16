package com.milanai.robot.service;

import com.milanai.robot.domain.dos.ChatDO;
import com.milanai.robot.domain.dos.ChatMessageDO;
import com.milanai.robot.domain.dos.EnterpriseUserDetails;
import com.milanai.robot.domain.mapper.ChatMapper;
import com.milanai.robot.domain.mapper.ChatMessageMapper;
import com.milanai.robot.domain.mapper.KnowledgeDocumentMapper;
import com.milanai.robot.domain.dos.KnowledgeDocumentDO;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AnswerSourceServiceTest {
    private final ChatMapper chats = mock(ChatMapper.class);
    private final ChatMessageMapper messages = mock(ChatMessageMapper.class);
    private final KnowledgeAccessService access = mock(KnowledgeAccessService.class);
    private final KnowledgeDocumentMapper documents = mock(KnowledgeDocumentMapper.class);
    private final AnswerSourceService service = new AnswerSourceService(chats, messages, access,
            documents, JsonMapper.builder().build());

    @Test
    void returnsSourcesOnlyForCompletedAnswerOwnedByCurrentUserAndAuthorizedLibrary() {
        when(access.currentUser()).thenReturn(new EnterpriseUserDetails(7L, "employee", "E", "USER", "hash", true, List.of()));
        when(messages.selectById(22L)).thenReturn(ChatMessageDO.builder().id(22L).chatUuid("chat-1")
                .role("assistant").status("COMPLETED")
                .sourcesJson("[{\"documentId\":\"4\",\"versionNo\":2,\"title\":\"VPN 指引\",\"fileName\":\"vpn.md\",\"fileId\":\"6\",\"chunkId\":\"chunk-1\",\"excerpt\":\"步骤\",\"historical\":false}]")
                .build());
        when(chats.selectOne(any())).thenReturn(ChatDO.builder().uuid("chat-1").userId(7L)
                .knowledgeBaseId(2L).build());
        when(documents.selectById(4L)).thenReturn(KnowledgeDocumentDO.builder().id(4L).currentFileId(6L).build());

        List<EnterpriseKnowledgeChatService.SourceCard> result = service.getSources(22L);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).title()).isEqualTo("VPN 指引");
        assertThat(result.get(0).historical()).isFalse();
        verify(access).requireRead(2L);
    }

    @Test
    void hidesAnotherUsersAnswerAsNotFoundBeforeCheckingLibraryAccess() {
        when(access.currentUser()).thenReturn(new EnterpriseUserDetails(7L, "employee", "E", "USER", "hash", true, List.of()));
        when(messages.selectById(22L)).thenReturn(ChatMessageDO.builder().id(22L).chatUuid("chat-1")
                .role("assistant").status("COMPLETED").sourcesJson("[]").build());
        when(chats.selectOne(any())).thenReturn(ChatDO.builder().uuid("chat-1").userId(8L)
                .knowledgeBaseId(2L).build());

        assertThatThrownBy(() -> service.getSources(22L)).isInstanceOf(ResponseStatusException.class)
                .extracting("statusCode").isEqualTo(HttpStatus.NOT_FOUND);
        verify(access, never()).requireRead(2L);
    }

    @Test
    void refusesSourcesForFailedOrGeneratingAnswer() {
        when(messages.selectById(22L)).thenReturn(ChatMessageDO.builder().id(22L).role("assistant")
                .status("FAILED").build());

        assertThatThrownBy(() -> service.getSources(22L)).isInstanceOf(ResponseStatusException.class)
                .extracting("statusCode").isEqualTo(HttpStatus.NOT_FOUND);
    }
}
