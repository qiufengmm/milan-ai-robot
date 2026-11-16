package com.milanai.robot.model.vo.customerService;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class EnterpriseChatCompletionReqVO {
    @NotBlank
    @Size(max = 36)
    private String chatId;

    @NotBlank
    @Size(max = 60)
    private String requestId;

    @NotBlank
    @Size(max = 4000)
    private String message;
}
