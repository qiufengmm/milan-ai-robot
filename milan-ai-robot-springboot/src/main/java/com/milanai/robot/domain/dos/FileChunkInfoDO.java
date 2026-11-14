package com.milanai.robot.domain.dos;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * @Description: 分片信息表
 **/
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_file_chunk_info")
public class FileChunkInfoDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long knowledgeBaseId;

    /** 所属原始文件的 MD5 值，关联 t_ai_customer_service_file_storage.file_md5 */
    private String fileMd5;

    /** 分片序号，从 0 开始 */
    private Integer chunkNumber;

    /** 分片文件落盘路径 */
    private String chunkPath;

    /** 分片大小（字节） */
    private Long chunkSize;

    private LocalDateTime createTime;
}
