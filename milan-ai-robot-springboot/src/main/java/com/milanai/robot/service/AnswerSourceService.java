package com.milanai.robot.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.milanai.robot.domain.dos.ChatDO;
import com.milanai.robot.domain.dos.ChatMessageDO;
import com.milanai.robot.domain.dos.EnterpriseUserDetails;
import com.milanai.robot.domain.mapper.ChatMapper;
import com.milanai.robot.domain.mapper.ChatMessageMapper;
import com.milanai.robot.domain.mapper.KnowledgeDocumentMapper;
import com.milanai.robot.service.EnterpriseKnowledgeChatService.SourceCard;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

@Service
public class AnswerSourceService {
    private static final TypeReference<List<SourceCard>> SOURCE_LIST = new TypeReference<>() {};

    private final ChatMapper chats;
    private final ChatMessageMapper messages;
    private final KnowledgeAccessService access;
    private final KnowledgeDocumentMapper documents;
    private final ObjectMapper json;

    public AnswerSourceService(ChatMapper chats, ChatMessageMapper messages,
                               KnowledgeAccessService access, KnowledgeDocumentMapper documents, ObjectMapper json) {
        this.chats = chats;
        this.messages = messages;
        this.access = access;
        this.documents = documents;
        this.json = json;
    }

    public List<SourceCard> getSources(Long answerId) {
        ChatMessageDO answer = messages.selectById(answerId);
        if (answer == null || !"assistant".equals(answer.getRole()) || !"COMPLETED".equals(answer.getStatus())) {
            throw notFound();
        }
        EnterpriseUserDetails user = access.currentUser();
        ChatDO chat = chats.selectOne(Wrappers.<ChatDO>lambdaQuery()
                .eq(ChatDO::getUuid, answer.getChatUuid()).eq(ChatDO::getUserId, user.id()));
        if (chat == null || !java.util.Objects.equals(chat.getUserId(), user.id())) throw notFound();
        access.requireRead(chat.getKnowledgeBaseId());
        if (answer.getSourcesJson() == null || answer.getSourcesJson().isBlank()) return List.of();
        try {
            List<SourceCard> sources = json.readValue(answer.getSourcesJson(), SOURCE_LIST);
            if (sources == null) return List.of();
            return sources.stream().map(source -> {
                Long documentId = parseId(source.documentId());
                Long fileId = parseId(source.fileId());
                var document = documentId == null ? null : documents.selectById(documentId);
                boolean historical = document == null || fileId == null
                        || !java.util.Objects.equals(document.getCurrentFileId(), fileId);
                return source.withHistorical(historical);
            }).toList();
        } catch (Exception exception) {
            throw new IllegalStateException("保存的回答来源格式无效", exception);
        }
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "回答来源不存在");
    }

    private Long parseId(String value) {
        try { return value == null ? null : Long.valueOf(value); }
        catch (NumberFormatException exception) { return null; }
    }
}
