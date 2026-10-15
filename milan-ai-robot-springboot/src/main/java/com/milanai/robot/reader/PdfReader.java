package com.milanai.robot.reader;

import org.springframework.ai.document.Document;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.reader.pdf.config.PdfDocumentReaderConfig;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * @Author: 米兰AI
 * @Date: 2026/10/15 10:58
 * @Version: v1.0.0
 * @Description: PDF 文档读取（底层使用 Apache PDFBox）
 **/
@Component
public class PdfReader {

    /**
     * 读取 PDF 文件为 Document 文档集合
     *
     * @param resource
     * @param metadatas
     * @return
     */
    public List<Document> read(Resource resource, Map<String, Object> metadatas) {
        // 按页解析 PDF：一页一个文档块
        PagePdfDocumentReader pdfDocumentReader = new PagePdfDocumentReader(resource,
                PdfDocumentReaderConfig.builder().build());

        // Reader 自身不支持传入业务元数据，读取后统一附加（文件存储 ID、文件名等）
        return DocumentMetadataSupport.withMetadata(pdfDocumentReader.get(), metadatas);
    }
}
