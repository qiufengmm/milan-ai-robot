package com.milanai.robot.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.milanai.robot.domain.dos.RagIndexTaskDO;
import org.apache.ibatis.annotations.*;

public interface RagIndexTaskMapper extends BaseMapper<RagIndexTaskDO> {
    @Insert("""
            INSERT INTO t_rag_index_task(file_id,knowledge_base_id,operation,attempts,next_attempt_time,update_time)
            VALUES(#{fileId},#{kb},#{operation},0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
            ON CONFLICT(file_id) DO UPDATE SET knowledge_base_id=EXCLUDED.knowledge_base_id,
            operation=EXCLUDED.operation,attempts=0,last_error=NULL,next_attempt_time=CURRENT_TIMESTAMP,update_time=CURRENT_TIMESTAMP
            """)
    int enqueue(@Param("fileId") Long fileId, @Param("kb") Long kb, @Param("operation") String operation);

    @Select("""
            SELECT * FROM t_rag_index_task WHERE next_attempt_time <= CURRENT_TIMESTAMP
            ORDER BY update_time LIMIT 1 FOR UPDATE SKIP LOCKED
            """)
    RagIndexTaskDO lockNext();
}
