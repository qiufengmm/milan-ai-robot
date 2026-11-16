package com.milanai.robot.model.vo.chat;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class EnterpriseRenameChatReqVO {
    @NotBlank
    private String uuid;

    @NotBlank
    @Size(max = 60)
    private String summary;
}
