package com.milanai.robot.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.milanai.robot.domain.dos.EnterpriseUserDetails;
import com.milanai.robot.domain.dos.UserDO;
import com.milanai.robot.domain.mapper.UserMapper;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserAdminServiceTest {
    private final UserMapper users = mock(UserMapper.class);
    private final KnowledgeAccessService access = mock(KnowledgeAccessService.class);
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
    private final UserAdminService service = new UserAdminService(users, access, encoder);

    @Test
    void createsOnlyNormalUserAndStoresBcryptHash() {
        when(users.selectCount(any(Wrapper.class))).thenReturn(0L);
        when(users.insert(any(UserDO.class))).thenAnswer(invocation -> {
            ((UserDO) invocation.getArgument(0)).setId(23L);
            return 1;
        });

        var result = service.create("it.reader", "Initial-password-23", "IT Reader");

        var inserted = org.mockito.ArgumentCaptor.forClass(UserDO.class);
        verify(users).insert(inserted.capture());
        assertThat(result.id()).isEqualTo(23L);
        assertThat(inserted.getValue().getRole()).isEqualTo("USER");
        assertThat(inserted.getValue().getEnabled()).isTrue();
        assertThat(encoder.matches("Initial-password-23", inserted.getValue().getPasswordHash())).isTrue();
    }

    @Test
    void rejectsDuplicateUsernameWithoutInserting() {
        when(users.selectCount(any(Wrapper.class))).thenReturn(1L);

        assertThatThrownBy(() -> service.create("existing", "Initial-password-23", "Existing"))
                .isInstanceOf(ResponseStatusException.class);
        verify(users, never()).insert(any(UserDO.class));
    }

    @Test
    void administratorCannotDisableOwnLogin() {
        EnterpriseUserDetails current = new EnterpriseUserDetails(7L, "admin", "Admin", "ADMIN", "hash", true,
                List.of());
        when(access.currentUser()).thenReturn(current);

        assertThatThrownBy(() -> service.setEnabled(7L, false))
                .isInstanceOf(ResponseStatusException.class);
        verify(users, never()).updateById(any(UserDO.class));
    }
}
