package com.milanai.robot.service;

import com.milanai.robot.domain.dos.AnswerFeedbackDO;
import com.milanai.robot.domain.dos.EnterpriseUserDetails;
import com.milanai.robot.domain.dos.FeedbackAnswerContext;
import com.milanai.robot.domain.dos.FeedbackQueueRow;
import com.milanai.robot.domain.mapper.AnswerFeedbackMapper;
import com.milanai.robot.domain.mapper.KnowledgeDocumentMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
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

class AnswerFeedbackServiceTest {
    private final AnswerFeedbackMapper feedbacks = mock(AnswerFeedbackMapper.class);
    private final KnowledgeDocumentMapper documents = mock(KnowledgeDocumentMapper.class);
    private final KnowledgeAccessService access = mock(KnowledgeAccessService.class);
    private final AnswerFeedbackService service = new AnswerFeedbackService(feedbacks, documents, access,
            JsonMapper.builder().build());

    @Test
    void employeeCanReportTheirCompletedAnswerOnceAndUpdateOpenDescription() {
        when(access.currentUser()).thenReturn(user(7L));
        when(feedbacks.selectAnswerContext(22L)).thenReturn(context(7L, "COMPLETED"));
        when(feedbacks.saveOrUpdateOpen(22L, 7L, "引用的流程不完整")).thenReturn(43L);
        when(feedbacks.selectById(43L)).thenReturn(AnswerFeedbackDO.builder().id(43L).messageId(22L)
                .userId(7L).description("引用的流程不完整").status("OPEN").build());

        var result = service.submit(22L, " 引用的流程不完整 ");

        assertThat(result.id()).isEqualTo(43L);
        assertThat(result.status()).isEqualTo("OPEN");
        verify(access).requireRead(2L);
        verify(feedbacks).saveOrUpdateOpen(22L, 7L, "引用的流程不完整");
    }

    @Test
    void anotherUsersAnswerIsHiddenAndDoesNotCreateFeedback() {
        when(access.currentUser()).thenReturn(user(7L));
        when(feedbacks.selectAnswerContext(22L)).thenReturn(context(8L, "COMPLETED"));

        assertThatThrownBy(() -> service.submit(22L, "有问题"))
                .isInstanceOf(ResponseStatusException.class).extracting("statusCode").isEqualTo(HttpStatus.NOT_FOUND);
        verify(feedbacks, never()).saveOrUpdateOpen(any(), any(), any());
    }

    @Test
    void doesNotAllowFeedbackOnIncompleteAnswerOrEditingResolvedFeedback() {
        when(access.currentUser()).thenReturn(user(7L));
        when(feedbacks.selectAnswerContext(22L)).thenReturn(context(7L, "FAILED"));
        assertThatThrownBy(() -> service.submit(22L, "有问题"))
                .isInstanceOf(ResponseStatusException.class).extracting("statusCode").isEqualTo(HttpStatus.CONFLICT);

        when(feedbacks.selectAnswerContext(22L)).thenReturn(context(7L, "COMPLETED"));
        when(feedbacks.saveOrUpdateOpen(22L, 7L, "有问题")).thenReturn(null);
        assertThatThrownBy(() -> service.submit(22L, "有问题"))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("已处理");
    }

    @Test
    void maintenanceQueueRequiresEditAndContainsOnlyReportedAnswerSnapshot() {
        when(feedbacks.selectMaintenanceQueue(2L)).thenReturn(List.of(FeedbackQueueRow.builder()
                .id(43L).answerId(22L).knowledgeBaseId(2L).description("流程不完整")
                .status("OPEN").sourcesJson("[]").build()));

        List<AnswerFeedbackService.MaintenanceFeedback> queue = service.listForMaintainer(2L);

        assertThat(queue).singleElement().satisfies(item -> {
            assertThat(item.id()).isEqualTo(43L);
            assertThat(item.answerId()).isEqualTo(22L);
            assertThat(item.sources()).isEmpty();
        });
        verify(access).requireEdit(2L);
    }

    private EnterpriseUserDetails user(long id) {
        return new EnterpriseUserDetails(id, "employee", "Employee", "USER", "hash", true, List.of());
    }

    private FeedbackAnswerContext context(long ownerId, String status) {
        FeedbackAnswerContext context = new FeedbackAnswerContext();
        context.setAnswerId(22L);
        context.setOwnerUserId(ownerId);
        context.setKnowledgeBaseId(2L);
        context.setAnswerRole("assistant");
        context.setAnswerStatus(status);
        return context;
    }
}
