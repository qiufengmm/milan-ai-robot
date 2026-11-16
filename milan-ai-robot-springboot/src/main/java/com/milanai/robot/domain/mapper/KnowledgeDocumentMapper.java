package com.milanai.robot.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.milanai.robot.domain.dos.KnowledgeDocumentDO;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface KnowledgeDocumentMapper extends BaseMapper<KnowledgeDocumentDO> {
    @Select("SELECT * FROM t_knowledge_document WHERE id = #{id} FOR UPDATE")
    KnowledgeDocumentDO lockById(@Param("id") Long id);

    @Update("UPDATE t_knowledge_document SET current_file_id = NULL, update_time = CURRENT_TIMESTAMP WHERE id = #{id}")
    int clearCurrentFile(@Param("id") Long id);
}
