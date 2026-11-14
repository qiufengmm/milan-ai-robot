package com.milanai.robot.domain.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.StringUtils;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.milanai.robot.domain.dos.AiCustomerServiceFileStorageDO;

import java.time.LocalDate;
import java.util.Objects;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Param;

/**
 * @Description: AI 客服问答文件存储
 **/
public interface AiCustomerServiceFileStorageMapper extends BaseMapper<AiCustomerServiceFileStorageDO> {

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
                                                               LocalDate startDate, LocalDate endDate, Long kb) {
        return selectPage(new Page<>(current, size), Wrappers.<AiCustomerServiceFileStorageDO>lambdaQuery()
                .eq(AiCustomerServiceFileStorageDO::getKnowledgeBaseId, kb)
                .like(StringUtils.isNotBlank(fileName), AiCustomerServiceFileStorageDO::getFileName, fileName)
                .ge(startDate != null, AiCustomerServiceFileStorageDO::getCreateTime, startDate)
                .lt(endDate != null, AiCustomerServiceFileStorageDO::getCreateTime, endDate)
                .orderByDesc(AiCustomerServiceFileStorageDO::getCreateTime));
    }

    /**
     * 分页查询
     *
     * @param current   当前页
     * @param size      每页数量
     * @param fileName  文件名称（模糊查询）
     * @param startDate 起始日期
     * @param endDate   结束日期
     * @return 分页结果
     */
    default Page<AiCustomerServiceFileStorageDO> selectPageList(Long current, Long size, String fileName,
                                                               LocalDate startDate, LocalDate endDate) {
        // 分页对象(查询第几页、每页多少数据)
        Page<AiCustomerServiceFileStorageDO> page = new Page<>(current, size);

        // 构建查询条件
        LambdaQueryWrapper<AiCustomerServiceFileStorageDO> wrapper = Wrappers.<AiCustomerServiceFileStorageDO>lambdaQuery()
                .like(StringUtils.isNotBlank(fileName), AiCustomerServiceFileStorageDO::getFileName, fileName) // like 模糊查询
                .ge(Objects.nonNull(startDate), AiCustomerServiceFileStorageDO::getCreateTime, startDate) // 大于等于 startDate
                .lt(Objects.nonNull(endDate), AiCustomerServiceFileStorageDO::getCreateTime, endDate) // 小于 endDate
                .orderByDesc(AiCustomerServiceFileStorageDO::getCreateTime); // 按创建时间倒序

        return selectPage(page, wrapper);
    }

    /**
     * 根据文件 MD5 值查询，用于秒传 / 断点续传判断
     *
     * @param fileMd5 文件 MD5
     * @return 文件记录，不存在时为 null
     */
    default AiCustomerServiceFileStorageDO selectByMd5(String fileMd5) {
        return selectOne(Wrappers.<AiCustomerServiceFileStorageDO>lambdaQuery()
                .eq(AiCustomerServiceFileStorageDO::getFileMd5, fileMd5));
    }

    /**
     * 已上传分片数 +1。
     * <p>
     * 用 SQL 表达式自增而不是「先查再写」，是为了避免并发上传分片时读-改-写导致的计数丢失。
     *
     * @param id 文件记录 ID
     * @return 影响行数
     */
    default int incrementUploadedChunks(Long id) {
        return update(Wrappers.<AiCustomerServiceFileStorageDO>lambdaUpdate()
                .eq(AiCustomerServiceFileStorageDO::getId, id)
                .setSql("uploaded_chunks = uploaded_chunks + 1"));
    }
}
