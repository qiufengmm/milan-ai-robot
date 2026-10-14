package com.milanai.robot.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * @Description: AI 客服 Markdown 问答文件上传事件
 **/
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class AiCustomerServiceMdUploadedEvent {
    /**
     * t_ai_customer_service_md_storage 表记录主键 ID
     */
    private Long id;
    /**
     * 存储路径
     */
    private String filePath;
    /**
     * 元数据
     */
    private Map<String, Object> metadatas;
}

