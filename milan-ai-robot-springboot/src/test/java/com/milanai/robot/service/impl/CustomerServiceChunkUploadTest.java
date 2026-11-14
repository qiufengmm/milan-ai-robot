package com.milanai.robot.service.impl;

import com.milanai.robot.domain.dos.*;
import com.milanai.robot.domain.mapper.*;
import com.milanai.robot.enums.AiCustomerServiceFileStatusEnum;
import com.milanai.robot.event.AiCustomerServiceMdUploadedEvent;
import com.milanai.robot.exception.BizException;
import com.milanai.robot.model.vo.customerService.*;
import com.milanai.robot.service.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CustomerServiceChunkUploadTest {
    @TempDir Path directory;
    private final Map<String,AiCustomerServiceFileStorageDO> files=new HashMap<>();
    private final List<FileChunkInfoDO> chunks=new ArrayList<>();
    private final List<AiCustomerServiceMdUploadedEvent> events=new ArrayList<>();
    private final AtomicLong sequence=new AtomicLong(1);
    private CustomerServiceImpl service;
    private static final byte[] FIRST="Product warranty ".getBytes(StandardCharsets.UTF_8);
    private static final byte[] LAST="is twelve months.".getBytes(StandardCharsets.UTF_8);

    @BeforeEach
    void setUp() {
        var mapper=mock(AiCustomerServiceFileStorageMapper.class);
        when(mapper.insertUploadIfAbsent(any())).thenAnswer(call -> {
            AiCustomerServiceFileStorageDO row=call.getArgument(0);
            String key=key(row.getKnowledgeBaseId(),row.getFileMd5());
            if (files.containsKey(key)) return 0;
            row.setId(sequence.getAndIncrement());row.setStatus(0);row.setUploadedChunks(0);row.setFilePath("");
            files.put(key,row);return 1;
        });
        when(mapper.lockUpload(anyString(),anyLong())).thenAnswer(call -> files.get(key(call.getArgument(1),call.getArgument(0))));
        when(mapper.selectByMd5(anyString(),anyLong())).thenAnswer(call -> files.get(key(call.getArgument(1),call.getArgument(0))));
        when(mapper.lockById(anyLong())).thenAnswer(call -> byId(call.getArgument(0)));
        doAnswer(call -> {
            AiCustomerServiceFileStorageDO patch=call.getArgument(0);var row=byId(patch.getId());
            if(row==null)return 0;
            if(patch.getStatus()!=null)row.setStatus(patch.getStatus());
            if(patch.getFilePath()!=null)row.setFilePath(patch.getFilePath());
            if(patch.getUploadedChunks()!=null)row.setUploadedChunks(patch.getUploadedChunks());
            return 1;
        }).when(mapper).updateById(any(AiCustomerServiceFileStorageDO.class));
        when(mapper.deleteById(anyLong())).thenAnswer(call -> {
            var row=byId(call.getArgument(0));if(row==null)return 0;
            files.remove(key(row.getKnowledgeBaseId(),row.getFileMd5()));return 1;
        });
        var parts=mock(FileChunkInfoMapper.class);
        when(parts.selectChunkedList(anyString(),anyLong())).thenAnswer(call -> chunks.stream()
                .filter(c -> c.getFileMd5().equals(call.getArgument(0)) && c.getKnowledgeBaseId().equals(call.getArgument(1)))
                .sorted(Comparator.comparing(FileChunkInfoDO::getChunkNumber)).toList());
        doAnswer(call -> {FileChunkInfoDO row=call.getArgument(0);row.setId(sequence.getAndIncrement());chunks.add(row);return 1;})
                .when(parts).insert(any(FileChunkInfoDO.class));
        when(parts.deleteByMd5(anyString(),anyLong())).thenAnswer(call -> {
            int before=chunks.size();
            chunks.removeIf(c -> c.getFileMd5().equals(call.getArgument(0)) && c.getKnowledgeBaseId().equals(call.getArgument(1)));
            return before-chunks.size();
        });
        var publisher=mock(ApplicationEventPublisher.class);
        doAnswer(call -> {events.add(call.getArgument(0));return null;}).when(publisher).publishEvent(any(Object.class));
        var libraries=mock(KnowledgeBaseService.class);
        when(libraries.require(any())).thenAnswer(call -> KnowledgeBaseDO.builder()
                .id(KnowledgeBaseService.resolveId(call.getArgument(0))).name("Test").build());
        var indexes=mock(RagIndexService.class);
        when(indexes.status(any())).thenAnswer(call -> ((AiCustomerServiceFileStorageDO)call.getArgument(0)).getStatus()==3?"SYNCED":"NOT_READY");
        service=new CustomerServiceImpl();
        ReflectionTestUtils.setField(service,"fileStoragePath",directory.resolve("files").toString());
        ReflectionTestUtils.setField(service,"chunkPath",directory.resolve("chunks").toString());
        ReflectionTestUtils.setField(service,"aiCustomerServiceFileStorageMapper",mapper);
        ReflectionTestUtils.setField(service,"fileChunkInfoMapper",parts);
        ReflectionTestUtils.setField(service,"eventPublisher",publisher);
        ReflectionTestUtils.setField(service,"knowledgeBaseService",libraries);
        ReflectionTestUtils.setField(service,"ragIndexService",indexes);
        ReflectionTestUtils.setField(service,"vectorStore",mock(VectorStore.class));
    }
    @AfterEach void clearSynchronization(){if(TransactionSynchronizationManager.isSynchronizationActive())TransactionSynchronizationManager.clearSynchronization();}

    @Test void outOfOrderChunksRestoreExactBytesAndOwnedMetadata() throws Exception {
        String md5=hash(FIRST,LAST);
        upload(1,md5,1,2,FIRST.length+LAST.length,LAST,"faq.txt");
        upload(1,md5,0,2,FIRST.length+LAST.length,FIRST,"faq.txt");
        service.mergeChunk(merge(1,md5));
        var file=files.get(key(1L,md5));
        assertThat(Path.of(file.getFilePath())).hasBinaryContent(join(FIRST,LAST));
        assertThat(file.getStatus()).isEqualTo(AiCustomerServiceFileStatusEnum.PENDING.getCode());
        assertThat(events).singleElement().satisfies(e -> assertThat(e.getMetadatas())
                .containsEntry("knowledge_base_id","1").containsEntry("mdStorageId",file.getId()));
        assertThat(chunks).isEmpty();
    }
    @Test void duplicateChunkIsNotCountedTwice() throws Exception {
        String md5=hash(FIRST,LAST);
        upload(1,md5,0,2,FIRST.length+LAST.length,FIRST,"faq.txt");
        upload(1,md5,0,2,FIRST.length+LAST.length,FIRST,"faq.txt");
        assertThat(chunks).hasSize(1);
        assertThat(files.get(key(1L,md5)).getUploadedChunks()).isEqualTo(1);
    }
    @Test void missingChunkCannotBeMerged() throws Exception {
        String md5=hash(FIRST,LAST);
        upload(1,md5,0,2,FIRST.length+LAST.length,FIRST,"faq.txt");
        assertThatThrownBy(() -> service.mergeChunk(merge(1,md5))).isInstanceOf(BizException.class)
                .extracting("errorCode").isEqualTo("20007");
        assertThat(events).isEmpty();
    }
    @Test void invalidMd5IsRejectedBeforeStorageLookup() {
        assertThatThrownBy(() -> service.checkFile(CheckFileReqVO.builder().fileMd5("../invalid").build())).isInstanceOf(BizException.class);
    }
    @Test void incorrectContentHashCannotBeMergedOrPublished() {
        String wrong="00000000000000000000000000000000";
        upload(1,wrong,0,1,FIRST.length,FIRST,"faq.txt");
        assertThatThrownBy(() -> service.mergeChunk(merge(1,wrong))).isInstanceOf(BizException.class)
                .extracting("errorCode").isEqualTo("20009");
        assertThat(events).isEmpty();
    }
    @Test void declaredSizeMismatchCannotBeMerged() throws Exception {
        String md5=hash(FIRST);
        upload(1,md5,0,1,FIRST.length+1,FIRST,"faq.txt");
        assertThatThrownBy(() -> service.mergeChunk(merge(1,md5))).isInstanceOf(BizException.class);
        assertThat(events).isEmpty();
    }
    @Test void repeatedMergeDoesNotRepublishOrOverwrite() throws Exception {
        String md5=hash(FIRST);
        upload(1,md5,0,1,FIRST.length,FIRST,"faq.txt");
        var first=service.mergeChunk(merge(1,md5));
        var second=service.mergeChunk(merge(1,md5));
        assertThat(second.getData()).isEqualTo(first.getData());
        assertThat(events).hasSize(1);
    }
    @Test void identicalFilesInDifferentLibrariesHaveIndependentStorageAndDeletion() throws Exception {
        String md5=hash(FIRST);
        for(long kb:List.of(1L,2L)){
            upload(kb,md5,0,1,FIRST.length,FIRST,"faq.txt");
            service.mergeChunk(merge(kb,md5));
        }
        var a=files.get(key(1L,md5));var b=files.get(key(2L,md5));
        a.setStatus(3);b.setStatus(3);
        assertThat(a.getId()).isNotEqualTo(b.getId());
        assertThat(a.getFilePath()).isNotEqualTo(b.getFilePath());
        service.deleteMarkdownFile(DeleteMarkdownFileReqVO.builder().id(a.getId()).knowledgeBaseId(1L).build());
        assertThat(Path.of(b.getFilePath())).exists().hasBinaryContent(FIRST);
        assertThat(files).hasSize(1);
    }
    @Test void crossLibraryDeletionIsRejected() throws Exception {
        String md5=hash(FIRST);
        upload(1,md5,0,1,FIRST.length,FIRST,"faq.txt");
        var file=files.get(key(1L,md5));
        assertThatThrownBy(() -> service.deleteMarkdownFile(DeleteMarkdownFileReqVO.builder().id(file.getId()).knowledgeBaseId(2L).build()))
                .isInstanceOf(BizException.class).extracting("errorCode").isEqualTo("20004");
        assertThat(files).hasSize(1);
    }
    @Test void resumeReportsOnlyPresentChunksInSelectedLibrary() throws Exception {
        String md5=hash(FIRST,LAST);
        upload(1,md5,0,2,FIRST.length+LAST.length,FIRST,"faq.txt");
        var first=service.checkFile(CheckFileReqVO.builder().fileMd5(md5).knowledgeBaseId(1L).build()).getData();
        var second=service.checkFile(CheckFileReqVO.builder().fileMd5(md5).knowledgeBaseId(2L).build()).getData();
        assertThat(first.getUploadedChunks()).containsExactly(0);
        assertThat(second.getExists()).isFalse();
    }
    @Test void failedParsingIsNotReportedAsSearchableSuccess() throws Exception {
        String md5=hash(FIRST);upload(1,md5,0,1,FIRST.length,FIRST,"faq.txt");service.mergeChunk(merge(1,md5));
        files.get(key(1L,md5)).setStatus(4);
        var check=service.checkFile(CheckFileReqVO.builder().fileMd5(md5).build()).getData();
        assertThat(check.getStatus()).isEqualTo(4);assertThat(check.getIndexStatus()).isEqualTo("NOT_READY");
    }
    @Test void outOfRangeChunkAndPathTraversalNamesAreRejected() throws Exception {
        String md5=hash(FIRST);
        assertThatThrownBy(() -> upload(1,md5,1,1,FIRST.length,FIRST,"faq.txt")).isInstanceOf(BizException.class);
        assertThatThrownBy(() -> upload(1,md5,0,1,FIRST.length,FIRST,"../faq.txt")).isInstanceOf(BizException.class);
        assertThat(files).isEmpty();
    }
    @Test void mismatchingMetadataCannotBeAttachedToExistingTask() throws Exception {
        String md5=hash(FIRST,LAST);
        upload(1,md5,0,2,FIRST.length+LAST.length,FIRST,"faq.txt");
        assertThatThrownBy(() -> upload(1,md5,1,3,FIRST.length+LAST.length,LAST,"faq.txt"))
                .isInstanceOf(BizException.class).extracting("errorCode").isEqualTo("20010");
    }
    @Test void differingDuplicateChunkDoesNotOverwriteStoredBytes() throws Exception {
        String md5=hash(FIRST,LAST);
        upload(1,md5,0,2,FIRST.length+LAST.length,FIRST,"faq.txt");
        assertThatThrownBy(() -> upload(1,md5,0,2,FIRST.length+LAST.length,LAST,"faq.txt")).isInstanceOf(BizException.class);
        assertThat(Path.of(chunks.getFirst().getChunkPath())).hasBinaryContent(FIRST);
    }
    @Test void vectorizationEventIsPublishedOnlyAfterCommit() throws Exception {
        String md5=hash(FIRST);upload(1,md5,0,1,FIRST.length,FIRST,"faq.txt");
        TransactionSynchronizationManager.initSynchronization();
        service.mergeChunk(merge(1,md5));assertThat(events).isEmpty();
        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
        assertThat(events).hasSize(1);
    }

    private AiCustomerServiceFileStorageDO byId(Long id){return files.values().stream().filter(f -> f.getId().equals(id)).findFirst().orElse(null);}
    private String key(Long kb,String md5){return kb+":"+md5;}
    private MergeChunkReqVO merge(long kb,String md5){return MergeChunkReqVO.builder().fileMd5(md5).knowledgeBaseId(kb).build();}
    private void upload(long kb,String md5,int number,int total,long size,byte[] bytes,String name){
        service.uploadChunk(UploadChunkReqVO.builder().knowledgeBaseId(kb).fileMd5(md5).fileName(name).fileSize(size)
                .chunkNumber(number).totalChunks(total).chunk(new MockMultipartFile("chunk","chunk","application/octet-stream",bytes)).build());
    }
    private byte[] join(byte[]...parts) throws Exception{var out=new java.io.ByteArrayOutputStream();for(var p:parts)out.write(p);return out.toByteArray();}
    private String hash(byte[]...parts) throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(join(parts)));}
}