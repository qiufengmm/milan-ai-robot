package com.milanai.robot.reader;

import org.springframework.ai.document.Document;
import org.springframework.ai.reader.TextReader;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * @Author: 米兰AI
 * @Date: 2026/10/15 10:58
 * @Version: v1.0.0
 * @Description: TXT 文档读取
 **/
@Component
public class TxtReader {

    /**
     * 读取 TXT 文件为 Document 文档集合
     *
     * @param resource
     * @param metadatas
     * @return
     */
    public List<Document> read(Resource resource, Map<String, Object> metadatas) {
        TextReader textReader = new TextReader(resource);

        // Reader 自身不支持传入业务元数据，读取后统一附加（文件存储 ID、文件名等）
        return DocumentMetadataSupport.withMetadata(textReader.get(), metadatas);
    }
}
