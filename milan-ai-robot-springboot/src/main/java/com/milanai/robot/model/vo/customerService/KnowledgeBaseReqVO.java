package com.milanai.robot.model.vo.customerService;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class KnowledgeBaseReqVO {
    @NotBlank @Size(max = 100)
    private String name;
    @Size(max = 500)
    private String description;
}
