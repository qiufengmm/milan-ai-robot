package com.milanai.robot.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.io.unit.DataSizeUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.google.common.collect.Maps;
import com.milanai.robot.domain.dos.AiCustomerServiceMdStorageDO;
import com.milanai.robot.domain.mapper.AiCustomerServiceMdStorageMapper;
import com.milanai.robot.enums.AiCustomerServiceMdStatusEnum;
import com.milanai.robot.enums.ResponseCodeEnum;
import com.milanai.robot.event.AiCustomerServiceMdUploadedEvent;
import com.milanai.robot.exception.BizException;
import com.milanai.robot.model.vo.customerService.DeleteMarkdownFileReqVO;
import com.milanai.robot.model.vo.customerService.FindMarkdownFilePageListReqVO;
import com.milanai.robot.model.vo.customerService.FindMarkdownFilePageListRspVO;
import com.milanai.robot.model.vo.customerService.UpdateMarkdownFileReqVO;
import com.milanai.robot.service.CustomerService;
import com.milanai.robot.utils.PageResponse;
import com.milanai.robot.utils.Response;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * @Description: AI 客服
 **/
@Service
@Slf4j
public class CustomerServiceImpl implements CustomerService {

    @Value("${customer-service.md-storage-path}")
    private String mdStoragePath;

    @Resource
    private ApplicationEventPublisher eventPublisher; // 注入事件发布器

    @Resource
    private AiCustomerServiceMdStorageMapper aiCustomerServiceMdStorageMapper;

    @Resource
    private VectorStore vectorStore;

    /**
     * 上传 Markdown 问答文件
     *
     * @param file
     * @return
     */
    @Override
    public Response<?> uploadMarkdownFile(MultipartFile file) {
        // 校验文件不能为空
        if (file == null || file.isEmpty()) {
            throw new BizException(ResponseCodeEnum.UPLOAD_FILE_CANT_EMPTY);
        }

        // 获取原始文件名（去除空格）
        String originalFilename = StringUtils.trimToEmpty(file.getOriginalFilename());

        // 验证文件类型，仅支持 Markdown
        if (StringUtils.isBlank(originalFilename) || !isMarkdownFile(originalFilename)) {
            throw new BizException(ResponseCodeEnum.ONLY_SUPPORT_MARKDOWN);
        }

        // 已落盘的文件路径，以及「文件是否已写入」「记录是否已入库」两个进度标记，
        // 用于在后续步骤失败时判断要不要清理文件
        Path targetPath = null;
        boolean stored = false;
        boolean inserted = false;

        try {
            // 重新生成文件名 (防止文件名冲突导致覆盖)
            String newFilename = UUID.randomUUID().toString() + "-" + originalFilename;

            // 构建存储路径
            Path storageDirectory = Paths.get(mdStoragePath);
            targetPath = storageDirectory.resolve(newFilename);

            // 确保目录存在
            Files.createDirectories(storageDirectory);

            // 保存文件
            file.transferTo(targetPath.toFile());
            stored = true;

            // 记录操作日志
            log.info("## Markdown 问答文件存储成功, 文件名：{} -> 存储路径：{}", originalFilename, targetPath);

            // 存储入库
            AiCustomerServiceMdStorageDO aiCustomerServiceMdStorageDO = AiCustomerServiceMdStorageDO.builder()
                    .originalFileName(originalFilename)
                    .newFileName(newFilename)
                    .filePath(targetPath.toString())
                    .fileSize(file.getSize())
                    .status(AiCustomerServiceMdStatusEnum.PENDING.getCode())
                    .createTime(LocalDateTime.now())
                    .updateTime(LocalDateTime.now())
                    .build();

            aiCustomerServiceMdStorageMapper.insert(aiCustomerServiceMdStorageDO);
            inserted = true;

            // 获取主键 ID
            Long id =  aiCustomerServiceMdStorageDO.getId();

            // 元数据
            Map<String, Object> metadatas = Maps.newHashMap();
            metadatas.put("mdStorageId", id); // 关联的文件存储表主键 ID
            metadatas.put("originalFileName", originalFilename); // 文件原始名称

            // 发布事件
            eventPublisher.publishEvent(AiCustomerServiceMdUploadedEvent.builder()
                    .id(id)
                    .filePath(targetPath.toString())
                    .metadatas(metadatas)
                    .build());


            // 把新记录 ID 一并返回，方便前端后续按需使用
            return Response.success(id);

        } catch (Exception e) {
            // 只有「文件已落盘、但记录还没入库」时才删文件。
            // 若记录已经入库，文件是被引用的，此时删掉会让数据库记录指向一个不存在的文件；
            // 这种残留记录由删除接口负责清理（支持强制删除）。
            if (stored && !inserted) {
                deleteQuietly(targetPath);
            }

            log.error("## Markdown 问答文件上传失败：{}", originalFilename, e);

            if (e instanceof BizException bizException) {
                throw bizException;
            }
            throw new BizException(ResponseCodeEnum.UPLOAD_FILE_FAILED);
        }
    }

    /**
     * 静默删除文件：失败只记录日志，不向上抛异常。
     * 用于清理上传流程中途失败时留下的孤儿文件。
     *
     * @param path 文件路径，允许为 null
     */
    private void deleteQuietly(Path path) {
        if (path == null || !Files.exists(path)) {
            return;
        }

        try {
            FileUtils.forceDelete(path.toFile());
            log.warn("## 已清理上传失败留下的文件：{}", path);
        } catch (IOException e) {
            log.error("## 清理上传失败留下的文件出错：{}", path, e);
        }
    }

    /**
     * 删除 Markdown 问答文件
     *
     * @param deleteMarkdownFileReqVO
     * @return
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Response<?> deleteMarkdownFile(DeleteMarkdownFileReqVO deleteMarkdownFileReqVO) {
        // 文件记录 ID
        Long id = deleteMarkdownFileReqVO.getId();

        // 查询该文件记录
        AiCustomerServiceMdStorageDO aiCustomerServiceMdStorageDO = aiCustomerServiceMdStorageMapper.selectById(id);

        // 若记录不存在
        if (Objects.isNull(aiCustomerServiceMdStorageDO)) {
            throw new BizException(ResponseCodeEnum.MARKDOWN_FILE_NOT_FOUND);
        }

        // 正在处理中的文件，无法删除
        // 传了 force=true 时跳过此限制，用于清理因进程中断而永久卡在「向量化中」的僵死记录
        boolean force = Boolean.TRUE.equals(deleteMarkdownFileReqVO.getForce());
        AiCustomerServiceMdStatusEnum statusEnum = AiCustomerServiceMdStatusEnum.codeOf(aiCustomerServiceMdStorageDO.getStatus());
        if (!force
                && (Objects.equals(statusEnum, AiCustomerServiceMdStatusEnum.PENDING) // 待向量化
                || Objects.equals(statusEnum, AiCustomerServiceMdStatusEnum.VECTORIZING))) { // 向量化中...
            throw new BizException(ResponseCodeEnum.MARKDOWN_FILE_CANT_DELETE);
        }

        if (force && (Objects.equals(statusEnum, AiCustomerServiceMdStatusEnum.PENDING)
                || Objects.equals(statusEnum, AiCustomerServiceMdStatusEnum.VECTORIZING))) {
            log.warn("## 强制删除处理中的 Markdown 问答文件, id: {}, 状态: {}", id, statusEnum);
        }

        // 删除文件表记录
        aiCustomerServiceMdStorageMapper.deleteById(id);

        // 删除向量化数据
        vectorStore.delete(String.format("mdStorageId == %s", id));

        // 删除本地文件
        String filePath = aiCustomerServiceMdStorageDO.getFilePath();
        try {
            FileUtils.forceDelete(new File(filePath));
        } catch (IOException e) {
            log.error("## Markdown 问答文件删除失败：", e);
        }

        return Response.success();
    }

    /**
     * 验证文件是否为 Markdown 格式
     */
    private boolean isMarkdownFile(String filename) {
        if (StringUtils.isBlank(filename)) {
            return false;
        }

        // 获取文件扩展名
        String extension = FilenameUtils.getExtension(filename);
        return StringUtils.equalsIgnoreCase(extension, "md");
    }

    /**
     * 分页查询 Markdown 问答文件
     *
     * @param findMarkdownFilePageListReqVO
     * @return
     */
    @Override
    public PageResponse<FindMarkdownFilePageListRspVO> findMarkdownFilePageList(FindMarkdownFilePageListReqVO findMarkdownFilePageListReqVO) {
        // 获取当前页、以及每页需要展示的数据数量
        Long current = findMarkdownFilePageListReqVO.getCurrent();
        Long size = findMarkdownFilePageListReqVO.getSize();

        // 文件名称（模糊查询）
        String fileName = findMarkdownFilePageListReqVO.getFileName();
        // 起始结束日期
        LocalDate startDate = findMarkdownFilePageListReqVO.getStartDate();
        LocalDate endDate = findMarkdownFilePageListReqVO.getEndDate();

        // 结束日期加一天，确保结束日期包含当天数据
        if (Objects.nonNull(endDate)) {
            endDate = endDate.plusDays(1);
        }

        // 执行分页查询
        Page<AiCustomerServiceMdStorageDO> mdStorageDOPage = aiCustomerServiceMdStorageMapper
                .selectPageList(current, size, fileName, startDate, endDate);

        List<AiCustomerServiceMdStorageDO> mdStorageDOS = mdStorageDOPage.getRecords();
        // DO 转 VO
        List<FindMarkdownFilePageListRspVO> vos = null;
        if (CollUtil.isNotEmpty(mdStorageDOS)) {
            vos = mdStorageDOS.stream()
                    .map(mdStorageDO -> FindMarkdownFilePageListRspVO.builder() // 构建返参 VO 实体类
                            .id(mdStorageDO.getId())
                            .originalFileName(mdStorageDO.getOriginalFileName())
                            .fileSize(DataSizeUtil.format(mdStorageDO.getFileSize())) // Hutool 工具库提供的字节转换
                            .status(mdStorageDO.getStatus())
                            .createTime(mdStorageDO.getCreateTime())
                            .updateTime(mdStorageDO.getUpdateTime())
                            .remark(mdStorageDO.getRemark())
                            .build())
                    .collect(Collectors.toList());
        }

        return PageResponse.success(mdStorageDOPage, vos);
    }

    /**
     * 修改  Markdown 问答文件信息
     *
     * @param updateMarkdownFileReqVO
     * @return
     */
    @Override
    public Response<?> updateMarkdownFile(UpdateMarkdownFileReqVO updateMarkdownFileReqVO) {
        // 文件 ID
        Long id = updateMarkdownFileReqVO.getId();
        // 备注：允许为空，null 统一转为空串，这样已有备注也能被清空
        String remark = StringUtils.trimToEmpty(updateMarkdownFileReqVO.getRemark());

        // 根据 ID 修改备注信息
        // 这里用 LambdaUpdateWrapper 显式 set，确保备注传空串时也能真正写进数据库；
        // 若用 updateById + 实体对象，null 字段会被 MyBatis-Plus 的默认更新策略忽略掉。
        int count = aiCustomerServiceMdStorageMapper.update(null, Wrappers.<AiCustomerServiceMdStorageDO>lambdaUpdate()
                .set(AiCustomerServiceMdStorageDO::getRemark, remark)
                .set(AiCustomerServiceMdStorageDO::getUpdateTime, LocalDateTime.now())
                .eq(AiCustomerServiceMdStorageDO::getId, id));

        // 若影响的行数为 0， 说明该文件记录不存在
        if (count == 0) {
            throw new BizException(ResponseCodeEnum.MARKDOWN_FILE_NOT_FOUND);
        }

        return Response.success();
    }

}

