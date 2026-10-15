package com.milanai.robot.event.listener;

import com.google.common.collect.Lists;
import com.milanai.robot.domain.dos.AiCustomerServiceMdStorageDO;
import com.milanai.robot.domain.mapper.AiCustomerServiceMdStorageMapper;
import com.milanai.robot.enums.AiCustomerServiceMdStatusEnum;
import com.milanai.robot.enums.ResponseCodeEnum;
import com.milanai.robot.event.AiCustomerServiceMdUploadedEvent;
import com.milanai.robot.exception.BizException;
import com.milanai.robot.reader.MarkdownReader;
import com.milanai.robot.reader.PdfReader;
import com.milanai.robot.reader.TxtReader;
import com.milanai.robot.reader.WordReader;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.document.Document;
import org.springframework.ai.document.id.IdGenerator;
import org.springframework.ai.document.id.JdkSha256HexIdGenerator;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.FileSystemResource;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * @Description: Markdown 文件上传事件监听
 **/
@Component
@Slf4j
public class AiCustomerServiceMdUploadedListener {

    @Resource
    private MarkdownReader markdownReader;
    @Resource
    private PdfReader pdfReader;
    @Resource
    private WordReader wordReader;
    @Resource
    private TxtReader txtReader;
    @Resource
    private VectorStore vectorStore;
    @Resource
    private AiCustomerServiceMdStorageMapper aiCustomerServiceMdStorageMapper;
    @Resource
    private TransactionTemplate transactionTemplate;

    /**
     * Markdown 文件向量化
     * @param event
     */
    @EventListener
    @Async("eventTaskExecutor") // 指定使用我们自定义的线程池
    public void vectorizing(AiCustomerServiceMdUploadedEvent event) {
        log.info("## AiCustomerServiceMdUploadedEvent: {}", event);

        // 文件存储表主键 ID
        Long id =  event.getId();
        // Markdown 文件存储路径
        String filePath = event.getFilePath();
        // 元数据
        Map<String, Object> metadatas = event.getMetadatas();

        // 更新存储文件的处理状态为 “向量化中”
        aiCustomerServiceMdStorageMapper.updateById(AiCustomerServiceMdStorageDO.builder()
                .id(id)
                .status(AiCustomerServiceMdStatusEnum.VECTORIZING.getCode())
                .updateTime(LocalDateTime.now())
                .build());

        // 编程式事务
        boolean isSuccess = Boolean.TRUE.equals(transactionTemplate.execute(status -> {
            try {
                // 读取文件
                org.springframework.core.io.Resource resource = new FileSystemResource(filePath);

                // 解析为 Document 集合
                // 根据文件扩展名，使用对应格式的读取器（Markdown / PDF / Word / TXT）
                String originalFileName = String.valueOf(metadatas.get("originalFileName"));
                List<Document> documents = readDocuments(originalFileName, resource, metadatas);

                // 各格式的初始读取粒度不一样：Markdown 已按水平线切成问答块，
                // PDF 是按页、Word / TXT 是整篇，后两者可能是一个很大的文档块，
                // 这里再用 Token 切分器按目标大小（默认 800 Token）二次切分，
                // 避免大块文本直接向量化导致语义被"平均化"、检索命中率下降。
                documents = new TokenTextSplitter().apply(documents);

                log.info("## Token 切分完成, 切分后文档块数量: {}", documents.size());

                // 防止重复添加相同文档到 PGVector 中
                // 这里仅对正文内容做哈希，同一份文件重复上传才能命中同一个 ID，从而被覆盖而非新增
                JdkSha256HexIdGenerator jdkSha256HexIdGenerator = new JdkSha256HexIdGenerator();
                IdGenerator contentHashIdGenerator = contents -> jdkSha256HexIdGenerator.generateId(contents[0]);

                // 重建 Document，将随机 ID 替换为基于内容哈希的确定性 ID
                List<Document> documentsWithStableId = documents.stream()
                        .map(doc -> Document.builder()
                                .text(doc.getText()) // 保留原文内容
                                .metadata(doc.getMetadata()) // 保留文件关联元数据
                                .idGenerator(contentHashIdGenerator) // 基于内容哈希生成确定性 ID
                                .build())
                        .toList();

                // 通过向量模型，将文档分批向量化并写入 PGVector
                // 注意：向量模型服务端限制单次批量向量化数量不能超过 10 条，这里按 10 条一批分批写入
                // 相同内容永远落到同一个 ID 上，天然幂等，重复上传不会产生重复数据
                for (List<Document> batch : Lists.partition(documentsWithStableId, 10)) {
                    vectorStore.add(batch);
                }

                // 更新存储文件的处理状态为 “已完成”
                aiCustomerServiceMdStorageMapper.updateById(AiCustomerServiceMdStorageDO.builder()
                        .id(id)
                        .status(AiCustomerServiceMdStatusEnum.COMPLETED.getCode())
                        .updateTime(LocalDateTime.now())
                        .build());

                return true;
            } catch (Exception ex) {
                log.error("## Markdown 文件向量化失败: {}", event, ex);
                status.setRollbackOnly(); // 标记事务为回滚
                return false;
            }
        }));

        // 若事务执行失败，更新存储文件的处理状态为 “失败”
        if (!isSuccess) {
            aiCustomerServiceMdStorageMapper.updateById(AiCustomerServiceMdStorageDO.builder()
                    .id(id)
                    .status(AiCustomerServiceMdStatusEnum.FAILED.getCode())
                    .updateTime(LocalDateTime.now())
                    .build());
        }
    }

    /**
     * 按文件扩展名，分别使用对应格式的读取器（Markdown / PDF / Word / TXT）
     *
     * @param originalFileName 上传时的原始文件名
     * @param resource         文件资源
     * @param metadatas        业务元数据（文件存储 ID、文件名等）
     * @return 解析后的文档集合
     */
    private List<Document> readDocuments(String originalFileName,
                                         org.springframework.core.io.Resource resource,
                                         Map<String, Object> metadatas) {
        // 提取文件扩展名，并统一转小写（如 “产品FAQ.PDF” -> “pdf”）
        String fileExtension = StringUtils.substringAfterLast(originalFileName, ".").toLowerCase();

        return switch (fileExtension) {
            case "md", "markdown" -> markdownReader.loadMarkdown(resource, metadatas);
            case "pdf" -> pdfReader.read(resource, metadatas);
            case "doc", "docx" -> wordReader.read(resource, metadatas);
            case "txt" -> txtReader.read(resource, metadatas);
            // 不支持的文件格式，直接抛出业务异常
            default -> throw new BizException(ResponseCodeEnum.UNSUPPORTED_FILE_TYPE);
        };
    }
}

