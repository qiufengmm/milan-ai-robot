package com.milanai.robot.service;

import org.springframework.ai.document.Document;
import java.util.List;

public interface EsChunkService {
    void ensureIndex();
    void upsert(List<Document> documents);
    List<Document> searchByKeyword(String query, int topK, Long knowledgeBaseId);
    void deleteByFileId(Long fileId);
}
