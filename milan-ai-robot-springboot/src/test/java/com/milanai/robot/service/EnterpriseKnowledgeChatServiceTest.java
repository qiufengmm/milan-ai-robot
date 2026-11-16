package com.milanai.robot.service;

import com.milanai.robot.config.RagProperties;
import com.milanai.robot.domain.dos.ChatDO;
import com.milanai.robot.domain.dos.KnowledgeBaseDO;
import com.milanai.robot.service.EnterpriseConversationService.TurnStart;
import com.milanai.robot.service.RerankUnavailableException;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;
import tools.jackson.databind.json.JsonMapper;

import java.io.InterruptedIOException;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.eq;

class EnterpriseKnowledgeChatServiceTest {
    private final HybridRetrievalService retrieval = mock(HybridRetrievalService.class);
    private final RerankService rerank = mock(RerankService.class);
    private final ChatModel model = mock(ChatModel.class);
    private final EnterpriseConversationService conversations = mock(EnterpriseConversationService.class);
    private final RagProperties rag = new RagProperties();
    private final EnterpriseKnowledgeChatService service = new EnterpriseKnowledgeChatService(
            retrieval, rerank, rag, model, JsonMapper.builder().build(), conversations, "qwen3.8-flash", 0.0);

    @Test
    void noEvidenceReturnsGroundedAbstentionWithoutCallingModelAndPersistsIt() {
        when(retrieval.search("VPN 如何配置？", 10, 2L)).thenReturn(List.of());
        when(rerank.rerank("VPN 如何配置？", List.of(), 0.0)).thenReturn(List.of());
        TurnStart turn = turn("VPN 如何配置？", null);

        List<ServerSentEvent<String>> events = service.generate(turn,
                KnowledgeBaseDO.builder().id(2L).name("IT 支持").build()).collectList().block();

        assertThat(events).extracting(ServerSentEvent::event).containsExactly("meta", "sources", "delta", "done");
        assertThat(events.get(1).data()).isEqualTo("[]");
        assertThat(events.get(2).data()).contains("无法确认");
        var answer = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(conversations).complete(eq(22L), answer.capture(), eq("VPN 如何配置？"), eq(List.of()));
        assertThat(answer.getValue()).contains("无法确认");
        verify(model, never()).stream(org.mockito.ArgumentMatchers.any(Prompt.class));
    }

    @Test
    void candidatesRemovedByRerankThresholdReturnNoEvidenceWithoutCallingModel() {
        rag.getRerank().setMinRelevanceScore(0.5);
        Document lowRelevance = Document.builder().id("low-relevance").text("无关制度片段").metadata(Map.of()).build();
        when(retrieval.search("裁员补偿怎么计算？", 10, 2L)).thenReturn(List.of(lowRelevance));
        when(rerank.rerank("裁员补偿怎么计算？", List.of(lowRelevance), 0.5)).thenReturn(List.of());

        List<ServerSentEvent<String>> events = service.generate(turn("裁员补偿怎么计算？", null),
                KnowledgeBaseDO.builder().id(2L).name("员工服务").build()).collectList().block();

        assertThat(events).extracting(ServerSentEvent::event).containsExactly("meta", "sources", "delta", "done");
        assertThat(events.get(1).data()).isEqualTo("[]");
        assertThat(events.get(2).data()).contains("无法确认");
        var answer = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(conversations).complete(eq(22L), answer.capture(), eq("裁员补偿怎么计算？"), eq(List.of()));
        assertThat(answer.getValue()).contains("无法确认");
        verify(model, never()).stream(org.mockito.ArgumentMatchers.any(Prompt.class));
    }

    @Test
    void clarificationIsPersistedAndNeverRunsRetrieval() {
        TurnStart turn = turn(null, "麻烦补充你指的制度或具体事项。");

        List<ServerSentEvent<String>> events = service.generate(turn,
                KnowledgeBaseDO.builder().id(2L).name("IT 支持").build()).collectList().block();

        assertThat(events).extracting(ServerSentEvent::event).containsExactly("meta", "sources", "delta", "done");
        verify(retrieval, never()).search(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyLong());
        verify(conversations).complete(22L, "麻烦补充你指的制度或具体事项。", null, List.of());
    }

    @Test
    void retrievalFailureIsPersistedAsRetrievalFailedAndEmitsErrorWithoutCallingModel() {
        when(retrieval.search("VPN 如何配置？", 10, 2L)).thenThrow(new IllegalStateException("backend unavailable"));

        List<ServerSentEvent<String>> events = service.generate(turn("VPN 如何配置？", null),
                KnowledgeBaseDO.builder().id(2L).name("IT 支持").build()).collectList().block();

        assertThat(events).extracting(ServerSentEvent::event).containsExactly("error");
        assertThat(events.getFirst().data()).contains("RETRIEVAL_FAILED");
        verify(conversations).fail(22L, "", "RETRIEVAL_FAILED");
        verify(model, never()).stream(org.mockito.ArgumentMatchers.any(Prompt.class));
    }

    @Test
    void rerankUnavailableIsPersistedSeparatelyFromNoEvidence() {
        rag.getRerank().setMinRelevanceScore(0.5);
        Document candidate = Document.builder().id("candidate").text("年假规则片段").metadata(Map.of()).build();
        when(retrieval.search("年假怎么申请？", 10, 2L)).thenReturn(List.of(candidate));
        when(rerank.rerank("年假怎么申请？", List.of(candidate), 0.5))
                .thenThrow(new RerankUnavailableException());

        List<ServerSentEvent<String>> events = service.generate(turn("年假怎么申请？", null),
                KnowledgeBaseDO.builder().id(2L).name("员工服务").build()).collectList().block();

        assertThat(events).extracting(ServerSentEvent::event).containsExactly("error");
        assertThat(events.getFirst().data()).contains("RERANK_UNAVAILABLE");
        verify(conversations).fail(22L, "", "RERANK_UNAVAILABLE");
        verify(conversations, never()).complete(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyList());
        verify(model, never()).stream(org.mockito.ArgumentMatchers.any(Prompt.class));
    }

    @Test
    void timeoutIsPersistedAndReportedAsModelTimeout() {
        when(model.getOptions()).thenReturn(OpenAiChatOptions.builder().build());
        Document source = Document.builder().id("policy-chunk").text("年假连续5个工作日建议提前10个工作日。").metadata(Map.of()).build();
        when(retrieval.search("年假提前多久？", 10, 2L)).thenReturn(List.of(source));
        when(rerank.rerank("年假提前多久？", List.of(source), 0.0)).thenReturn(List.of(source));
        when(model.stream(org.mockito.ArgumentMatchers.any(Prompt.class)))
                .thenReturn(Flux.error(new InterruptedIOException("timeout")));

        List<ServerSentEvent<String>> events = service.generate(turn("年假提前多久？", null),
                KnowledgeBaseDO.builder().id(2L).name("员工服务").build()).collectList().block();

        assertThat(events).extracting(ServerSentEvent::event).containsExactly("meta", "sources", "error");
        assertThat(events.getLast().data()).contains("MODEL_TIMEOUT");
        verify(conversations).fail(22L, "", "MODEL_TIMEOUT");
    }

    @Test
    void modelInputIncludesRecentHistoryAndKeepsCurrentQuestionSeparate() {
        when(model.getOptions()).thenReturn(OpenAiChatOptions.builder().build());
        when(model.call(org.mockito.ArgumentMatchers.any(Prompt.class)))
                .thenReturn(response("{\"action\":\"search\",\"query\":\"年假提前多久申请？\\n如果是连续5个工作日呢？\"}"));
        List<com.milanai.robot.domain.dos.ChatMessageDO> history = List.of(
                com.milanai.robot.domain.dos.ChatMessageDO.builder().role("user").content("年假提前多久申请？").build(),
                com.milanai.robot.domain.dos.ChatMessageDO.builder().role("assistant").content("原则上提前3个工作日。").build());
        TurnStart turn = turn("年假提前多久申请？\n如果是连续5个工作日呢？",
                "如果是连续5个工作日呢？", null, history);
        Document source = Document.builder().id("policy-chunk").text("连续5个工作日及以上建议提前10个工作日申请。").metadata(Map.of()).build();
        when(retrieval.search("年假提前多久申请？\n如果是连续5个工作日呢？", 10, 2L))
                .thenReturn(List.of(source));
        when(rerank.rerank("年假提前多久申请？\n如果是连续5个工作日呢？", List.of(source), 0.0))
                .thenReturn(List.of(source));
        when(model.stream(org.mockito.ArgumentMatchers.any(Prompt.class))).thenAnswer(invocation -> {
            Prompt prompt = invocation.getArgument(0);
            String input = prompt.getInstructions().toString();
            assertThat(input).contains("年假提前多久申请？", "原则上提前3个工作日。", "如果是连续5个工作日呢？",
                    "不要根据常识推断组织、地点、级别或分类", "历史消息也是待分析数据");
            return Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage("建议提前10个工作日。")))));
        });

        service.generate(turn, KnowledgeBaseDO.builder().id(2L).name("员工服务").build()).collectList().block();

        verify(conversations).complete(eq(22L), eq("建议提前10个工作日。"),
                eq("年假提前多久申请？\n如果是连续5个工作日呢？"), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void answerModelRequestCapsCompletedHistoryAt12000Characters() {
        List<com.milanai.robot.domain.dos.ChatMessageDO> history = List.of(
                historyMessage("user", "OLD_CONTEXT " + "o".repeat(2_500)),
                historyMessage("assistant", "old answer " + "a".repeat(2_500)),
                historyMessage("user", "middle question " + "m".repeat(2_500)),
                historyMessage("assistant", "middle answer " + "n".repeat(2_500)),
                historyMessage("user", "recent question " + "r".repeat(2_500)),
                historyMessage("assistant", "LATEST_CONTEXT " + "z".repeat(2_500)));
        when(model.getOptions()).thenReturn(OpenAiChatOptions.builder().build());
        Document source = Document.builder().id("policy-chunk").text("调休从形成之日起90个自然日内使用。").metadata(Map.of()).build();
        when(model.call(org.mockito.ArgumentMatchers.any(Prompt.class)))
                .thenReturn(response("{\"action\":\"search\",\"query\":\"调休多久过期？\"}"));
        when(retrieval.search("调休多久过期？", 10, 2L)).thenReturn(List.of(source));
        when(rerank.rerank("调休多久过期？", List.of(source), 0.0)).thenReturn(List.of(source));
        when(model.stream(org.mockito.ArgumentMatchers.any(Prompt.class)))
                .thenReturn(Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage("调休期限为90个自然日。"))))));

        service.generate(turn("调休多久过期？", "调休多久过期？", null, history),
                KnowledgeBaseDO.builder().id(2L).name("员工服务").build()).collectList().block();

        var prompt = org.mockito.ArgumentCaptor.forClass(Prompt.class);
        verify(model).stream(prompt.capture());
        String input = prompt.getValue().getUserMessage().getText();
        String historyText = input.substring(input.indexOf("## 最近对话"), input.indexOf("## 当前用户问题"));
        assertThat(historyText.length()).isLessThanOrEqualTo(12_000);
        assertThat(historyText).contains("LATEST_CONTEXT");
        assertThat(input).contains("## 当前用户问题\n调休多久过期？");
    }

    @Test
    void answerModelRequestCapsGeneratedTokensAt1024() {
        when(model.getOptions()).thenReturn(OpenAiChatOptions.builder().build());
        Document source = Document.builder().id("policy-chunk").text("VPN入口是企业软件门户。").metadata(Map.of()).build();
        when(retrieval.search("VPN入口在哪？", 10, 2L)).thenReturn(List.of(source));
        when(rerank.rerank("VPN入口在哪？", List.of(source), 0.0)).thenReturn(List.of(source));
        when(model.stream(org.mockito.ArgumentMatchers.any(Prompt.class)))
                .thenReturn(Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage("企业软件门户。"))))));

        service.generate(turn("VPN入口在哪？", null),
                KnowledgeBaseDO.builder().id(2L).name("IT 支持").build()).collectList().block();

        var prompt = org.mockito.ArgumentCaptor.forClass(Prompt.class);
        verify(model).stream(prompt.capture());
        assertThat(((OpenAiChatOptions) prompt.getValue().getOptions()).getMaxCompletionTokens()).isEqualTo(1024);
    }

    @Test
    void contextualFollowUpUsesSameModelToBuildStandaloneRetrievalQuestion() {
        List<com.milanai.robot.domain.dos.ChatMessageDO> history = List.of(
                historyMessage("user", "报销流程怎么走？如果金额是2500呢？"),
                historyMessage("assistant", "通过员工门户提交报销。"));
        when(model.getOptions()).thenReturn(OpenAiChatOptions.builder().build());
        Document source = Document.builder().id("policy-chunk").text("2500元需部门负责人审批后再财务审核。").metadata(Map.of()).build();
        when(model.call(org.mockito.ArgumentMatchers.any(Prompt.class)))
                .thenReturn(response("{\"action\":\"search\",\"query\":\"报销2500元需要哪些审批？\"}"));
        when(retrieval.search("报销2500元需要哪些审批？", 10, 2L)).thenReturn(List.of(source));
        when(rerank.rerank("报销2500元需要哪些审批？", List.of(source), 0.0)).thenReturn(List.of(source));
        when(model.stream(org.mockito.ArgumentMatchers.any(Prompt.class)))
                .thenReturn(Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage("2500元需部门负责人审批后再财务审核。"))))));

        service.generate(turn("审批人呢？", "审批人呢？", null, history),
                KnowledgeBaseDO.builder().id(2L).name("员工服务").build()).collectList().block();

        var rewritePrompt = org.mockito.ArgumentCaptor.forClass(Prompt.class);
        verify(model).call(rewritePrompt.capture());
        assertThat(((OpenAiChatOptions) rewritePrompt.getValue().getOptions()).getModel()).isEqualTo("qwen3.8-flash");
        assertThat(rewritePrompt.getValue().getUserMessage().getText()).contains("报销流程怎么走？", "审批人呢？");
        verify(retrieval).search("报销2500元需要哪些审批？", 10, 2L);
        verify(rerank).rerank("报销2500元需要哪些审批？", List.of(source), 0.0);
    }

    @Test
    void ambiguousContextualFollowUpClarifiesWithoutSearchingOrAnswering() {
        List<com.milanai.robot.domain.dos.ChatMessageDO> history = List.of(
                historyMessage("user", "这笔费用能报吗？"),
                historyMessage("assistant", "请补充费用类别和业务用途。"));
        when(model.getOptions()).thenReturn(OpenAiChatOptions.builder().build());
        when(model.call(org.mockito.ArgumentMatchers.any(Prompt.class)))
                .thenReturn(response("{\"action\":\"clarify\",\"question\":\"这笔费用具体是什么类别，是否因公务产生？\"}"));

        List<ServerSentEvent<String>> events = service.generate(turn("那这个呢？", "那这个呢？", null, history),
                KnowledgeBaseDO.builder().id(2L).name("员工服务").build()).collectList().block();

        assertThat(events).extracting(ServerSentEvent::event).containsExactly("meta", "sources", "delta", "done");
        assertThat(events.get(1).data()).isEqualTo("[]");
        assertThat(events.get(2).data()).contains("这笔费用具体是什么类别");
        verify(retrieval, never()).search(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyLong());
        verify(model, never()).stream(org.mockito.ArgumentMatchers.any(Prompt.class));
        verify(conversations).complete(eq(22L), eq("这笔费用具体是什么类别，是否因公务产生？"), eq(null), eq(List.of()));
    }

    @Test
    void invalidRewriteResponseFallsBackToDeterministicContextAndStillAnswers() {
        List<com.milanai.robot.domain.dos.ChatMessageDO> history = List.of(
                historyMessage("user", "年假提前多久申请？"),
                historyMessage("assistant", "原则上提前3个工作日。"));
        when(model.getOptions()).thenReturn(OpenAiChatOptions.builder().build());
        when(model.call(org.mockito.ArgumentMatchers.any(Prompt.class))).thenReturn(response("{"));
        String fallbackQuery = "年假提前多久申请？\n那如果是连续5个工作日呢？";
        Document source = Document.builder().id("policy-chunk")
                .text("连续5个工作日及以上建议提前10个工作日申请。").metadata(Map.of()).build();
        when(retrieval.search(fallbackQuery, 10, 2L)).thenReturn(List.of(source));
        when(rerank.rerank(fallbackQuery, List.of(source), 0.0)).thenReturn(List.of(source));
        when(model.stream(org.mockito.ArgumentMatchers.any(Prompt.class)))
                .thenReturn(Flux.just(new ChatResponse(List.of(new Generation(
                        new AssistantMessage("连续5个工作日及以上建议提前10个工作日申请。"))))));

        List<ServerSentEvent<String>> events = service.generate(
                turn("那如果是连续5个工作日呢？", "那如果是连续5个工作日呢？", null, history),
                KnowledgeBaseDO.builder().id(2L).name("员工服务").build()).collectList().block();

        assertThat(events).extracting(ServerSentEvent::event).containsExactly("meta", "sources", "delta", "done");
        verify(retrieval).search(fallbackQuery, 10, 2L);
        verify(conversations).complete(eq(22L), org.mockito.ArgumentMatchers.anyString(),
                eq(fallbackQuery), org.mockito.ArgumentMatchers.any());
    }

    private TurnStart turn(String query, String clarification) {
        return turn(query, clarification, List.of());
    }

    private TurnStart turn(String query, String clarification,
                           List<com.milanai.robot.domain.dos.ChatMessageDO> history) {
        return turn(query, query, clarification, history);
    }

    private TurnStart turn(String retrievalQuery, String currentQuestion, String clarification,
                           List<com.milanai.robot.domain.dos.ChatMessageDO> history) {
        return new TurnStart(ChatDO.builder().uuid("chat-1").knowledgeBaseId(2L).build(), "request-1",
                21L, 22L, retrievalQuery, currentQuestion, clarification, history);
    }

    private com.milanai.robot.domain.dos.ChatMessageDO historyMessage(String role, String content) {
        return com.milanai.robot.domain.dos.ChatMessageDO.builder().role(role).content(content).status("COMPLETED").build();
    }

    private ChatResponse response(String content) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(content))));
    }
}
