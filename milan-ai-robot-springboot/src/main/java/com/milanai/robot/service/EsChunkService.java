package com.milanai.robot.service;

import org.springframework.ai.document.Document;
import java.util.List;
import java.util.Set;

public interface EsChunkService {
    void ensureIndex();
    void upsert(List<Document> documents);
    List<Document> searchByKeyword(String query, int topK, Long knowledgeBaseId, Set<Long> allowedFileIds);
    void deleteByFileId(Long fileId);
}
