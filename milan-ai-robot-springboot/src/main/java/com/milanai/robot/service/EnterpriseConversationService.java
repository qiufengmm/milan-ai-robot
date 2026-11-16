package com.milanai.robot.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.milanai.robot.domain.dos.ChatDO;
import com.milanai.robot.domain.dos.ChatMessageDO;
import com.milanai.robot.domain.dos.EnterpriseUserDetails;
import com.milanai.robot.domain.dos.KnowledgeBaseDO;
import com.milanai.robot.domain.mapper.ChatMapper;
import com.milanai.robot.domain.mapper.ChatMessageMapper;
import com.milanai.robot.model.vo.chat.FindChatHistoryMessagePageListReqVO;
import com.milanai.robot.model.vo.chat.FindChatHistoryMessagePageListRspVO;
import com.milanai.robot.model.vo.chat.FindChatHistoryPageListRspVO;
import com.milanai.robot.model.vo.chat.NewChatRspVO;
import com.milanai.robot.service.QuestionRewriteService.RewriteResult;
import com.milanai.robot.utils.PageResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class EnterpriseConversationService {
    private static final Pattern REQUEST_ID = Pattern.compile("[A-Za-z0-9._:-]{1,60}");
    private static final int MAX_MESSAGE_CHARS = 4_000;
    private static final int MAX_PAGE_SIZE = 50;
    private static final int HISTORY_TURNS = 6;

    private final ChatMapper chats;
    private final ChatMessageMapper messages;
    private final KnowledgeAccessService access;
    private final ObjectMapper objectMapper;
    private final QuestionRewriteService questionRewrite;

    public EnterpriseConversationService(ChatMapper chats, ChatMessageMapper messages,
                                         KnowledgeAccessService access, ObjectMapper objectMapper) {
        this(chats, messages, access, objectMapper, new QuestionRewriteService());
    }

    @org.springframework.beans.factory.annotation.Autowired
    public EnterpriseConversationService(ChatMapper chats, ChatMessageMapper messages,
                                         KnowledgeAccessService access, ObjectMapper objectMapper,
                                         QuestionRewriteService questionRewrite) {
        this.chats = chats;
        this.messages = messages;
        this.access = access;
        this.objectMapper = objectMapper;
        this.questionRewrite = questionRewrite;
    }

    @Transactional
    public NewChatRspVO createChat(Long knowledgeBaseId, String initialMessage) {
        validateMessage(initialMessage);
        access.requireRead(knowledgeBaseId);
        EnterpriseUserDetails user = access.currentUser();
        String uuid = UUID.randomUUID().toString();
        String summary = truncate(initialMessage.trim(), 60);
        LocalDateTime now = LocalDateTime.now();
        chats.insert(ChatDO.builder().uuid(uuid).summary(summary).userId(user.id())
                .knowledgeBaseId(knowledgeBaseId).createTime(now).updateTime(now).build());
        return NewChatRspVO.builder().uuid(uuid).summary(summary).build();
    }

    @Transactional
    public TurnStart beginRequest(String chatUuid, String requestId, String message) {
        if (chatUuid == null || chatUuid.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "对话 ID 不能为空");
        }
        if (requestId == null || !REQUEST_ID.matcher(requestId).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请求 ID 格式无效");
        }
        validateMessage(message);

        EnterpriseUserDetails user = access.currentUser();
        ChatDO chat = chats.lockByUuid(chatUuid);
        if (chat == null || !Objects.equals(chat.getUserId(), user.id())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "对话不存在");
        }
        access.requireRead(chat.getKnowledgeBaseId());

        LambdaQueryWrapper<ChatMessageDO> duplicateRequest = Wrappers.<ChatMessageDO>lambdaQuery()
                .eq(ChatMessageDO::getChatUuid, chatUuid)
                .eq(ChatMessageDO::getRequestId, requestId);
        if (messages.selectCount(duplicateRequest) > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "该请求已提交，请刷新对话后重试");
        }
        LambdaQueryWrapper<ChatMessageDO> generating = Wrappers.<ChatMessageDO>lambdaQuery()
                .eq(ChatMessageDO::getChatUuid, chatUuid)
                .eq(ChatMessageDO::getRole, "assistant")
                .eq(ChatMessageDO::getStatus, "GENERATING");
        if (messages.selectCount(generating) > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "当前对话仍有回答生成中");
        }

        List<ChatMessageDO> history = messages.selectCompletedConversationHistory(chatUuid, HISTORY_TURNS);
        RewriteResult rewrite = questionRewrite.rewrite(message, history);
        LocalDateTime now = LocalDateTime.now();
        ChatMessageDO userMessage = ChatMessageDO.builder().chatUuid(chatUuid).requestId(requestId)
                .role("user").content(message.trim()).status("COMPLETED").createTime(now).build();
        messages.insert(userMessage);

        ChatMessageDO assistantMessage = ChatMessageDO.builder().chatUuid(chatUuid).requestId(requestId)
                .role("assistant").status("GENERATING").content("")
                .retrievalQuery(rewrite.retrievalQuery()).createTime(now).build();
        messages.insert(assistantMessage);

        if (chat.getSummary() == null || chat.getSummary().isBlank()) {
            chat.setSummary(truncate(message.trim(), 60));
        }
        chat.setUpdateTime(now);
        chats.updateById(ChatDO.builder().id(chat.getId()).summary(chat.getSummary()).updateTime(now).build());

        return new TurnStart(chat, requestId, userMessage.getId(), assistantMessage.getId(),
                rewrite.retrievalQuery(), message.trim(),
                rewrite.needsClarification() ? rewrite.clarification() : null, history);
    }

    @Transactional
    public void complete(long assistantMessageId, String answer, String retrievalQuery, Object sources) {
        ChatMessageDO message = requireAssistantMessage(assistantMessageId);
        if (!"GENERATING".equals(message.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "该回答已结束");
        }
        int updated = messages.completeGenerating(assistantMessageId, answer == null ? "" : answer,
                retrievalQuery, writeJson(sources));
        if (updated != 1) throw new ResponseStatusException(HttpStatus.CONFLICT, "该回答已结束");
    }

    @Transactional
    public void fail(long assistantMessageId, String partialAnswer, String errorCode) {
        finish(assistantMessageId, partialAnswer, errorCode, "FAILED");
    }

    @Transactional
    public void cancel(long assistantMessageId, String partialAnswer) {
        finish(assistantMessageId, partialAnswer, "CANCELLED", "CANCELLED");
    }

    public PageResponse<FindChatHistoryPageListRspVO> listChats(long current, long size) {
        EnterpriseUserDetails user = access.currentUser();
        List<Long> allowedIds = access.listAccessibleKnowledgeBases().stream().map(KnowledgeBaseDO::getId).toList();
        Page<ChatDO> page = new Page<>(validPage(current), validSize(size));
        LambdaQueryWrapper<ChatDO> query = Wrappers.<ChatDO>lambdaQuery()
                .eq(ChatDO::getUserId, user.id())
                .in(!allowedIds.isEmpty(), ChatDO::getKnowledgeBaseId, allowedIds)
                .eq(allowedIds.isEmpty(), ChatDO::getKnowledgeBaseId, -1L)
                .orderByDesc(ChatDO::getUpdateTime).orderByDesc(ChatDO::getId);
        Page<ChatDO> result = chats.selectPage(page, query);
        List<FindChatHistoryPageListRspVO> data = result.getRecords().stream()
                .map(chat -> FindChatHistoryPageListRspVO.builder().id(chat.getId()).uuid(chat.getUuid())
                        .summary(chat.getSummary()).knowledgeBaseId(chat.getKnowledgeBaseId())
                        .updateTime(chat.getUpdateTime()).build()).toList();
        return PageResponse.success(result, data);
    }

    public PageResponse<FindChatHistoryMessagePageListRspVO> listMessages(
            FindChatHistoryMessagePageListReqVO request) {
        ChatDO chat = requireOwnedChat(request.getChatId());
        access.requireRead(chat.getKnowledgeBaseId());
        Page<ChatMessageDO> page = new Page<>(validPage(request.getCurrent()), validSize(request.getSize()));
        Page<ChatMessageDO> result = messages.selectPage(page, Wrappers.<ChatMessageDO>lambdaQuery()
                .eq(ChatMessageDO::getChatUuid, chat.getUuid())
                .orderByDesc(ChatMessageDO::getId));
        List<FindChatHistoryMessagePageListRspVO> data = result.getRecords().stream()
                .filter(message -> "COMPLETED".equals(message.getStatus()) || "FAILED".equals(message.getStatus())
                        || "CANCELLED".equals(message.getStatus()) || "GENERATING".equals(message.getStatus()))
                .map(message -> FindChatHistoryMessagePageListRspVO.builder().id(message.getId())
                        .chatId(message.getChatUuid()).content(message.getContent()).role(message.getRole())
                        .createTime(message.getCreateTime()).status(message.getStatus())
                        .errorCode(message.getErrorCode()).build())
                .sorted((left, right) -> left.getId().compareTo(right.getId())).toList();
        return PageResponse.success(result, data);
    }

    @Transactional
    public void renameChat(String uuid, String summary) {
        if (summary == null || summary.isBlank() || summary.trim().length() > 60) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "标题长度须为1至60个字符");
        }
        ChatDO chat = requireOwnedChat(uuid);
        access.requireRead(chat.getKnowledgeBaseId());
        chats.updateById(ChatDO.builder().id(chat.getId()).summary(summary.trim())
                .updateTime(LocalDateTime.now()).build());
    }

    @Transactional
    public void deleteChat(String uuid) {
        ChatDO chat = requireOwnedChat(uuid);
        access.requireRead(chat.getKnowledgeBaseId());
        if (messages.countFeedbackByChatUuid(uuid) > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "此会话包含反馈记录，为保留处理结果暂不允许删除");
        }
        messages.delete(Wrappers.<ChatMessageDO>lambdaQuery().eq(ChatMessageDO::getChatUuid, uuid));
        chats.deleteById(chat.getId());
    }

    public ChatDO requireOwnedChat(String uuid) {
        EnterpriseUserDetails user = access.currentUser();
        ChatDO chat = chats.selectOne(Wrappers.<ChatDO>lambdaQuery()
                .eq(ChatDO::getUuid, uuid).eq(ChatDO::getUserId, user.id()));
        if (chat == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "对话不存在");
        }
        return chat;
    }

    public ConversationDetail getChat(String uuid) {
        ChatDO chat = requireOwnedChat(uuid);
        access.requireRead(chat.getKnowledgeBaseId());
        return new ConversationDetail(chat.getUuid(), chat.getSummary(), chat.getKnowledgeBaseId());
    }

    private ChatMessageDO requireAssistantMessage(long id) {
        ChatMessageDO message = messages.selectById(id);
        if (message == null || !"assistant".equals(message.getRole())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "回答不存在");
        }
        return message;
    }

    private void finish(long id, String answer, String errorCode, String status) {
        int updated = messages.finishGenerating(id, answer == null ? "" : answer, status, errorCode);
        if (updated == 0) return;
        if (updated != 1) throw new IllegalStateException("多个回答状态被同时更新");
    }

    private String writeJson(Object value) {
        if (value == null) return "[]";
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalArgumentException("无法保存回答来源", exception);
        }
    }

    private static void validateMessage(String message) {
        if (message == null || message.isBlank() || message.trim().length() > MAX_MESSAGE_CHARS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "问题不能为空且不能超过4000个字符");
        }
    }

    private static long validPage(long current) { return Math.max(1, current); }
    private static long validSize(long size) { return Math.min(MAX_PAGE_SIZE, Math.max(1, size)); }

    private static String truncate(String value, int length) {
        return value.length() <= length ? value : value.substring(0, length);
    }

    public record TurnStart(ChatDO chat, String requestId, Long userMessageId, Long assistantMessageId,
                            String retrievalQuery, String currentQuestion, String clarification,
                            List<ChatMessageDO> completedHistory) {
        public boolean needsClarification() { return clarification != null; }
    }

    public record ConversationDetail(String uuid, String summary, Long knowledgeBaseId) {}
}
