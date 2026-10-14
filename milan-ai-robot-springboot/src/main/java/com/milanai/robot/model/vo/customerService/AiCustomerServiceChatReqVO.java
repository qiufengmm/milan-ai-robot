package com.milanai.robot.model.vo.customerService;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;


/**
 * @description: AI 智能客服聊天
 **/
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class AiCustomerServiceChatReqVO {

    @NotBlank(message = "用户消息不能为空")
    private String message;

    /**
     * 对话 ID
     */
    private String chatId;

}
