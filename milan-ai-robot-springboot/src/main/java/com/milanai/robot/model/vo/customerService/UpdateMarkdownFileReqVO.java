package com.milanai.robot.model.vo.customerService;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;


/**
 * @description: 修改 Markdown 问答文件
 **/
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class UpdateMarkdownFileReqVO {

    @NotNull(message = "问答文件 ID 不能为空")
    private Long id;

    /**
     * 备注信息，允许为空。
     * 传空串（或 null）即可清空原有备注，不再限制必须填写。
     */
    @Size(max = 500, message = "备注信息长度不能超过 500 个字符")
    private String remark;

}
