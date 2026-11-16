package com.milanai.robot.model.vo.customerService;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;


/**
 * @description: 查询问答文件列表
 **/
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class FindMarkdownFilePageListRspVO {
    private Long knowledgeBaseId;
    private Long documentId;
    private Integer versionNo;
    private String publishStatus;
    private String indexStatus;
    /**
     * 文件 ID
     */
    private Long id;

    /**
     * 文件原始名称
     */
    private String fileName;

    /**
     * 文件大小
     */
    private String fileSize;

    /**
     * 处理状态：0-上传中 1-待处理 2-向量化中 3-已完成 4-失败
     */
    private Integer status;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    private LocalDateTime updateTime;

    /**
     * 备注
     */
    private String remark;

}
