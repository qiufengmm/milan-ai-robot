package com.milanai.robot.model.vo.customerService;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.web.multipart.MultipartFile;

/**
 * @description: 文件分片上传
 **/
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class UploadChunkReqVO {

    @jakarta.validation.constraints.NotNull
    @jakarta.validation.constraints.Positive
    private Long knowledgeBaseId;

    /** Existing logical document to replace; absent means a new logical document. */
    private Long documentId;

    /**
     * 文件 MD5
     */
    private String fileMd5;

    /**
     * 原始文件名称
     */
    private String fileName;

    /**
     * 原始文件大小（字节），是整份大文件的大小，不是分片大小
     */
    private Long fileSize;

    /**
     * 当前分片序号，从 0 开始
     */
    private Integer chunkNumber;

    /**
     * 总分片数
     */
    private Integer totalChunks;

    /**
     * 分片文件本体
     */
    private MultipartFile chunk;
}
