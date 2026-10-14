package com.milanai.robot.controller;

import com.google.common.collect.Lists;
import com.milanai.robot.advisor.CustomChatMemoryAdvisor;
import com.milanai.robot.advisor.CustomStreamLoggerAndMessage2DBAdvisor;
import com.milanai.robot.advisor.NetworkSearchAdvisor;
import com.milanai.robot.aspect.ApiOperationLog;
import com.milanai.robot.domain.mapper.ChatMessageMapper;
import com.milanai.robot.model.vo.chat.*;
import com.milanai.robot.service.ChatService;
import com.milanai.robot.service.SearXNGService;
import com.milanai.robot.service.SearchResultContentFetcherService;
import com.milanai.robot.utils.PageResponse;
import com.milanai.robot.utils.Response;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.http.MediaType;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.util.List;


/**
 * @Description: 对话
 **/
@RestController
@RequestMapping("/chat")
@Slf4j
public class ChatController {

    @Resource
    private ChatService chatService;
    @Resource
    private OpenAiChatModel openAiChatModel;
    @Resource
    private ChatMessageMapper chatMessageMapper;
    @Resource
    private TransactionTemplate transactionTemplate;
    @Resource
    private SearXNGService searXNGService;
    @Resource
    private SearchResultContentFetcherService searchResultContentFetcherService;

    @PostMapping("/new")
    @ApiOperationLog(description = "新建对话")
    public Response<?> newChat(@RequestBody @Validated NewChatReqVO newChatReqVO) {
        return chatService.newChat(newChatReqVO);
    }

    /**
     * 流式对话
     * @return
     */
    @PostMapping(value = "/completion", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @ApiOperationLog(description = "流式对话")
    public Flux<AIResponse> chat(@RequestBody @Validated AiChatReqVO aiChatReqVO) {
        // 用户消息
        String userMessage = aiChatReqVO.getMessage();
        // 模型名称
        String modelName = aiChatReqVO.getModelName();
        // 温度值
        Double temperature = aiChatReqVO.getTemperature();
        // 是否开启联网搜索
        boolean networkSearch = Boolean.TRUE.equals(aiChatReqVO.getNetworkSearch());


        // 动态设置调用的模型名称、温度值
        ChatClient.ChatClientRequestSpec chatClientRequestSpec = ChatClient.create(openAiChatModel)
                .prompt()
                .options(OpenAiChatOptions.builder()
                        .model(modelName) // 模型名称
                        .temperature(temperature)) // 温度值
                .user(userMessage); // 用户提示词
        // Advisor 集合
        List<Advisor> advisors = Lists.newArrayList();
        // 是否开启了联网搜索
        if (networkSearch) {
            advisors.add(new NetworkSearchAdvisor(searXNGService, searchResultContentFetcherService));
        } else {
            // 添加自定义对话记忆 Advisor（以最新的 50 条消息作为记忆）
            advisors.add(new CustomChatMemoryAdvisor(chatMessageMapper, aiChatReqVO, 50));
        }

        // 添加自定义打印流式对话日志 Advisor
        advisors.add(new CustomStreamLoggerAndMessage2DBAdvisor(chatMessageMapper, aiChatReqVO, transactionTemplate));


        // 应用 Advisor 集合
        chatClientRequestSpec.advisors(advisors);

        int[] reasoningLen = {0};
        return chatClientRequestSpec
                .stream()
                .chatResponse()
                .mapNotNull(chatResponse -> { // 构建返参 AIResponse
                    // 若 chunk 不包含对话内容，直接跳过
                    if (chatResponse.getResult() == null) {
                        return null;
                    }

                    // 获取 AI 回复的消息
                    AssistantMessage message = chatResponse.getResult().getOutput();

                    // 获取正式回答
                    String text = message.getText();

                    // 获取推理内容（累积值）。判空：非推理模型或换 provider 时 metadata 里可能没这个 key
                    Object reasoningObj = message.getMetadata().get("reasoningContent");
                    String reasoningContent = reasoningObj == null ? "" : reasoningObj.toString();

                    // 若推理内容有新增，则下发本帧增量（思考过程）
                    if (reasoningContent.length() > reasoningLen[0]) {
                        String delta = reasoningContent.substring(reasoningLen[0]);
                        reasoningLen[0] = reasoningContent.length();
                        return AIResponse.builder().reasoning(delta).build();
                    }

                    // 推理内容无新增（正式回答阶段），下发正式回答
                    return AIResponse.builder().v(text).build();
                });

    }
    @PostMapping("/message/list")
    @ApiOperationLog(description = "查询对话历史消息")
    public PageResponse<FindChatHistoryMessagePageListRspVO> findChatMessagePageList(@RequestBody @Validated FindChatHistoryMessagePageListReqVO findChatHistoryMessagePageListReqVO) {
        return chatService.findChatHistoryMessagePageList(findChatHistoryMessagePageListReqVO);
    }

    @PostMapping("/list")
    @ApiOperationLog(description = "查询历史对话")
    public PageResponse<FindChatHistoryPageListRspVO> findChatHistoryPageList(@RequestBody @Validated FindChatHistoryPageListReqVO findChatHistoryPageListReqVO) {
        return chatService.findChatHistoryPageList(findChatHistoryPageListReqVO);
    }

    @PostMapping("/summary/rename")
    @ApiOperationLog(description = "重命名对话摘要")
    public Response<?> renameChatSummary(@RequestBody @Validated RenameChatReqVO renameChatReqVO) {
        return chatService.renameChatSummary(renameChatReqVO);
    }
    @PostMapping("/delete")
    @ApiOperationLog(description = "删除对话")
    public Response<?> deleteChat(@RequestBody @Validated DeleteChatReqVO deleteChatReqVO) {
        return chatService.deleteChat(deleteChatReqVO);
    }
}

