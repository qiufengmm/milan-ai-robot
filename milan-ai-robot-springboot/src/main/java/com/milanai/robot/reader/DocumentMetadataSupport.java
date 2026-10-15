package com.milanai.robot.reader;

import org.springframework.ai.document.Document;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * @Author: 米兰AI
 * @Date: 2026/10/15 10:58
 * @Version: v1.0.0
 * @Description: 文档元数据合并工具
 *
 * PDF / Word / TXT 三个 Reader 都需要把业务元数据（文件存储 ID、原始文件名等）
 * 附加到读出来的每个文档块上，逻辑完全一样，抽到这里共用，避免复制三份。
 **/
public final class DocumentMetadataSupport {

    private DocumentMetadataSupport() {
    }

    /**
     * 将业务元数据附加到每个文档块上（合并 Reader 自带的元数据）
     *
     * @param documents Reader 读出来的文档集合
     * @param metadatas 业务元数据，如 mdStorageId、originalFileName
     * @return 附加了业务元数据的新文档集合
     */
    public static List<Document> withMetadata(List<Document> documents, Map<String, Object> metadatas) {
        return documents.stream()
                .map(doc -> {
                    // 先保留 Reader 自带的元数据，再合并业务元数据
                    Map<String, Object> mergedMetadata = new HashMap<>(doc.getMetadata());
                    mergedMetadata.putAll(metadatas);
                    return Document.builder()
                            .text(doc.getText())
                            .metadata(mergedMetadata)
                            .build();
                })
                .toList();
    }
}
