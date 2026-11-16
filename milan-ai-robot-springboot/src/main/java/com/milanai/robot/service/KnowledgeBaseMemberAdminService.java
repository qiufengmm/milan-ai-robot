package com.milanai.robot.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.milanai.robot.domain.dos.KnowledgeBaseMemberDO;
import com.milanai.robot.domain.mapper.KnowledgeBaseMapper;
import com.milanai.robot.domain.mapper.KnowledgeBaseMemberMapper;
import com.milanai.robot.domain.mapper.UserMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class KnowledgeBaseMemberAdminService {
    private final UserMapper users;
    private final KnowledgeBaseMapper knowledgeBases;
    private final KnowledgeBaseMemberMapper members;

    public KnowledgeBaseMemberAdminService(UserMapper users, KnowledgeBaseMapper knowledgeBases,
                                           KnowledgeBaseMemberMapper members) {
        this.users = users;
        this.knowledgeBases = knowledgeBases;
        this.members = members;
    }

    public List<MemberSummary> list(Long knowledgeBaseId) {
        requireLibrary(knowledgeBaseId);
        return members.selectList(Wrappers.<KnowledgeBaseMemberDO>lambdaQuery()
                        .eq(KnowledgeBaseMemberDO::getKnowledgeBaseId, knowledgeBaseId)
                        .orderByAsc(KnowledgeBaseMemberDO::getUserId))
                .stream().map(row -> new MemberSummary(row.getUserId(), row.getKnowledgeBaseId(), row.getAccessLevel()))
                .toList();
    }

    @Transactional
    public void assign(Long userId, Long knowledgeBaseId, String accessLevel) {
        if (!"READER".equals(accessLevel) && !"EDITOR".equals(accessLevel)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "权限只能是 READER 或 EDITOR");
        }
        var user = users.selectById(userId);
        if (user == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "账号不存在");
        }
        if (!Boolean.TRUE.equals(user.getEnabled())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "不能授权已停用账号");
        }
        requireLibrary(knowledgeBaseId);
        KnowledgeBaseMemberDO member = members.selectOne(Wrappers.<KnowledgeBaseMemberDO>lambdaQuery()
                .eq(KnowledgeBaseMemberDO::getUserId, userId)
                .eq(KnowledgeBaseMemberDO::getKnowledgeBaseId, knowledgeBaseId));
        if (member == null) {
            members.insert(KnowledgeBaseMemberDO.builder().userId(userId).knowledgeBaseId(knowledgeBaseId)
                    .accessLevel(accessLevel).createTime(LocalDateTime.now()).build());
        } else {
            member.setAccessLevel(accessLevel);
            members.updateById(member);
        }
    }

    @Transactional
    public void revoke(Long userId, Long knowledgeBaseId) {
        requireLibrary(knowledgeBaseId);
        members.delete(Wrappers.<KnowledgeBaseMemberDO>lambdaQuery()
                .eq(KnowledgeBaseMemberDO::getUserId, userId)
                .eq(KnowledgeBaseMemberDO::getKnowledgeBaseId, knowledgeBaseId));
    }

    private void requireLibrary(Long knowledgeBaseId) {
        if (knowledgeBaseId == null || knowledgeBases.selectById(knowledgeBaseId) == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "知识库不存在");
        }
    }

    public record MemberSummary(Long userId, Long knowledgeBaseId, String accessLevel) {}
}
