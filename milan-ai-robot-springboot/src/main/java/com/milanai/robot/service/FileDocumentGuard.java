package com.milanai.robot.service;

import org.springframework.ai.document.Document;
import java.util.List;

public interface FileDocumentGuard {
    List<Document> retainOwned(List<Document> documents, long knowledgeBaseId);
}
