package com.milanai.robot.model.vo.chat;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * @Version: v1.0.0
 * @Description: AI 对话响应类
 **/
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class AIResponse {

    // 流式响应内容
    private String v;

    // 推理过程
    private String reasoning;

}
