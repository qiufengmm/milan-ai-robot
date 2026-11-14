package com.milanai.robot.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.milanai.robot.config.RagProperties;
import com.milanai.robot.domain.dos.*;
import com.milanai.robot.domain.mapper.*;
import com.milanai.robot.enums.AiCustomerServiceFileStatusEnum;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.*;

/** PostgreSQL outbox: ES synchronization can retry after an application restart. */
@Service
@Slf4j
public class RagIndexService {
    private final RagIndexTaskMapper tasks;
    private final AiCustomerServiceFileStorageMapper files;
    private final EsChunkService es;
    private final RagProperties properties;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final TransactionTemplate transaction;

    public RagIndexService(RagIndexTaskMapper tasks, AiCustomerServiceFileStorageMapper files,
                           EsChunkService es, RagProperties properties, JdbcTemplate jdbc,
                           ObjectMapper json, TransactionTemplate transaction) {
        this.tasks=tasks; this.files=files; this.es=es; this.properties=properties;
        this.jdbc=jdbc; this.json=json; this.transaction=transaction;
    }

    public void enqueueUpsert(Long fileId, Long kb) { tasks.enqueue(fileId, kb, "UPSERT"); }
    public void enqueueDelete(Long fileId, Long kb) { tasks.enqueue(fileId, kb, "DELETE"); }

    public String status(AiCustomerServiceFileStorageDO file) {
        if (!Objects.equals(file.getStatus(), AiCustomerServiceFileStatusEnum.COMPLETED.getCode())) return "NOT_READY";
        if (!properties.getElasticsearch().isEnabled()) return "DISABLED";
        RagIndexTaskDO task=tasks.selectById(file.getId());
        return task==null ? "SYNCED" : task.getAttempts()>0 ? "FAILED" : "PENDING";
    }

    @Transactional
    public int reindex(long kb) {
        List<AiCustomerServiceFileStorageDO> owned=files.selectList(Wrappers.<AiCustomerServiceFileStorageDO>lambdaQuery()
                .eq(AiCustomerServiceFileStorageDO::getKnowledgeBaseId,kb)
                .eq(AiCustomerServiceFileStorageDO::getStatus,AiCustomerServiceFileStatusEnum.COMPLETED.getCode()));
        owned.forEach(f -> enqueueUpsert(f.getId(),kb));
        return owned.size();
    }

    @Scheduled(fixedDelayString="${rag.elasticsearch.sync-delay-ms:3000}")
    public synchronized void synchronizePending() {
        if (!properties.getElasticsearch().isEnabled()) return;
        for (int i=0;i<5;i++) {
            try {
                Boolean processed=transaction.execute(status -> {
                    RagIndexTaskDO task=tasks.lockNext();
                    if (task==null) return false;
                    try {
                        es.ensureIndex();
                        // Never lock/update an owner row here: uploads/deletes lock owner then outbox.
                        es.deleteByFileId(task.getFileId());
                        AiCustomerServiceFileStorageDO owner=files.selectById(task.getFileId());
                        if ("UPSERT".equals(task.getOperation()) && owner!=null
                                && Objects.equals(owner.getKnowledgeBaseId(),task.getKnowledgeBaseId())
                                && Objects.equals(owner.getStatus(),AiCustomerServiceFileStatusEnum.COMPLETED.getCode())) {
                            List<Document> documents=readDocuments(task.getFileId(),task.getKnowledgeBaseId());
                            if (documents.isEmpty()) throw new IllegalStateException("Completed file has no PostgreSQL vector documents");
                            es.upsert(documents);
                        }
                        tasks.deleteById(task.getFileId());
                    } catch (Exception ex) {
                        task.setAttempts(task.getAttempts()+1);
                        task.setLastError(ex.getClass().getSimpleName());
                        task.setNextAttemptTime(LocalDateTime.now().plusSeconds(Math.min(60,task.getAttempts()*5L)));
                        task.setUpdateTime(LocalDateTime.now());
                        tasks.updateById(task);
                        log.warn("RAG index sync failed fileId={}, errorType={}",task.getFileId(),ex.getClass().getSimpleName());
                    }
                    return true;
                });
                if (!Boolean.TRUE.equals(processed)) break;
            } catch (Exception ex) {
                log.warn("RAG index queue unavailable: {}",ex.getClass().getSimpleName());
                break;
            }
        }
    }

    @SuppressWarnings("unchecked")
    private List<Document> readDocuments(Long fileId,Long kb) {
        return jdbc.query("SELECT id,content,metadata FROM t_vector_store WHERE metadata->>'mdStorageId'=? AND metadata->>'knowledge_base_id'=? ORDER BY id",
                (rs,n) -> Document.builder().id(rs.getString("id")).text(rs.getString("content"))
                        .metadata((Map<String,Object>)json.readValue(rs.getString("metadata"),Map.class)).build(),
                fileId.toString(),kb.toString());
    }
}
