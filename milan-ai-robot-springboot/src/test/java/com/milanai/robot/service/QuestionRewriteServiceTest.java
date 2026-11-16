package com.milanai.robot.service;

import com.milanai.robot.domain.dos.ChatMessageDO;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class QuestionRewriteServiceTest {
    private final QuestionRewriteService service = new QuestionRewriteService();

    @Test
    void explicitQuestionUsesCurrentTextWithoutPriorConversation() {
        var result = service.rewrite("VPN 安装需要管理员权限吗？", List.of());

        assertThat(result.retrievalQuery()).isEqualTo("VPN 安装需要管理员权限吗？");
        assertThat(result.needsClarification()).isFalse();
    }

    @Test
    void completeQuestionBeginningWithDemonstrativeStillUsesKnowledgeRetrieval() {
        var result = service.rewrite("这份演示制度里的调休多久过期？", List.of());

        assertThat(result.retrievalQuery()).isEqualTo("这份演示制度里的调休多久过期？");
        assertThat(result.needsClarification()).isFalse();
    }

    @Test
    void conditionalFollowUpWithoutRepeatedTopicUsesPreviousQuestion() {
        List<ChatMessageDO> history = List.of(
                message("user", "年假提前多久申请？"),
                message("assistant", "年假原则上提前3个工作日申请。"));

        var result = service.rewrite("如果是连续5个工作日呢？", history);

        assertThat(result.retrievalQuery()).contains("年假提前多久申请？", "如果是连续5个工作日呢？");
        assertThat(result.needsClarification()).isFalse();
    }

    @Test
    void followUpCombinesPriorUserQuestionButNeverUsesPriorAssistantAsEvidence() {
        List<ChatMessageDO> history = List.of(
                message("user", "员工出差住宿标准是什么？"),
                message("assistant", "历史回答中写了 800 元。"));

        var result = service.rewrite("那超出标准呢？", history);

        assertThat(result.retrievalQuery()).contains("员工出差住宿标准是什么？", "那超出标准呢？")
                .doesNotContain("800 元");
        assertThat(result.needsClarification()).isFalse();
    }

    @Test
    void ambiguousQuestionWithoutCompletedContextRequestsClarification() {
        var result = service.rewrite("那这个呢？", List.of());

        assertThat(result.needsClarification()).isTrue();
        assertThat(result.clarification()).isNotBlank();
        assertThat(result.retrievalQuery()).isNull();
    }

    @Test
    void vagueExpenseEligibilityAsksForItemAndBusinessPurpose() {
        var result = service.rewrite("这个能报吗？", List.of());

        assertThat(result.needsClarification()).isTrue();
        assertThat(result.clarification()).contains("具体是什么费用", "公务");
    }

    @Test
    void vagueExpenseFollowUpUsesExistingConversationContext() {
        List<ChatMessageDO> history = List.of(message("user", "客户现场打车费用怎么报？"));

        var result = service.rewrite("这个能报吗？", history);

        assertThat(result.needsClarification()).isFalse();
        assertThat(result.retrievalQuery()).contains("客户现场打车费用怎么报？", "这个能报吗？");
    }

    @Test
    void expenseAmountFollowUpAddsApprovalSearchTermsWithoutInventingThresholds() {
        List<ChatMessageDO> history = List.of(message("user", "报销流程怎么走？"));

        var result = service.rewrite("那如果金额是2500呢？", history);

        assertThat(result.needsClarification()).isFalse();
        assertThat(result.retrievalQuery()).contains("报销流程怎么走？", "那如果金额是2500呢？",
                "单笔", "金额审批", "直属主管", "部门负责人", "财务合规审核")
                .doesNotContain("2000");
    }

    @Test
    void vagueExpenseStandardAsksWhichKindOfStandard() {
        var result = service.rewrite("费用这个标准是多少？", List.of());

        assertThat(result.needsClarification()).isTrue();
        assertThat(result.clarification()).contains("住宿", "交通", "报销审批");
    }

    @Test
    void genericLoginFailureAsksWhichSystemBeforeListingTroubleshooting() {
        var result = service.rewrite("登录不了，帮我看看。", List.of());

        assertThat(result.needsClarification()).isTrue();
        assertThat(result.retrievalQuery()).isNull();
        assertThat(result.clarification()).contains("哪个系统", "错误提示");
    }

    private ChatMessageDO message(String role, String content) {
        return ChatMessageDO.builder().role(role).content(content).status("COMPLETED").build();
    }
}
