package com.milanai.robot.service;

import org.springframework.ai.document.Document;
import java.util.List;
import java.util.Set;

public interface FileDocumentGuard {
    List<Document> retainOwned(List<Document> documents, long knowledgeBaseId, Set<Long> allowedFileIds);
}
