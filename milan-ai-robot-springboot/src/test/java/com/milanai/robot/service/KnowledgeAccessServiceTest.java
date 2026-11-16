package com.milanai.robot.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.milanai.robot.domain.dos.EnterpriseUserDetails;
import com.milanai.robot.domain.dos.KnowledgeBaseDO;
import com.milanai.robot.domain.dos.KnowledgeBaseMemberDO;
import com.milanai.robot.domain.mapper.KnowledgeBaseMapper;
import com.milanai.robot.domain.mapper.KnowledgeBaseMemberMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class KnowledgeAccessServiceTest {
    private final KnowledgeBaseMemberMapper members = mock(KnowledgeBaseMemberMapper.class);
    private final KnowledgeBaseMapper knowledgeBases = mock(KnowledgeBaseMapper.class);
    private final KnowledgeAccessService access = new KnowledgeAccessService(members, knowledgeBases);

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void editorCanReadAndWriteOnlyAssignedLibrary() {
        authenticate("editor", "USER");
        when(members.selectOne(any(Wrapper.class))).thenReturn(KnowledgeBaseMemberDO.builder()
                .userId(7L).knowledgeBaseId(3L).accessLevel("EDITOR").build(),
                KnowledgeBaseMemberDO.builder().userId(7L).knowledgeBaseId(3L).accessLevel("EDITOR").build(),
                null);

        access.requireRead(3L);
        access.requireEdit(3L);

        assertThatThrownBy(() -> access.requireRead(4L)).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void readerCanReadButCannotChangeLibrary() {
        authenticate("reader", "USER");
        when(members.selectOne(any(Wrapper.class))).thenReturn(KnowledgeBaseMemberDO.builder()
                .userId(8L).knowledgeBaseId(3L).accessLevel("READER").build());

        access.requireRead(3L);

        assertThatThrownBy(() -> access.requireEdit(3L)).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void administratorCanManageEveryLibraryWithoutMemberRows() {
        authenticate("admin", "ADMIN");
        when(knowledgeBases.selectList(any(Wrapper.class))).thenReturn(List.of(
                KnowledgeBaseDO.builder().id(1L).name("员工服务").build(),
                KnowledgeBaseDO.builder().id(2L).name("IT 支持").build()));

        access.requireEdit(2L);

        assertThat(access.listAccessibleKnowledgeBases()).extracting(KnowledgeBaseDO::getId)
                .containsExactly(1L, 2L);
    }

    private void authenticate(String username, String role) {
        Long id = "admin".equals(username) ? 1L : 7L;
        var principal = new EnterpriseUserDetails(id, username, username, role, "hash", true,
                List.of(new SimpleGrantedAuthority("ROLE_" + role)));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, "", principal.getAuthorities()));
    }
}
