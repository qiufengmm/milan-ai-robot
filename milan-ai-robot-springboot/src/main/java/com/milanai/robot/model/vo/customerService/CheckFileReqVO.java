package com.milanai.robot.model.vo.customerService;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * @description: 文件检查（秒传）
 **/
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class CheckFileReqVO {

    @jakarta.validation.constraints.NotNull
    @jakarta.validation.constraints.Positive
    private Long knowledgeBaseId;

    @NotBlank(message = "文件 MD5 不能为空")
    private String fileMd5;
}
