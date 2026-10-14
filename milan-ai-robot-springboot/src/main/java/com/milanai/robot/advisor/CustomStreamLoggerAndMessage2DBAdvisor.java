package com.milanai.robot.advisor;

import com.milanai.robot.domain.dos.ChatMessageDO;
import com.milanai.robot.domain.mapper.ChatMessageMapper;
import com.milanai.robot.model.vo.chat.AiChatReqVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.core.publisher.Flux;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.apache.commons.lang3.StringUtils;


import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicReference;

/**
 * @Version: v1.0.0
 * @Description: 自定义打印流式日志 Advisor
 **/
@Slf4j
public class CustomStreamLoggerAndMessage2DBAdvisor implements StreamAdvisor {

    private final ChatMessageMapper chatMessageMapper;
    private final AiChatReqVO aiChatReqVO;
    private final TransactionTemplate transactionTemplate;

    public CustomStreamLoggerAndMessage2DBAdvisor(ChatMessageMapper chatMessageMapper,
                                                  AiChatReqVO aiChatReqVO,
                                                  TransactionTemplate transactionTemplate) {
        this.chatMessageMapper = chatMessageMapper;
        this.aiChatReqVO = aiChatReqVO;
        this.transactionTemplate = transactionTemplate;
    }
    @Override
    public int getOrder() {
        return 99; // order 值越小，越先执行
    }

    @Override
    public String getName() {
        return this.getClass().getSimpleName();
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest chatClientRequest, StreamAdvisorChain streamAdvisorChain) {
        // 对话 UUID
        String chatUuid = aiChatReqVO.getChatId();
        // 用户消息
        String userMessage = aiChatReqVO.getMessage();
        Flux<ChatClientResponse> chatClientResponseFlux = streamAdvisorChain.nextStream(chatClientRequest);

        // 创建 AI 流式推理过程容器（线程安全）
        AtomicReference<String> fullReasoning = new AtomicReference<>("");
        // 记录上一帧已打印的推理长度，用于打印本帧增量
        int[] printedReasoningLen = {0};
        // 创建 AI 流式回答聚合容器（线程安全）
        AtomicReference<StringBuilder> fullContent = new AtomicReference<>(new StringBuilder());

        // 返回处理后的流
        return chatClientResponseFlux
                .doOnNext(response -> {
                    // getResult() 为 null 时，直接跳过
                    if (response.chatResponse() == null || response.chatResponse().getResult() == null) {
                        return;
                    }

                    // 获取 AI 回复的消息
                    AssistantMessage message = response.chatResponse().getResult().getOutput();

                    // ===== ① 先处理推理内容 =====
                    // 必须放在下面「空 chunk 跳过」的 return 之前！
                    // 推理帧的 getText() 就是空串，放后面会被直接 return 掉，终端永远打不出来
                    Object reasoningObj = message.getMetadata().get("reasoningContent");
                    String reasoningChunk = reasoningObj == null ? "" : reasoningObj.toString();
                    if (StringUtils.isNotBlank(reasoningChunk)) {
                        // 仅打印本帧新增的增量部分
                        if (reasoningChunk.length() > printedReasoningLen[0]) {
                            log.info("## reasoning chunk: {}", reasoningChunk.substring(printedReasoningLen[0]));
                            printedReasoningLen[0] = reasoningChunk.length();
                        }
                        // 累积值直接覆盖，最终保留完整推理
                        fullReasoning.set(reasoningChunk);
                    }

                    // ===== ② 再处理正文 =====
                    String chunk = message.getText();

                    // 推理模型思考阶段的 content 是空串，跳过，别刷日志
                    if (chunk == null || chunk.isEmpty()) {
                        return;
                    }

                    log.info("## chunk: {}", chunk);
                    fullContent.get().append(chunk);
                })

                .doOnComplete(() -> {
                // 流完成后打印完整推理过程
                String completeReasoning = fullReasoning.get();
                log.info("\n==== FULL Reasoning RESPONSE ====\n{}\n========================", completeReasoning);

                // 流完成后打印完整回答      ← 这两行是原有的，别删
                String completeResponse = fullContent.get().toString();
                log.info("\n==== FULL AI RESPONSE ====\n{}\n========================", completeResponse);

                // 开启编程式事务
                    transactionTemplate.execute(status -> {
                        try {
                            // 1. 存储用户消息
                            chatMessageMapper.insert(ChatMessageDO.builder()
                                    .chatUuid(chatUuid)
                                    .content(userMessage)
                                    .role(MessageType.USER.getValue()) // 用户消息
                                    .createTime(LocalDateTime.now())
                                    .build());


                            // 2. 存储 AI 回答
                            chatMessageMapper.insert(ChatMessageDO.builder()
                                    .chatUuid(chatUuid)
                                    .content(completeResponse)
                                    .reasoningContent(completeReasoning)   // ← 加这一行
                                    .role(MessageType.ASSISTANT.getValue())
                                    .createTime(LocalDateTime.now())
                                    .build());

                            return true;
                        } catch (Exception ex) {
                            status.setRollbackOnly(); // 标记事务为回滚
                            log.error("", ex);
                        }
                        return false;
                    });
                })

                .doOnError(error -> {
                    // 出错时打印已收集的部分
                    String partialResponse = fullContent.get().toString();
                    log.error("## Stream 流出现错误，已收集回答如下: {}", partialResponse, error);
                });
    }
}

