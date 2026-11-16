package com.milanai.robot.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.milanai.robot.domain.dos.AnswerFeedbackDO;
import com.milanai.robot.domain.dos.EnterpriseUserDetails;
import com.milanai.robot.domain.dos.FeedbackAnswerContext;
import com.milanai.robot.domain.dos.FeedbackQueueRow;
import com.milanai.robot.domain.mapper.AnswerFeedbackMapper;
import com.milanai.robot.domain.mapper.KnowledgeDocumentMapper;
import com.milanai.robot.service.EnterpriseKnowledgeChatService.SourceCard;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

@Service
public class AnswerFeedbackService {
    private static final TypeReference<List<SourceCard>> SOURCE_LIST = new TypeReference<>() {};

    private final AnswerFeedbackMapper feedbacks;
    private final KnowledgeDocumentMapper documents;
    private final KnowledgeAccessService access;
    private final ObjectMapper json;

    public AnswerFeedbackService(AnswerFeedbackMapper feedbacks, KnowledgeDocumentMapper documents,
                                 KnowledgeAccessService access, ObjectMapper json) {
        this.feedbacks = feedbacks;
        this.documents = documents;
        this.access = access;
        this.json = json;
    }

    @Transactional
    public EmployeeFeedback submit(Long answerId, String description) {
        String cleanDescription = description == null ? "" : description.trim();
        if (cleanDescription.isBlank() || cleanDescription.length() > 500) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "反馈说明须为1至500个字符");
        }
        EnterpriseUserDetails user = access.currentUser();
        FeedbackAnswerContext context = feedbacks.selectAnswerContext(answerId);
        if (context == null || !Objects.equals(context.getOwnerUserId(), user.id())) throw notFound();
        if (!"assistant".equals(context.getAnswerRole()) || !"COMPLETED".equals(context.getAnswerStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "只能反馈已完成的回答");
        }
        access.requireRead(context.getKnowledgeBaseId());
        Long feedbackId = feedbacks.saveOrUpdateOpen(answerId, user.id(), cleanDescription);
        if (feedbackId == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "该反馈已处理，不能继续修改");
        }
        AnswerFeedbackDO row = feedbacks.selectById(feedbackId);
        return employeeView(row);
    }

    public List<EmployeeFeedback> listMine() {
        EnterpriseUserDetails user = access.currentUser();
        return feedbacks.selectList(Wrappers.<AnswerFeedbackDO>lambdaQuery()
                        .eq(AnswerFeedbackDO::getUserId, user.id())
                        .orderByDesc(AnswerFeedbackDO::getUpdateTime).orderByDesc(AnswerFeedbackDO::getId))
                .stream().map(this::employeeView).toList();
    }

    public List<MaintenanceFeedback> listForMaintainer(Long knowledgeBaseId) {
        access.requireEdit(knowledgeBaseId);
        return feedbacks.selectMaintenanceQueue(knowledgeBaseId).stream()
                .map(this::maintenanceView).toList();
    }

    @Transactional
    public void resolve(Long feedbackId, String resolutionNote) {
        String cleanNote = resolutionNote == null ? "" : resolutionNote.trim();
        if (cleanNote.isBlank() || cleanNote.length() > 500) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "处理说明须为1至500个字符");
        }
        AnswerFeedbackDO feedback = feedbacks.lockById(feedbackId);
        if (feedback == null) throw notFound();
        FeedbackAnswerContext context = feedbacks.selectAnswerContext(feedback.getMessageId());
        if (context == null) throw notFound();
        access.requireEdit(context.getKnowledgeBaseId());
        if (!"OPEN".equals(feedback.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "该反馈已处理");
        }
        feedback.setStatus("RESOLVED");
        feedback.setResolvedBy(access.currentUser().id());
        feedback.setResolutionNote(cleanNote);
        feedback.setUpdateTime(LocalDateTime.now());
        feedbacks.updateById(feedback);
    }

    private MaintenanceFeedback maintenanceView(FeedbackQueueRow row) {
        List<SourceCard> sources = parseSources(row.getSourcesJson()).stream()
                .map(this::withCurrentStatus).toList();
        return new MaintenanceFeedback(row.getId(), row.getAnswerId(), row.getUserId(), row.getReporterDisplayName(),
                row.getKnowledgeBaseId(), row.getUserQuestion(), row.getAnswerContent(), sources, row.getDescription(),
                row.getStatus(), row.getResolutionNote(), row.getCreateTime(), row.getUpdateTime());
    }

    private SourceCard withCurrentStatus(SourceCard source) {
        Long documentId = parseId(source.documentId());
        Long fileId = parseId(source.fileId());
        var document = documentId == null ? null : documents.selectById(documentId);
        boolean historical = document == null || fileId == null
                || !Objects.equals(document.getCurrentFileId(), fileId);
        return source.withHistorical(historical);
    }

    private List<SourceCard> parseSources(String sourceJson) {
        if (sourceJson == null || sourceJson.isBlank()) return List.of();
        try {
            List<SourceCard> values = json.readValue(sourceJson, SOURCE_LIST);
            return values == null ? List.of() : values;
        } catch (Exception exception) {
            throw new IllegalStateException("保存的回答来源格式无效", exception);
        }
    }

    private Long parseId(String value) {
        try { return value == null ? null : Long.valueOf(value); }
        catch (NumberFormatException exception) { return null; }
    }

    private EmployeeFeedback employeeView(AnswerFeedbackDO row) {
        return new EmployeeFeedback(row.getId(), row.getMessageId(), row.getDescription(), row.getStatus(),
                row.getResolutionNote(), row.getCreateTime(), row.getUpdateTime());
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "反馈记录不存在");
    }

    public record EmployeeFeedback(Long id, Long answerId, String description, String status,
                                   String resolutionNote, LocalDateTime createTime, LocalDateTime updateTime) {}
    public record MaintenanceFeedback(Long id, Long answerId, Long userId, String reporterDisplayName,
                                      Long knowledgeBaseId, String userQuestion, String answerContent,
                                      List<SourceCard> sources, String description, String status,
                                      String resolutionNote, LocalDateTime createTime, LocalDateTime updateTime) {}
}
