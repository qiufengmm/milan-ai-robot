package com.milanai.robot.service;

import org.springframework.ai.document.Document;
import java.util.List;

public interface RerankService {
    List<Document> rerank(String query, List<Document> candidates, double minRelevanceScore);
}
