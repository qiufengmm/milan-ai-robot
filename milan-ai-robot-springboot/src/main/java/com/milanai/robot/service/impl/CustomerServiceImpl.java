package com.milanai.robot.service.impl;

import cn.hutool.core.io.unit.DataSizeUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.milanai.robot.domain.dos.*;
import com.milanai.robot.domain.mapper.*;
import com.milanai.robot.enums.*;
import com.milanai.robot.event.AiCustomerServiceMdUploadedEvent;
import com.milanai.robot.exception.BizException;
import com.milanai.robot.model.vo.customerService.*;
import com.milanai.robot.service.*;
import com.milanai.robot.utils.*;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FilenameUtils;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.*;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.time.LocalDateTime;
import java.util.*;

@Service
@Slf4j
public class CustomerServiceImpl implements CustomerService {
    private static final Set<String> FORMATS = Set.of("md","markdown","pdf","doc","docx","txt","mp4","mov","avi");
    @Value("${customer-service.file-storage-path}") private String fileStoragePath;
    @Value("${customer-service.chunk-path}") private String chunkPath;
    @Resource private ApplicationEventPublisher eventPublisher;
    @Resource private AiCustomerServiceFileStorageMapper aiCustomerServiceFileStorageMapper;
    @Resource private FileChunkInfoMapper fileChunkInfoMapper;
    @Resource private VectorStore vectorStore;
    @Resource private KnowledgeBaseService knowledgeBaseService;
    @Resource private RagIndexService ragIndexService;
    @Resource private KnowledgeDocumentService documentService;
    @Resource private KnowledgeAccessService knowledgeAccess;

    @Override
    public Response<CheckFileRspVO> checkFile(CheckFileReqVO request) {
        String md5 = validMd5(request.getFileMd5());
        long kb = knowledgeBaseService.require(request.getKnowledgeBaseId()).getId();
        AiCustomerServiceFileStorageDO file = aiCustomerServiceFileStorageMapper.selectByMd5(md5,kb);
        if (file == null) return Response.success(CheckFileRspVO.builder().exists(false).needUpload(true).build());
        boolean merged = file.getStatus() != AiCustomerServiceFileStatusEnum.UPLOADING.getCode() && regular(file.getFilePath());
        List<Integer> uploaded = merged ? List.of() : fileChunkInfoMapper.selectChunkedList(md5,kb).stream()
                .filter(c -> regular(c.getChunkPath())).map(FileChunkInfoDO::getChunkNumber).toList();
        return Response.success(CheckFileRspVO.builder().exists(true).needUpload(!merged)
                .fileId(file.getId()).status(file.getStatus()).indexStatus(ragIndexService.status(file))
                .uploadedChunks(uploaded).build());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Response<?> uploadChunk(UploadChunkReqVO request) {
        String md5 = validMd5(request.getFileMd5());
        validateUpload(request);
        long kb = knowledgeBaseService.require(request.getKnowledgeBaseId()).getId();
        if (request.getDocumentId() != null) {
            documentService.requireSameLibrary(request.getDocumentId(), kb);
        }
        var seed = AiCustomerServiceFileStorageDO.builder().knowledgeBaseId(kb).fileMd5(md5)
                .fileName(request.getFileName()).fileSize(request.getFileSize()).totalChunks(request.getTotalChunks()).build();
        int inserted = aiCustomerServiceFileStorageMapper.insertUploadIfAbsent(seed);
        AiCustomerServiceFileStorageDO owner = aiCustomerServiceFileStorageMapper.lockUpload(md5,kb);
        if (owner == null) throw new BizException(ResponseCodeEnum.UPLOAD_FILE_FAILED);
        if (inserted == 1) {
            documentService.attachDraftToUpload(owner.getId(), kb, request.getFileName(), request.getDocumentId(),
                    knowledgeAccess.currentUser().id());
            owner = aiCustomerServiceFileStorageMapper.lockUpload(md5, kb);
        }
        if (!Objects.equals(owner.getFileSize(),request.getFileSize()) || !Objects.equals(owner.getTotalChunks(),request.getTotalChunks()))
            throw new BizException(ResponseCodeEnum.UPLOAD_CONFLICT);
        if (owner.getStatus() != AiCustomerServiceFileStatusEnum.UPLOADING.getCode()) {
            if (regular(owner.getFilePath())) return Response.success(owner.getId());
            aiCustomerServiceFileStorageMapper.updateById(AiCustomerServiceFileStorageDO.builder().id(owner.getId())
                    .status(AiCustomerServiceFileStatusEnum.UPLOADING.getCode()).filePath("").uploadedChunks(0)
                    .updateTime(LocalDateTime.now()).build());
        }
        List<FileChunkInfoDO> prior = fileChunkInfoMapper.selectChunkedList(md5,kb);
        var existing = prior.stream().filter(c -> c.getChunkNumber().equals(request.getChunkNumber())).findFirst();
        try {
            Path directory = root(chunkPath).resolve(Long.toString(kb)).resolve(md5);
            Files.createDirectories(directory);
            Path path = directory.resolve(request.getChunkNumber()+".chunk");
            if (existing.isPresent() && regular(existing.get().getChunkPath())) {
                try (InputStream input = request.getChunk().getInputStream();
                     InputStream stored = Files.newInputStream(safeChunk(existing.get().getChunkPath()))) {
                    if (!digest(input).equals(digest(stored))) throw new BizException(ResponseCodeEnum.UPLOAD_CONFLICT);
                }
                return Response.success(owner.getId());
            }
            long storedBytes = 0;
            for (FileChunkInfoDO part : prior) {
                if (!regular(part.getChunkPath())) continue;
                long partBytes = Files.size(safeChunk(part.getChunkPath()));
                if (partBytes > owner.getFileSize() - storedBytes)
                    throw new BizException(ResponseCodeEnum.UPLOAD_SIZE_EXCEEDED);
                storedBytes += partBytes;
            }
            if (request.getChunk().getSize() > owner.getFileSize() - storedBytes)
                throw new BizException(ResponseCodeEnum.UPLOAD_SIZE_EXCEEDED);
            request.getChunk().transferTo(path);
            afterCompletion(() -> {}, () -> deleteOne(path));
            FileChunkInfoDO part = FileChunkInfoDO.builder().knowledgeBaseId(kb).fileMd5(md5)
                    .chunkNumber(request.getChunkNumber()).chunkPath(path.toString()).chunkSize(request.getChunk().getSize())
                    .createTime(LocalDateTime.now()).build();
            if (existing.isPresent()) { part.setId(existing.get().getId()); fileChunkInfoMapper.updateById(part); }
            else fileChunkInfoMapper.insert(part);
            int count = (int)fileChunkInfoMapper.selectChunkedList(md5,kb).stream().filter(c -> regular(c.getChunkPath())).count();
            aiCustomerServiceFileStorageMapper.updateById(AiCustomerServiceFileStorageDO.builder().id(owner.getId())
                    .uploadedChunks(count).updateTime(LocalDateTime.now()).build());
            return Response.success(owner.getId());
        } catch (IOException ex) { throw new BizException(ResponseCodeEnum.UPLOAD_FILE_FAILED); }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Response<?> mergeChunk(MergeChunkReqVO request) {
        String md5 = validMd5(request.getFileMd5());
        long kb = knowledgeBaseService.require(request.getKnowledgeBaseId()).getId();
        AiCustomerServiceFileStorageDO owner = aiCustomerServiceFileStorageMapper.lockUpload(md5,kb);
        if (owner == null) throw new BizException(ResponseCodeEnum.MERGE_CHUNK_NOT_FOUND);
        if (regular(owner.getFilePath()) && owner.getStatus() != AiCustomerServiceFileStatusEnum.UPLOADING.getCode()) {
            if (owner.getStatus() == AiCustomerServiceFileStatusEnum.FAILED.getCode()) {
                aiCustomerServiceFileStorageMapper.updateById(AiCustomerServiceFileStorageDO.builder().id(owner.getId())
                        .status(AiCustomerServiceFileStatusEnum.PENDING.getCode()).updateTime(LocalDateTime.now()).build());
                publishAfterCommit(owner,Path.of(owner.getFilePath()),List.of());
            }
            return Response.success(owner.getId());
        }
        List<FileChunkInfoDO> parts = fileChunkInfoMapper.selectChunkedList(md5,kb);
        if (parts.size() != owner.getTotalChunks()) throw new BizException(ResponseCodeEnum.CHUNK_NUM_NOT_COMPLETE);
        for (int i=0;i<parts.size();i++) {
            if (!Objects.equals(parts.get(i).getChunkNumber(),i) || !regular(parts.get(i).getChunkPath()))
                throw new BizException(ResponseCodeEnum.CHUNK_NUM_NOT_COMPLETE);
        }
        validateName(owner.getFileName());
        Path temporary = null;
        try {
            Path storage = root(fileStoragePath);
            Files.createDirectories(storage);
            temporary = Files.createTempFile(storage,owner.getId()+"-",".partial");
            MessageDigest hash = md5Digest();
            long copied = 0;
            try (OutputStream output = new DigestOutputStream(new BufferedOutputStream(Files.newOutputStream(temporary)),hash)) {
                for (var part : parts) {
                    Path chunk = safeChunk(part.getChunkPath());
                    if (Files.size(chunk) != part.getChunkSize()) throw new BizException(ResponseCodeEnum.FILE_INTEGRITY_FAILED);
                    try (InputStream input = new BufferedInputStream(Files.newInputStream(chunk))) { copied += input.transferTo(output); }
                }
            }
            if (copied != owner.getFileSize() || !HexFormat.of().formatHex(hash.digest()).equals(md5))
                throw new BizException(ResponseCodeEnum.FILE_INTEGRITY_FAILED);
            Path target = storage.resolve(owner.getId()+"_"+owner.getFileName());
            try { Files.move(temporary,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException ex) { Files.move(temporary,target,StandardCopyOption.REPLACE_EXISTING); }
            afterCompletion(() -> {}, () -> deleteOne(target));
            aiCustomerServiceFileStorageMapper.updateById(AiCustomerServiceFileStorageDO.builder().id(owner.getId())
                    .status(AiCustomerServiceFileStatusEnum.PENDING.getCode()).filePath(target.toString())
                    .uploadedChunks(owner.getTotalChunks()).updateTime(LocalDateTime.now()).build());
            fileChunkInfoMapper.deleteByMd5(md5,kb);
            publishAfterCommit(owner,target,parts);
            return Response.success(owner.getId());
        } catch (BizException ex) { deleteOne(temporary); throw ex; }
        catch (IOException ex) { deleteOne(temporary); throw new BizException(ResponseCodeEnum.UPLOAD_FILE_FAILED); }
    }

    private void publishAfterCommit(AiCustomerServiceFileStorageDO owner,Path path,List<FileChunkInfoDO> parts) {
        afterCompletion(() -> {
            for (var part : parts) deleteChunk(part.getChunkPath());
            Map<String,Object> metadata = new HashMap<>();
            metadata.put("mdStorageId",owner.getId());
            metadata.put("originalFileName",owner.getFileName());
            metadata.put("knowledge_base_id",owner.getKnowledgeBaseId().toString());
            eventPublisher.publishEvent(AiCustomerServiceMdUploadedEvent.builder().id(owner.getId())
                    .filePath(path.toString()).metadatas(metadata).build());
        }, () -> {});
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Response<?> deleteMarkdownFile(DeleteMarkdownFileReqVO request) {
        long kb = knowledgeBaseService.require(request.getKnowledgeBaseId()).getId();
        AiCustomerServiceFileStorageDO owner = ownedFile(request.getId(),kb,true);
        documentService.assertDraftCanBeDeleted(owner);
        boolean processing = owner.getStatus()==AiCustomerServiceFileStatusEnum.PENDING.getCode()
                || owner.getStatus()==AiCustomerServiceFileStatusEnum.VECTORIZING.getCode();
        if (processing && !Boolean.TRUE.equals(request.getForce())) throw new BizException(ResponseCodeEnum.MARKDOWN_FILE_CANT_DELETE);
        List<FileChunkInfoDO> parts=fileChunkInfoMapper.selectChunkedList(owner.getFileMd5(),kb);
        aiCustomerServiceFileStorageMapper.deleteById(owner.getId());
        vectorStore.delete("mdStorageId == "+owner.getId());
        fileChunkInfoMapper.deleteByMd5(owner.getFileMd5(),kb);
        ragIndexService.enqueueDelete(owner.getId(),kb);
        afterCompletion(() -> {
            if (owner.getFilePath()!=null && !owner.getFilePath().isBlank()) deleteOne(Path.of(owner.getFilePath()));
            for (var part:parts) deleteChunk(part.getChunkPath());
        }, () -> {});
        return Response.success();
    }

    @Override
    public PageResponse<FindMarkdownFilePageListRspVO> findMarkdownFilePageList(FindMarkdownFilePageListReqVO request) {
        long kb=knowledgeBaseService.require(request.getKnowledgeBaseId()).getId();
        var end=request.getEndDate()==null ? null : request.getEndDate().plusDays(1);
        var page = aiCustomerServiceFileStorageMapper.selectPageList(request.getCurrent(),request.getSize(),
                request.getFileName(),request.getStartDate(),end,kb,knowledgeAccess.canEdit(kb));
        var rows=page.getRecords().stream().map(f -> FindMarkdownFilePageListRspVO.builder().id(f.getId())
                .knowledgeBaseId(f.getKnowledgeBaseId()).documentId(f.getDocumentId()).versionNo(f.getVersionNo())
                .publishStatus(f.getPublishStatus()).fileName(f.getFileName()).fileSize(DataSizeUtil.format(Objects.requireNonNullElse(f.getFileSize(),0L)))
                .status(f.getStatus()).indexStatus(ragIndexService.status(f)).createTime(f.getCreateTime()).updateTime(f.getUpdateTime()).remark(f.getRemark()).build()).toList();
        return PageResponse.success(page,rows);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Response<?> updateMarkdownFile(UpdateMarkdownFileReqVO request) {
        long kb=knowledgeBaseService.require(request.getKnowledgeBaseId()).getId();
        ownedFile(request.getId(),kb,true);
        String remark=request.getRemark()==null ? "" : request.getRemark().trim();
        if (remark.length()>500) throw new BizException(ResponseCodeEnum.PARAM_NOT_VALID);
        aiCustomerServiceFileStorageMapper.update(null,Wrappers.<AiCustomerServiceFileStorageDO>lambdaUpdate()
                .eq(AiCustomerServiceFileStorageDO::getId,request.getId()).eq(AiCustomerServiceFileStorageDO::getKnowledgeBaseId,kb)
                .set(AiCustomerServiceFileStorageDO::getRemark,remark).set(AiCustomerServiceFileStorageDO::getUpdateTime,LocalDateTime.now()));
        return Response.success();
    }

    private AiCustomerServiceFileStorageDO ownedFile(Long id,long kb,boolean lock) {
        var owner=lock ? aiCustomerServiceFileStorageMapper.lockById(id) : aiCustomerServiceFileStorageMapper.selectById(id);
        if (owner==null || !Objects.equals(owner.getKnowledgeBaseId(),kb)) throw new BizException(ResponseCodeEnum.MARKDOWN_FILE_NOT_FOUND);
        return owner;
    }
    private void validateUpload(UploadChunkReqVO r) {
        if (r.getChunk()==null || r.getChunk().isEmpty() || r.getFileSize()==null || r.getFileSize()<=0 || r.getFileSize()>512L*1024*1024
                || r.getTotalChunks()==null || r.getTotalChunks()<=0 || r.getTotalChunks()>10000 || r.getChunkNumber()==null
                || r.getChunkNumber()<0 || r.getChunkNumber()>=r.getTotalChunks() || r.getChunk().getSize()>10L*1024*1024 || r.getChunk().getSize()>r.getFileSize())
            throw new BizException(ResponseCodeEnum.PARAM_NOT_VALID);
        validateName(r.getFileName());
        if (!FORMATS.contains(FilenameUtils.getExtension(r.getFileName()).toLowerCase(Locale.ROOT)))
            throw new BizException(ResponseCodeEnum.UNSUPPORTED_FILE_TYPE);
    }
    private void validateName(String name) {
        if (name==null || name.isBlank() || name.length()>160 || !name.equals(FilenameUtils.getName(name))
                || name.indexOf(':')>=0 || name.chars().anyMatch(c -> c<32)) throw new BizException(ResponseCodeEnum.PARAM_NOT_VALID);
    }
    private String validMd5(String value) {
        if (value==null || !value.matches("[a-fA-F0-9]{32}")) throw new BizException(ResponseCodeEnum.PARAM_NOT_VALID);
        return value.toLowerCase(Locale.ROOT);
    }
    private boolean regular(String value) {
        if (value==null || value.isBlank()) return false;
        try { return Files.isRegularFile(Path.of(value),LinkOption.NOFOLLOW_LINKS); } catch (InvalidPathException ex) { return false; }
    }
    private Path root(String path) { return Path.of(path).toAbsolutePath().normalize(); }
    private Path safeChunk(String path) throws IOException {
        Path resolved=Path.of(path).toAbsolutePath().normalize();
        if (!resolved.startsWith(root(chunkPath)) || !Files.isRegularFile(resolved,LinkOption.NOFOLLOW_LINKS)) throw new IOException("Unowned chunk path");
        return resolved;
    }
    private MessageDigest md5Digest() {
        try { return MessageDigest.getInstance("MD5"); } catch (NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
    private String digest(InputStream input) throws IOException {
        MessageDigest hash=md5Digest();
        try (DigestInputStream stream=new DigestInputStream(input,hash)) { stream.transferTo(OutputStream.nullOutputStream()); }
        return HexFormat.of().formatHex(hash.digest());
    }
    private void deleteChunk(String value) {
        try { deleteOne(safeChunk(value)); } catch (IOException ex) { log.warn("Chunk cleanup skipped for an unowned or missing path"); }
    }
    private void deleteOne(Path path) {
        if (path==null) return;
        try { if (Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)) Files.delete(path); }
        catch (IOException ex) { log.warn("File cleanup failed: {}",ex.getClass().getSimpleName()); }
    }
    private void afterCompletion(Runnable committed,Runnable rolledBack) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) { committed.run(); return; }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { committed.run(); }
            @Override public void afterCompletion(int status) { if (status==STATUS_ROLLED_BACK) rolledBack.run(); }
        });
    }
}
