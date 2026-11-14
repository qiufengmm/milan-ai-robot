package com.milanai.robot.advisor;

import com.milanai.robot.config.RagProperties;
import com.milanai.robot.domain.dos.KnowledgeBaseDO;
import com.milanai.robot.service.HybridRetrievalService;
import com.milanai.robot.service.RerankService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.openai.OpenAiChatOptions;
import reactor.core.publisher.Flux;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class CustomerServiceAdvisorTest {
    @Test
    void usesSelectedLibraryAndRerankOrderWhilePreservingRequestState() {
        var hybrid=mock(HybridRetrievalService.class);
        var rerank=mock(RerankService.class);
        var chain=mock(StreamAdvisorChain.class);
        var properties=new RagProperties();
        properties.getRerank().setTopN(1);
        var kb=KnowledgeBaseDO.builder().id(7L).name("独立课程知识库").build();
        var a=Document.builder().id("a").text("FIRST MATERIAL").metadata(Map.of("knowledge_base_id","7","mdStorageId",10L)).build();
        var b=Document.builder().id("b").text("RERANK WINNER").metadata(Map.of("knowledge_base_id","7","mdStorageId",11L)).build();
        when(hybrid.search("课程资料",10,7L)).thenReturn(List.of(a,b));
        when(rerank.rerank("课程资料",List.of(a,b))).thenReturn(List.of(b,a));
        when(chain.nextStream(any())).thenReturn(Flux.empty());
        var options=OpenAiChatOptions.builder().model("qwen3.8-flash").temperature(0.0).build();
        var request=ChatClientRequest.builder().prompt(new Prompt(List.of(new SystemMessage("Original system"), new UserMessage("课程资料")), options))
                .context(Map.of("trace","preserved")).build();
        new CustomerServiceAdvisor(hybrid,rerank,properties,kb,new ObjectMapper()).adviseStream(request,chain).blockLast();
        var capture=ArgumentCaptor.forClass(ChatClientRequest.class);
        verify(chain).nextStream(capture.capture());
        var next=capture.getValue();
        assertThat(next.context()).containsEntry("trace","preserved");
        assertThat(next.prompt().getOptions()).isEqualTo(options);
        assertThat(next.prompt().getUserMessage().getText()).isEqualTo("课程资料");
        String instructions=next.prompt().getInstructions().toString();
        assertThat(instructions).contains("独立课程知识库","RERANK WINNER","Original system")
                .doesNotContain("FIRST MATERIAL","米兰数码商城","每日 9:00");
    }

    @Test
    void emptyKnowledgeStillUsesExplicitNoInventedChannelsPolicy() {
        var hybrid=mock(HybridRetrievalService.class);
        var rerank=mock(RerankService.class);
        var chain=mock(StreamAdvisorChain.class);
        when(hybrid.search(anyString(),anyInt(),eq(1L))).thenReturn(List.of());
        when(rerank.rerank(anyString(),anyList())).thenReturn(List.of());
        when(chain.nextStream(any())).thenReturn(Flux.empty());
        var kb=KnowledgeBaseDO.builder().id(1L).name("米兰数码商城").build();
        var request=ChatClientRequest.builder().prompt(new Prompt("退款了吗")).build();
        new CustomerServiceAdvisor(hybrid,rerank,new RagProperties(),kb,new ObjectMapper()).adviseStream(request,chain).blockLast();
        var capture=ArgumentCaptor.forClass(ChatClientRequest.class);
        verify(chain).nextStream(capture.capture());
        assertThat(capture.getValue().prompt().getInstructions().toString())
                .contains("米兰数码商城","没有检索到资料","不得声称","资料中明确写出的")
                .doesNotContain("每日 9:00");
    }
}
