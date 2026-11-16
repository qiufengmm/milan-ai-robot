package com.milanai.robot.model.vo.chat;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class EnterpriseNewChatReqVO {
    @NotNull
    @Positive
    private Long knowledgeBaseId;

    @NotBlank
    @Size(max = 4000)
    private String message;
}
