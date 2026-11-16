package com.milanai.robot.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.milanai.robot.domain.dos.ChatDO;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * @Version: v1.0.0
 * @Description: TODO
 **/
public interface ChatMapper extends BaseMapper<ChatDO> {
    @Select("SELECT * FROM t_chat WHERE uuid = #{uuid} FOR UPDATE")
    ChatDO lockByUuid(@Param("uuid") String uuid);
}
