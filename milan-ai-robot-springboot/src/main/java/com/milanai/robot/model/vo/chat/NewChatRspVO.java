package com.milanai.robot.model.vo.chat;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;


/**
 * @description: 新建对话
 **/
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class NewChatRspVO {
    /**
     * 摘要
     */
    private String summary;

    /**
     * 对话 UUID
     */
    private String uuid;
}
