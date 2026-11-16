package com.milanai.robot.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.milanai.robot.domain.dos.ChatDO;
import com.milanai.robot.domain.dos.ChatMessageDO;
import com.milanai.robot.domain.dos.EnterpriseUserDetails;
import com.milanai.robot.domain.mapper.ChatMapper;
import com.milanai.robot.domain.mapper.ChatMessageMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EnterpriseConversationServiceTest {
    private final ChatMapper chats = mock(ChatMapper.class);
    private final ChatMessageMapper messages = mock(ChatMessageMapper.class);
    private final KnowledgeAccessService access = mock(KnowledgeAccessService.class);
    private final EnterpriseConversationService service =
            new EnterpriseConversationService(chats, messages, access, JsonMapper.builder().build());

    @Test
    void otherUsersCannotStartARequestInSomeoneElsesChat() {
        when(access.currentUser()).thenReturn(user(7L));
        when(chats.lockByUuid("chat-1")).thenReturn(ChatDO.builder()
                .uuid("chat-1").userId(8L).knowledgeBaseId(2L).build());

        assertThatThrownBy(() -> service.beginRequest("chat-1", "request-1", "VPN 问题"))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("statusCode").isEqualTo(HttpStatus.NOT_FOUND);
        verify(messages, never()).insert(any(ChatMessageDO.class));
    }

    @Test
    void beginRequestPersistsUserAndGeneratingAssistantBeforeStreaming() {
        when(access.currentUser()).thenReturn(user(7L));
        when(chats.lockByUuid("chat-1")).thenReturn(ChatDO.builder()
                .uuid("chat-1").userId(7L).knowledgeBaseId(2L).build());
        when(messages.selectCount(any(Wrapper.class))).thenReturn(0L, 0L);
        AtomicLong id = new AtomicLong(20L);
        when(messages.insert(any(ChatMessageDO.class))).thenAnswer(call -> {
            ((ChatMessageDO) call.getArgument(0)).setId(id.incrementAndGet());
            return 1;
        });
        when(messages.selectCompletedConversationHistory("chat-1", 6)).thenReturn(List.of());

        EnterpriseConversationService.TurnStart turn = service.beginRequest("chat-1", "request-1", "VPN 问题");

        assertThat(turn.assistantMessageId()).isEqualTo(22L);
        var saved = org.mockito.ArgumentCaptor.forClass(ChatMessageDO.class);
        verify(messages, org.mockito.Mockito.times(2)).insert(saved.capture());
        assertThat(saved.getAllValues()).extracting(ChatMessageDO::getRole).containsExactly("user", "assistant");
        assertThat(saved.getAllValues()).extracting(ChatMessageDO::getRequestId).containsOnly("request-1");
        assertThat(saved.getAllValues()).extracting(ChatMessageDO::getStatus)
                .containsExactly("COMPLETED", "GENERATING");
        verify(access).requireRead(2L);
    }

    @Test
    void duplicateRequestIsRejectedWithoutStartingAnotherModelTurn() {
        when(access.currentUser()).thenReturn(user(7L));
        when(chats.lockByUuid("chat-1")).thenReturn(ChatDO.builder()
                .uuid("chat-1").userId(7L).knowledgeBaseId(2L).build());
        when(messages.selectCount(any(Wrapper.class))).thenReturn(1L);

        assertThatThrownBy(() -> service.beginRequest("chat-1", "request-1", "VPN 问题"))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("statusCode").isEqualTo(HttpStatus.CONFLICT);
        verify(messages, never()).insert(any(ChatMessageDO.class));
    }

    @Test
    void completionUsesConditionalGeneratingTransitionAndPersistsSources() {
        when(messages.selectById(22L)).thenReturn(ChatMessageDO.builder().id(22L).role("assistant")
                .status("GENERATING").build());
        when(messages.completeGenerating(org.mockito.ArgumentMatchers.eq(22L),
                org.mockito.ArgumentMatchers.eq("已完成"), org.mockito.ArgumentMatchers.eq("VPN"),
                org.mockito.ArgumentMatchers.anyString())).thenReturn(1);

        service.complete(22L, "已完成", "VPN", List.of());

        verify(messages).completeGenerating(org.mockito.ArgumentMatchers.eq(22L),
                org.mockito.ArgumentMatchers.eq("已完成"), org.mockito.ArgumentMatchers.eq("VPN"),
                org.mockito.ArgumentMatchers.contains("[]"));
        verify(messages, never()).updateById(any(ChatMessageDO.class));
    }

    @Test
    void cancellationCanOnlyWinWhileMessageRemainsGenerating() {
        when(messages.finishGenerating(22L, "partial", "CANCELLED", "CANCELLED")).thenReturn(1);

        service.cancel(22L, "partial");

        verify(messages).finishGenerating(22L, "partial", "CANCELLED", "CANCELLED");
        verify(messages, never()).updateById(any(ChatMessageDO.class));
    }

    private EnterpriseUserDetails user(long id) {
        return new EnterpriseUserDetails(id, "employee", "Employee", "USER", "hash", true, List.of());
    }
}
