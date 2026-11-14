package com.milanai.robot.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.milanai.robot.domain.dos.KnowledgeBaseDO;
import com.milanai.robot.domain.mapper.KnowledgeBaseMapper;
import com.milanai.robot.enums.ResponseCodeEnum;
import com.milanai.robot.exception.BizException;
import com.milanai.robot.model.vo.customerService.KnowledgeBaseReqVO;
import org.springframework.stereotype.Service;
import java.time.LocalDateTime;
import java.util.List;

@Service
public class KnowledgeBaseService {
    private final KnowledgeBaseMapper mapper;
    public KnowledgeBaseService(KnowledgeBaseMapper mapper) { this.mapper = mapper; }

    public static long resolveId(Long id) {
        if (id == null) return 1L;
        if (id <= 0) throw new BizException(ResponseCodeEnum.PARAM_NOT_VALID);
        return id;
    }

    public KnowledgeBaseDO require(Long id) {
        KnowledgeBaseDO result = mapper.selectById(resolveId(id));
        if (result == null) throw new BizException(ResponseCodeEnum.KNOWLEDGE_BASE_NOT_FOUND);
        return result;
    }

    public List<KnowledgeBaseDO> list() {
        return mapper.selectList(Wrappers.<KnowledgeBaseDO>lambdaQuery().orderByAsc(KnowledgeBaseDO::getId));
    }

    public long create(KnowledgeBaseReqVO request) {
        validate(request);
        KnowledgeBaseDO row = KnowledgeBaseDO.builder().name(request.getName().trim())
                .description(request.getDescription() == null ? "" : request.getDescription().trim())
                .createTime(LocalDateTime.now()).updateTime(LocalDateTime.now()).build();
        mapper.insert(row);
        return row.getId();
    }

    public void update(Long id, KnowledgeBaseReqVO request) {
        long resolvedId = resolveId(id);
        require(resolvedId);
        validate(request);
        mapper.updateById(KnowledgeBaseDO.builder().id(resolvedId).name(request.getName().trim())
                .description(request.getDescription() == null ? "" : request.getDescription().trim())
                .updateTime(LocalDateTime.now()).build());
    }

    private void validate(KnowledgeBaseReqVO request) {
        if (request == null || request.getName() == null || request.getName().isBlank()
                || request.getName().length() > 100
                || (request.getDescription() != null && request.getDescription().length() > 500)) {
            throw new BizException(ResponseCodeEnum.PARAM_NOT_VALID);
        }
    }
}
