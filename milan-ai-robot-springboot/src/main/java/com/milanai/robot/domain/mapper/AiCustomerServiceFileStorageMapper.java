package com.milanai.robot.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.StringUtils;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.milanai.robot.domain.dos.AiCustomerServiceFileStorageDO;

import java.time.LocalDate;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/**
 * @Description: AI 客服问答文件存储
 **/
public interface AiCustomerServiceFileStorageMapper extends BaseMapper<AiCustomerServiceFileStorageDO> {

    @Select("SELECT COALESCE(MAX(version_no), 0) FROM t_ai_customer_service_file_storage WHERE document_id = #{documentId}")
    int selectMaxVersion(@Param("documentId") Long documentId);

    @Select("SELECT f.id FROM t_ai_customer_service_file_storage f "
            + "JOIN t_knowledge_document d ON d.id = f.document_id AND d.current_file_id = f.id "
            + "WHERE f.knowledge_base_id = #{knowledgeBaseId} AND f.status = 3 AND f.publish_status = 'PUBLISHED' "
            + "ORDER BY f.id")
    List<Long> selectCurrentPublishedFileIds(@Param("knowledgeBaseId") Long knowledgeBaseId);

    @Select("<script>SELECT f.*, d.title AS document_title FROM t_ai_customer_service_file_storage f "
            + "JOIN t_knowledge_document d ON d.id = f.document_id AND d.current_file_id = f.id "
            + "WHERE f.knowledge_base_id = #{knowledgeBaseId} AND f.status = 3 AND f.publish_status = 'PUBLISHED' "
            + "AND f.id IN <foreach item='fileId' collection='fileIds' open='(' separator=',' close=')'>#{fileId}</foreach></script>")
    List<AiCustomerServiceFileStorageDO> selectCurrentPublishedFiles(@Param("knowledgeBaseId") Long knowledgeBaseId,
                                                                     @Param("fileIds") List<Long> fileIds);

    @Insert("""
            INSERT INTO t_ai_customer_service_file_storage
              (knowledge_base_id,file_md5,file_name,file_path,file_size,total_chunks,uploaded_chunks,status,create_time,update_time)
            VALUES (#{knowledgeBaseId},#{fileMd5},#{fileName},'',#{fileSize},#{totalChunks},0,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
            ON CONFLICT (knowledge_base_id,file_md5) DO NOTHING
            """)
    int insertUploadIfAbsent(AiCustomerServiceFileStorageDO file);

    @Select("SELECT * FROM t_ai_customer_service_file_storage WHERE file_md5=#{md5} AND knowledge_base_id=#{kb} FOR UPDATE")
    AiCustomerServiceFileStorageDO lockUpload(@Param("md5") String md5, @Param("kb") Long kb);

    @Select("SELECT * FROM t_ai_customer_service_file_storage WHERE id=#{id} FOR UPDATE")
    AiCustomerServiceFileStorageDO lockById(@Param("id") Long id);

    default AiCustomerServiceFileStorageDO selectByMd5(String fileMd5, Long knowledgeBaseId) {
        return selectOne(Wrappers.<AiCustomerServiceFileStorageDO>lambdaQuery()
                .eq(AiCustomerServiceFileStorageDO::getFileMd5, fileMd5)
                .eq(AiCustomerServiceFileStorageDO::getKnowledgeBaseId, knowledgeBaseId));
    }

    default Page<AiCustomerServiceFileStorageDO> selectPageList(Long current, Long size, String fileName,
                                                               LocalDate startDate, LocalDate endDate, Long kb,
                                                               boolean includeDrafts) {
        return selectPage(new Page<>(current, size), Wrappers.<AiCustomerServiceFileStorageDO>lambdaQuery()
                .eq(AiCustomerServiceFileStorageDO::getKnowledgeBaseId, kb)
                .eq(!includeDrafts, AiCustomerServiceFileStorageDO::getPublishStatus, "PUBLISHED")
                .like(StringUtils.isNotBlank(fileName), AiCustomerServiceFileStorageDO::getFileName, fileName)
                .ge(startDate != null, AiCustomerServiceFileStorageDO::getCreateTime, startDate)
                .lt(endDate != null, AiCustomerServiceFileStorageDO::getCreateTime, endDate)
                .orderByDesc(AiCustomerServiceFileStorageDO::getCreateTime));
    }

}
