package com.milanai.robot.service.impl;

import com.milanai.robot.domain.dos.AiCustomerServiceFileStorageDO;
import com.milanai.robot.domain.mapper.AiCustomerServiceFileStorageMapper;
import com.milanai.robot.service.FileDocumentGuard;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;
import java.util.stream.Collectors;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.HashMap;

@Service
public class FileDocumentGuardImpl implements FileDocumentGuard {
    private final AiCustomerServiceFileStorageMapper mapper;
    public FileDocumentGuardImpl(AiCustomerServiceFileStorageMapper mapper) { this.mapper = mapper; }

    @Override
    public List<Document> retainOwned(List<Document> documents, long knowledgeBaseId, Set<Long> allowedFileIds) {
        if (allowedFileIds == null) throw new IllegalArgumentException("Published file IDs must be provided");
        if (knowledgeBaseId <= 0 || documents == null || documents.isEmpty() || allowedFileIds.isEmpty()) return List.of();
        List<Document> scoped = documents.stream()
                .filter(document -> Long.toString(knowledgeBaseId).equals(String.valueOf(document.getMetadata().get("knowledge_base_id"))))
                .filter(document -> fileId(document) != null)
                .filter(document -> allowedFileIds.contains(fileId(document))).toList();
        Set<Long> ids = scoped.stream().map(this::fileId).collect(Collectors.toSet());
        if (ids.isEmpty()) return List.of();
        Map<Long, AiCustomerServiceFileStorageDO> valid = mapper.selectCurrentPublishedFiles(knowledgeBaseId, List.copyOf(ids)).stream()
                .collect(Collectors.toMap(AiCustomerServiceFileStorageDO::getId, owner -> owner));
        return scoped.stream().filter(document -> valid.containsKey(fileId(document)))
                .map(document -> canonical(document, valid.get(fileId(document)))).toList();
    }

    private Document canonical(Document document, AiCustomerServiceFileStorageDO owner) {
        Map<String, Object> metadata = new HashMap<>(document.getMetadata());
        metadata.put("mdStorageId", owner.getId().toString());
        metadata.put("knowledge_base_id", owner.getKnowledgeBaseId().toString());
        if (owner.getDocumentId() != null) metadata.put("documentId", owner.getDocumentId().toString());
        if (owner.getVersionNo() != null) metadata.put("versionNo", owner.getVersionNo());
        if (owner.getDocumentTitle() != null) metadata.put("documentTitle", owner.getDocumentTitle());
        if (owner.getFileName() != null) metadata.put("originalFileName", owner.getFileName());
        return Document.builder().id(document.getId()).text(document.getText()).metadata(metadata)
                .score(document.getScore()).build();
    }

    private Long fileId(Document document) {
        try { return Long.valueOf(String.valueOf(document.getMetadata().get("mdStorageId"))); }
        catch (NumberFormatException ex) { return null; }
    }
}
