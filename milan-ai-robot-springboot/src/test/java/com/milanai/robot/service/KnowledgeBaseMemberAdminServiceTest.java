package com.milanai.robot.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.milanai.robot.domain.dos.KnowledgeBaseDO;
import com.milanai.robot.domain.dos.KnowledgeBaseMemberDO;
import com.milanai.robot.domain.dos.UserDO;
import com.milanai.robot.domain.mapper.KnowledgeBaseMapper;
import com.milanai.robot.domain.mapper.KnowledgeBaseMemberMapper;
import com.milanai.robot.domain.mapper.UserMapper;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgeBaseMemberAdminServiceTest {
    private final UserMapper users = mock(UserMapper.class);
    private final KnowledgeBaseMapper knowledgeBases = mock(KnowledgeBaseMapper.class);
    private final KnowledgeBaseMemberMapper members = mock(KnowledgeBaseMemberMapper.class);
    private final KnowledgeBaseMemberAdminService service =
            new KnowledgeBaseMemberAdminService(users, knowledgeBases, members);

    @Test
    void grantsEditorOnlyToExistingEnabledUserAndExistingLibrary() {
        when(users.selectById(8L)).thenReturn(UserDO.builder().id(8L).enabled(true).build());
        when(knowledgeBases.selectById(2L)).thenReturn(KnowledgeBaseDO.builder().id(2L).build());
        when(members.selectOne(any(Wrapper.class))).thenReturn(null);

        service.assign(8L, 2L, "EDITOR");

        verify(members).insert(any(KnowledgeBaseMemberDO.class));
    }

    @Test
    void rejectsMembershipForDisabledAccount() {
        when(users.selectById(8L)).thenReturn(UserDO.builder().id(8L).enabled(false).build());

        assertThatThrownBy(() -> service.assign(8L, 2L, "READER"))
                .isInstanceOf(ResponseStatusException.class);
        verify(members, never()).insert(any(KnowledgeBaseMemberDO.class));
    }

    @Test
    void updatesExistingMembershipInsteadOfDuplicatingIt() {
        when(users.selectById(8L)).thenReturn(UserDO.builder().id(8L).enabled(true).build());
        when(knowledgeBases.selectById(2L)).thenReturn(KnowledgeBaseDO.builder().id(2L).build());
        when(members.selectOne(any(Wrapper.class))).thenReturn(KnowledgeBaseMemberDO.builder()
                .id(55L).userId(8L).knowledgeBaseId(2L).accessLevel("READER").build());

        service.assign(8L, 2L, "EDITOR");

        verify(members).updateById(any(KnowledgeBaseMemberDO.class));
        verify(members, never()).insert(any(KnowledgeBaseMemberDO.class));
    }
}
