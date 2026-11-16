package com.milanai.robot.domain.dos;

import lombok.Data;
import lombok.Builder;

import java.time.LocalDateTime;

@Data
@Builder
public class FeedbackQueueRow {
    private Long id;
    private Long answerId;
    private Long userId;
    private String reporterDisplayName;
    private Long knowledgeBaseId;
    private String userQuestion;
    private String answerContent;
    private String sourcesJson;
    private String description;
    private String status;
    private Long resolvedBy;
    private String resolutionNote;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
