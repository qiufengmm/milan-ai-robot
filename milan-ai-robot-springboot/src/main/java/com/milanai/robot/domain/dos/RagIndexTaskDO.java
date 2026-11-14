package com.milanai.robot.domain.dos;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("t_rag_index_task")
public class RagIndexTaskDO {
    @TableId(value = "file_id", type = IdType.INPUT)
    private Long fileId;
    private Long knowledgeBaseId;
    private String operation;
    private Integer attempts;
    private String lastError;
    private LocalDateTime nextAttemptTime;
    private LocalDateTime updateTime;
}
