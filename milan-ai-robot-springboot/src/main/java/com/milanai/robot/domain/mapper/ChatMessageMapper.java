package com.milanai.robot.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.milanai.robot.domain.dos.ChatMessageDO;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * @Description:
 **/
public interface ChatMessageMapper extends BaseMapper<ChatMessageDO> {
    @Select("SELECT m.* FROM t_chat_message m WHERE m.chat_uuid = #{chatUuid} "
            + "AND m.status = 'COMPLETED' AND m.role IN ('user','assistant') "
            + "AND m.request_id IN (SELECT a.request_id FROM t_chat_message a "
            + "WHERE a.chat_uuid = #{chatUuid} AND a.role = 'assistant' AND a.status = 'COMPLETED' "
            + "AND a.request_id IS NOT NULL ORDER BY a.id DESC LIMIT #{turnLimit}) ORDER BY m.id ASC")
    List<ChatMessageDO> selectCompletedConversationHistory(@Param("chatUuid") String chatUuid,
                                                          @Param("turnLimit") int turnLimit);

    @Update("UPDATE t_chat_message SET status = 'FAILED', error_code = 'INTERRUPTED' "
            + "WHERE role = 'assistant' AND status = 'GENERATING' "
            + "AND create_time < CURRENT_TIMESTAMP - (#{ageMinutes} * INTERVAL '1 minute')")
    int failStaleGeneratingMessages(@Param("ageMinutes") int ageMinutes);

    @Update("UPDATE t_chat_message SET content = #{content}, retrieval_query = #{retrievalQuery}, "
            + "sources_json = #{sourcesJson}, status = 'COMPLETED', error_code = NULL "
            + "WHERE id = #{id} AND role = 'assistant' AND status = 'GENERATING'")
    int completeGenerating(@Param("id") Long id, @Param("content") String content,
                           @Param("retrievalQuery") String retrievalQuery, @Param("sourcesJson") String sourcesJson);

    @Update("UPDATE t_chat_message SET content = #{content}, status = #{status}, error_code = #{errorCode} "
            + "WHERE id = #{id} AND role = 'assistant' AND status = 'GENERATING'")
    int finishGenerating(@Param("id") Long id, @Param("content") String content,
                         @Param("status") String status, @Param("errorCode") String errorCode);

    @Select("SELECT COUNT(*) FROM t_answer_feedback f JOIN t_chat_message m ON m.id=f.message_id "
            + "WHERE m.chat_uuid=#{chatUuid}")
    long countFeedbackByChatUuid(@Param("chatUuid") String chatUuid);
}
