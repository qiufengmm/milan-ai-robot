package com.milanai.robot.domain.dos;

import lombok.Data;

@Data
public class FeedbackAnswerContext {
    private Long answerId;
    private String chatUuid;
    private Long ownerUserId;
    private Long knowledgeBaseId;
    private String answerRole;
    private String answerStatus;
    private String answerContent;
    private String sourcesJson;
    private String userQuestion;
}
