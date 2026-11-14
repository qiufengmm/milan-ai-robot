package com.milanai.robot.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.milanai.robot.domain.dos.AiCustomerServiceFileStorageDO;
import com.milanai.robot.domain.mapper.AiCustomerServiceFileStorageMapper;
import com.milanai.robot.enums.AiCustomerServiceFileStatusEnum;
import com.milanai.robot.service.FileDocumentGuard;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class FileDocumentGuardImpl implements FileDocumentGuard {
    private final AiCustomerServiceFileStorageMapper mapper;
    public FileDocumentGuardImpl(AiCustomerServiceFileStorageMapper mapper) { this.mapper = mapper; }

    public List<Document> retainOwned(List<Document> documents, long knowledgeBaseId) {
        if (knowledgeBaseId <= 0 || documents == null || documents.isEmpty()) return List.of();
        List<Document> scoped = documents.stream()
                .filter(d -> Long.toString(knowledgeBaseId).equals(String.valueOf(d.getMetadata().get("knowledge_base_id"))))
                .filter(d -> fileId(d) != null).toList();
        Set<Long> ids = scoped.stream().map(this::fileId).collect(Collectors.toSet());
        if (ids.isEmpty()) return List.of();
        Set<Long> valid = mapper.selectList(Wrappers.<AiCustomerServiceFileStorageDO>lambdaQuery()
                        .in(AiCustomerServiceFileStorageDO::getId, ids)
                        .eq(AiCustomerServiceFileStorageDO::getKnowledgeBaseId, knowledgeBaseId)
                        .eq(AiCustomerServiceFileStorageDO::getStatus, AiCustomerServiceFileStatusEnum.COMPLETED.getCode()))
                .stream().map(AiCustomerServiceFileStorageDO::getId).collect(Collectors.toSet());
        return scoped.stream().filter(d -> valid.contains(fileId(d))).toList();
    }

    private Long fileId(Document document) {
        try { return Long.valueOf(String.valueOf(document.getMetadata().get("mdStorageId"))); }
        catch (NumberFormatException ex) { return null; }
    }
}
