package com.milanai.robot.model.vo.customerService;


import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;


/**
 * @description: 删除 Markdown 问答文件
 **/
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class DeleteMarkdownFileReqVO {

    @NotNull(message = "问答文件 ID 不能为空")
    private Long id;

    /**
     * 是否强制删除。
     * <p>
     * 默认 false：状态为「待处理 / 向量化中」的记录不允许删除，防止误删正在处理的数据；
     * 传 true 时跳过该限制，用于清理因进程中断而永久卡在「向量化中」的僵死记录。
     */
    private Boolean force = false;

}

