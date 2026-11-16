package com.milanai.robot.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.milanai.robot.domain.dos.AiCustomerServiceFileStorageDO;
import com.milanai.robot.domain.dos.KnowledgeDocumentDO;
import com.milanai.robot.domain.mapper.AiCustomerServiceFileStorageMapper;
import com.milanai.robot.domain.mapper.KnowledgeDocumentMapper;
import com.milanai.robot.enums.AiCustomerServiceFileStatusEnum;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

@Service
public class KnowledgeDocumentService {
    private final KnowledgeDocumentMapper documents;
    private final AiCustomerServiceFileStorageMapper files;
    private final KnowledgeBaseService knowledgeBases;
    private final RagIndexService indexes;
    private final JdbcTemplate jdbc;

    public KnowledgeDocumentService(KnowledgeDocumentMapper documents, AiCustomerServiceFileStorageMapper files,
                                    KnowledgeBaseService knowledgeBases, RagIndexService indexes, JdbcTemplate jdbc) {
        this.documents = documents;
        this.files = files;
        this.knowledgeBases = knowledgeBases;
        this.indexes = indexes;
        this.jdbc = jdbc;
    }

    @Transactional
    public void attachDraftToUpload(Long fileId, Long knowledgeBaseId, String title,
                                    Long existingDocumentId, Long uploaderId) {
        Long libraryId = knowledgeBases.require(knowledgeBaseId).getId();
        KnowledgeDocumentDO document;
        if (existingDocumentId == null) {
            LocalDateTime now = LocalDateTime.now();
            document = KnowledgeDocumentDO.builder().knowledgeBaseId(libraryId).title(title)
                    .createBy(uploaderId).createTime(now).updateTime(now).build();
            documents.insert(document);
        } else {
            document = documents.lockById(existingDocumentId);
            if (document == null || !Objects.equals(document.getKnowledgeBaseId(), libraryId)) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "目标知识文档不存在");
            }
        }
        int versionNo = files.selectMaxVersion(document.getId()) + 1;
        int updated = files.updateById(AiCustomerServiceFileStorageDO.builder().id(fileId)
                .documentId(document.getId()).versionNo(versionNo).uploadedBy(uploaderId)
                .publishStatus("DRAFT").updateTime(LocalDateTime.now()).build());
        if (updated != 1) {
            throw new IllegalStateException("上传记录未能关联到知识文档");
        }
    }

    public void requireSameLibrary(Long documentId, Long knowledgeBaseId) {
        KnowledgeDocumentDO document = require(documentId);
        if (!Objects.equals(document.getKnowledgeBaseId(), knowledgeBaseId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "目标知识文档不存在");
        }
    }

    public KnowledgeDocumentDO require(Long documentId) {
        KnowledgeDocumentDO document = documents.selectById(documentId);
        if (document == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "知识文档不存在");
        }
        return document;
    }

    public List<DocumentSummary> list(Long knowledgeBaseId) {
        knowledgeBases.require(knowledgeBaseId);
        return documents.selectList(Wrappers.<KnowledgeDocumentDO>lambdaQuery()
                        .eq(KnowledgeDocumentDO::getKnowledgeBaseId, knowledgeBaseId)
                        .orderByAsc(KnowledgeDocumentDO::getId))
                .stream().map(document -> {
                    List<AiCustomerServiceFileStorageDO> versions = files.selectList(
                            Wrappers.<AiCustomerServiceFileStorageDO>lambdaQuery()
                                    .eq(AiCustomerServiceFileStorageDO::getDocumentId, document.getId())
                                    .orderByDesc(AiCustomerServiceFileStorageDO::getVersionNo));
                    return new DocumentSummary(document.getId(), document.getKnowledgeBaseId(), document.getTitle(),
                            document.getCurrentFileId(), versions.stream().map(file -> new VersionSummary(file.getId(),
                            file.getVersionNo(), file.getFileName(), file.getStatus(), file.getPublishStatus(),
                            indexes.status(file))).toList());
                }).toList();
    }

    public List<Long> currentPublishedFileIds(Long knowledgeBaseId) {
        return files.selectCurrentPublishedFileIds(knowledgeBaseId);
    }

    @Transactional
    public void publish(Long documentId, Long fileId) {
        KnowledgeDocumentDO document = documents.lockById(documentId);
        if (document == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "知识文档不存在");
        }
        AiCustomerServiceFileStorageDO next = files.selectById(fileId);
        if (next == null || !Objects.equals(next.getDocumentId(), documentId)
                || !Objects.equals(next.getKnowledgeBaseId(), document.getKnowledgeBaseId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "目标版本不属于该知识文档");
        }
        if (!Objects.equals(next.getStatus(), AiCustomerServiceFileStatusEnum.COMPLETED.getCode())
                || !"SYNCED".equals(indexes.status(next))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "文件解析与搜索索引同步完成后才能发布");
        }
        if (Objects.equals(document.getCurrentFileId(), fileId)
                && "PUBLISHED".equals(next.getPublishStatus())) {
            return;
        }
        if (!"DRAFT".equals(next.getPublishStatus())
                && !("WITHDRAWN".equals(next.getPublishStatus())
                && Objects.equals(next.getVersionNo(), files.selectMaxVersion(documentId)))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "不能重新发布历史版本");
        }
        if (document.getCurrentFileId() != null) {
            AiCustomerServiceFileStorageDO previous = files.selectById(document.getCurrentFileId());
            if (previous != null) {
                files.updateById(AiCustomerServiceFileStorageDO.builder().id(previous.getId())
                        .publishStatus("ARCHIVED").updateTime(LocalDateTime.now()).build());
            }
        }
        files.updateById(AiCustomerServiceFileStorageDO.builder().id(next.getId())
                .publishStatus("PUBLISHED").updateTime(LocalDateTime.now()).build());
        document.setCurrentFileId(next.getId());
        document.setUpdateTime(LocalDateTime.now());
        documents.updateById(document);
    }

    @Transactional
    public void withdraw(Long documentId) {
        KnowledgeDocumentDO document = documents.lockById(documentId);
        if (document == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "知识文档不存在");
        }
        if (document.getCurrentFileId() == null) {
            return;
        }
        files.updateById(AiCustomerServiceFileStorageDO.builder().id(document.getCurrentFileId())
                .publishStatus("WITHDRAWN").updateTime(LocalDateTime.now()).build());
        documents.clearCurrentFile(documentId);
    }

    public void assertDraftCanBeDeleted(AiCustomerServiceFileStorageDO file) {
        if (!"DRAFT".equals(file.getPublishStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "当前版和历史版保留，不允许删除");
        }
    }

    public List<ChunkPreview> preview(Long documentId, Long fileId) {
        KnowledgeDocumentDO document = require(documentId);
        AiCustomerServiceFileStorageDO file = files.selectById(fileId);
        if (file == null || !Objects.equals(file.getDocumentId(), documentId)
                || !Objects.equals(file.getKnowledgeBaseId(), document.getKnowledgeBaseId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "目标版本不属于该知识文档");
        }
        return jdbc.query("SELECT id::text, content FROM t_vector_store "
                        + "WHERE metadata ->> 'mdStorageId' = ? ORDER BY id LIMIT 3",
                (result, row) -> new ChunkPreview(result.getString("id"), result.getString("content")),
                Long.toString(fileId));
    }

    public record DocumentSummary(Long id, Long knowledgeBaseId, String title, Long currentFileId,
                                  List<VersionSummary> versions) {}
    public record VersionSummary(Long id, Integer versionNo, String fileName, Integer status,
                                 String publishStatus, String indexStatus) {}
    public record ChunkPreview(String chunkId, String content) {}
}
