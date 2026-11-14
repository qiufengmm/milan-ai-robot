package com.milanai.robot.model.vo.customerService;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * @description: 文件检查（秒传）
 **/
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class CheckFileRspVO {
    private Long fileId;
    private Integer status;
    private String indexStatus;

    /**
     * 服务端是否已有该 MD5 的文件记录
     */
    private Boolean exists;

    /**
     * 是否还需要上传分片。
     * false 且 exists 为 true → 秒传成功，前端直接跳过整个上传流程
     */
    private Boolean needUpload;

    /**
     * 已上传成功的分片序号集合，断点续传时前端跳过这些序号
     */
    private List<Integer> uploadedChunks;
}
