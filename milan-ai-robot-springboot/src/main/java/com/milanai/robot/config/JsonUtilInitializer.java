package com.milanai.robot.config;

import com.milanai.robot.utils.JsonUtil;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

/**
 * @version: v1.0.0
 * @description: 初始化 JsonUtil，让其与 Spring 容器共用同一个 ObjectMapper，
 **/
@Configuration
public class JsonUtilInitializer {

    public JsonUtilInitializer(ObjectMapper objectMapper) {
        JsonUtil.init(objectMapper);
    }

}

