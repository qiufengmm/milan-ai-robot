package com.milanai.robot.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.milanai.robot.domain.dos.EnterpriseUserDetails;
import com.milanai.robot.domain.dos.KnowledgeBaseDO;
import com.milanai.robot.domain.dos.KnowledgeBaseMemberDO;
import com.milanai.robot.domain.mapper.KnowledgeBaseMapper;
import com.milanai.robot.domain.mapper.KnowledgeBaseMemberMapper;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;

@Service
public class KnowledgeAccessService {
    private final KnowledgeBaseMemberMapper members;
    private final KnowledgeBaseMapper knowledgeBases;

    public KnowledgeAccessService(KnowledgeBaseMemberMapper members, KnowledgeBaseMapper knowledgeBases) {
        this.members = members;
        this.knowledgeBases = knowledgeBases;
    }

    public EnterpriseUserDetails currentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof EnterpriseUserDetails user)) {
            throw new AccessDeniedException("需要登录");
        }
        return user;
    }

    public boolean isAdministrator() {
        return "ADMIN".equals(currentUser().role());
    }

    public void requireAdministrator() {
        if (!isAdministrator()) {
            throw new AccessDeniedException("需要管理员权限");
        }
    }

    public void requireRead(Long knowledgeBaseId) {
        requireMembership(knowledgeBaseId, false);
    }

    public void requireEdit(Long knowledgeBaseId) {
        requireMembership(knowledgeBaseId, true);
    }

    public boolean canEdit(Long knowledgeBaseId) {
        try {
            requireEdit(knowledgeBaseId);
            return true;
        } catch (AccessDeniedException exception) {
            return false;
        }
    }

    public List<KnowledgeBaseDO> listAccessibleKnowledgeBases() {
        EnterpriseUserDetails user = currentUser();
        if ("ADMIN".equals(user.role())) {
            return knowledgeBases.selectList(Wrappers.<KnowledgeBaseDO>lambdaQuery()
                            .orderByAsc(KnowledgeBaseDO::getId)).stream()
                    .peek(base -> base.setAccessLevel("ADMIN")).toList();
        }
        List<KnowledgeBaseMemberDO> grants = members.selectList(Wrappers.<KnowledgeBaseMemberDO>lambdaQuery()
                        .eq(KnowledgeBaseMemberDO::getUserId, user.id()))
                .stream().toList();
        List<Long> ids = grants.stream().map(KnowledgeBaseMemberDO::getKnowledgeBaseId).distinct().toList();
        if (ids.isEmpty()) {
            return List.of();
        }
        var levelById = grants.stream().collect(java.util.stream.Collectors.toMap(
                KnowledgeBaseMemberDO::getKnowledgeBaseId, KnowledgeBaseMemberDO::getAccessLevel, (left, right) -> left));
        return knowledgeBases.selectList(Wrappers.<KnowledgeBaseDO>lambdaQuery()
                        .in(KnowledgeBaseDO::getId, ids))
                .stream().peek(base -> base.setAccessLevel(levelById.get(base.getId())))
                .sorted(Comparator.comparing(KnowledgeBaseDO::getId)).toList();
    }

    private void requireMembership(Long knowledgeBaseId, boolean edit) {
        EnterpriseUserDetails user = currentUser();
        if ("ADMIN".equals(user.role())) {
            return;
        }
        if (!"USER".equals(user.role()) || knowledgeBaseId == null || knowledgeBaseId <= 0) {
            throw new AccessDeniedException("无权访问该知识库");
        }
        KnowledgeBaseMemberDO member = members.selectOne(Wrappers.<KnowledgeBaseMemberDO>lambdaQuery()
                .eq(KnowledgeBaseMemberDO::getUserId, user.id())
                .eq(KnowledgeBaseMemberDO::getKnowledgeBaseId, knowledgeBaseId));
        if (member == null || (edit && !"EDITOR".equals(member.getAccessLevel()))) {
            throw new AccessDeniedException(edit ? "需要该知识库的编辑权限" : "无权访问该知识库");
        }
    }
}
