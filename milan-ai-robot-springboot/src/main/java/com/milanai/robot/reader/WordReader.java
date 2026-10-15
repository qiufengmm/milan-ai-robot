package com.milanai.robot.reader;

import org.springframework.ai.document.Document;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * @Author: 米兰AI
 * @Date: 2026/10/15 10:58
 * @Version: v1.0.0
 * @Description: Word 文档读取（基于 Apache Tika，支持 .doc / .docx）
 **/
@Component
public class WordReader {

    /**
     * 读取 Word 文件为 Document 文档集合
     *
     * @param resource
     * @param metadatas
     * @return
     */
    public List<Document> read(Resource resource, Map<String, Object> metadatas) {
        // Tika 是通用的文档解析框架，能解析 Word、HTML、PPT 等多种格式。
        // 这里读出来的是整篇文本，一个文档块；内容较长的部分由后面的 Token 切分器处理。
        TikaDocumentReader tikaDocumentReader = new TikaDocumentReader(resource);

        // Reader 自身不支持传入业务元数据，读取后统一附加（文件存储 ID、文件名等）
        return DocumentMetadataSupport.withMetadata(tikaDocumentReader.get(), metadatas);
    }
}
