package com.milanai.robot.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.milanai.robot.domain.dos.EnterpriseUserDetails;
import com.milanai.robot.domain.dos.UserDO;
import com.milanai.robot.domain.mapper.UserMapper;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DatabaseUserDetailsServiceTest {
    private final UserMapper users = mock(UserMapper.class);
    private final DatabaseUserDetailsService service = new DatabaseUserDetailsService(users);

    @Test
    void loadsCurrentRoleAndDisabledStateFromDatabase() {
        when(users.selectOne(any(Wrapper.class))).thenReturn(UserDO.builder()
                .id(12L).username("it-editor").passwordHash("$2a$hash")
                .displayName("IT Editor").role("USER").enabled(false).build());

        EnterpriseUserDetails principal = (EnterpriseUserDetails) service.loadUserByUsername("it-editor");

        assertThat(principal.id()).isEqualTo(12L);
        assertThat(principal.isEnabled()).isFalse();
        assertThat(principal.getAuthorities()).extracting("authority").containsExactly("ROLE_USER");
    }

    @Test
    void unknownAccountCannotAuthenticate() {
        when(users.selectOne(any(Wrapper.class))).thenReturn(null);

        assertThatThrownBy(() -> service.loadUserByUsername("missing"))
                .isInstanceOf(UsernameNotFoundException.class);
    }
}
