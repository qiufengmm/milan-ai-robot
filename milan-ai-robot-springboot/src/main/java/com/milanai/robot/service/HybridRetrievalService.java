package com.milanai.robot.service;

import org.springframework.ai.document.Document;
import java.util.List;

public interface HybridRetrievalService {
    List<Document> search(String query, int topK, Long knowledgeBaseId);
}
