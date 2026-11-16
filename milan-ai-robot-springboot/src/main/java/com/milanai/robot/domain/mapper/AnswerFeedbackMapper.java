package com.milanai.robot.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.milanai.robot.domain.dos.AnswerFeedbackDO;
import com.milanai.robot.domain.dos.FeedbackAnswerContext;
import com.milanai.robot.domain.dos.FeedbackQueueRow;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface AnswerFeedbackMapper extends BaseMapper<AnswerFeedbackDO> {
    @Select("""
            INSERT INTO t_answer_feedback(message_id,user_id,description,status,create_time,update_time)
            VALUES(#{answerId},#{userId},#{description},'OPEN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
            ON CONFLICT (message_id,user_id) DO UPDATE
              SET description=EXCLUDED.description, update_time=CURRENT_TIMESTAMP
              WHERE t_answer_feedback.status='OPEN'
            RETURNING id
            """)
    @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
    Long saveOrUpdateOpen(@Param("answerId") Long answerId, @Param("userId") Long userId,
                          @Param("description") String description);

    @Select("SELECT * FROM t_answer_feedback WHERE id=#{id} FOR UPDATE")
    AnswerFeedbackDO lockById(@Param("id") Long id);

    @Select("""
            SELECT a.id AS answer_id, a.chat_uuid, c.user_id AS owner_user_id,
                   c.knowledge_base_id, a.role AS answer_role, a.status AS answer_status,
                   a.content AS answer_content, a.sources_json,
                   COALESCE(u.content, '') AS user_question
            FROM t_chat_message a
            JOIN t_chat c ON c.uuid=a.chat_uuid
            LEFT JOIN t_chat_message u ON u.chat_uuid=a.chat_uuid
              AND u.request_id=a.request_id AND u.role='user'
            WHERE a.id=#{answerId}
            """)
    @Results(id = "feedbackAnswerContext", value = {
            @Result(property = "answerId", column = "answer_id"),
            @Result(property = "chatUuid", column = "chat_uuid"),
            @Result(property = "ownerUserId", column = "owner_user_id"),
            @Result(property = "knowledgeBaseId", column = "knowledge_base_id"),
            @Result(property = "answerRole", column = "answer_role"),
            @Result(property = "answerStatus", column = "answer_status"),
            @Result(property = "answerContent", column = "answer_content"),
            @Result(property = "sourcesJson", column = "sources_json"),
            @Result(property = "userQuestion", column = "user_question")
    })
    FeedbackAnswerContext selectAnswerContext(@Param("answerId") Long answerId);

    @Select("""
            SELECT f.id, f.message_id AS answer_id, f.user_id, reporter.display_name AS reporter_display_name,
                   c.knowledge_base_id, COALESCE(q.content,'') AS user_question,
                   a.content AS answer_content, a.sources_json, f.description, f.status,
                   f.resolved_by, f.resolution_note, f.create_time, f.update_time
            FROM t_answer_feedback f
            JOIN t_chat_message a ON a.id=f.message_id
            JOIN t_chat c ON c.uuid=a.chat_uuid
            JOIN t_user reporter ON reporter.id=f.user_id
            LEFT JOIN t_chat_message q ON q.chat_uuid=a.chat_uuid AND q.request_id=a.request_id AND q.role='user'
            WHERE c.knowledge_base_id=#{knowledgeBaseId}
            ORDER BY CASE WHEN f.status='OPEN' THEN 0 ELSE 1 END, f.update_time DESC, f.id DESC
            """)
    @Results(id = "feedbackQueueRow", value = {
            @Result(property = "id", column = "id"),
            @Result(property = "answerId", column = "answer_id"),
            @Result(property = "userId", column = "user_id"),
            @Result(property = "reporterDisplayName", column = "reporter_display_name"),
            @Result(property = "knowledgeBaseId", column = "knowledge_base_id"),
            @Result(property = "userQuestion", column = "user_question"),
            @Result(property = "answerContent", column = "answer_content"),
            @Result(property = "sourcesJson", column = "sources_json"),
            @Result(property = "description", column = "description"),
            @Result(property = "status", column = "status"),
            @Result(property = "resolvedBy", column = "resolved_by"),
            @Result(property = "resolutionNote", column = "resolution_note"),
            @Result(property = "createTime", column = "create_time"),
            @Result(property = "updateTime", column = "update_time")
    })
    List<FeedbackQueueRow> selectMaintenanceQueue(@Param("knowledgeBaseId") Long knowledgeBaseId);
}
