package com.milanai.robot.config;

import com.milanai.robot.domain.dos.UserDO;
import com.milanai.robot.domain.mapper.UserMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BootstrapAdminInitializerTest {
    private final UserMapper users = mock(UserMapper.class);
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
    private final BootstrapAdminProperties properties = new BootstrapAdminProperties();

    @Test
    void disabledBootstrapDoesNotReadOrWriteUserTable() {
        BootstrapAdminInitializer initializer = initializer();

        initializer.run(new DefaultApplicationArguments());

        verify(users, never()).selectCount(any());
        verify(users, never()).insert(any(UserDO.class));
    }

    @Test
    void existingAccountPreventsRebootstrapAndDoesNotRequireBootstrapSecret() {
        properties.setEnabled(true);
        when(users.selectCount(any())).thenReturn(1L);

        initializer().run(new DefaultApplicationArguments());

        verify(users).selectCount(any());
        verify(users, never()).insert(any(UserDO.class));
    }

    @Test
    void emptyUserTableGetsOneBcryptAdminOnlyWhenExplicitlyEnabled() {
        properties.setEnabled(true);
        properties.setUsername("admin");
        properties.setDisplayName("系统管理员");
        properties.setPassword("local-only-passphrase");
        when(users.selectCount(any())).thenReturn(0L);

        initializer().run(new DefaultApplicationArguments());

        var inserted = org.mockito.ArgumentCaptor.forClass(UserDO.class);
        verify(users).insert(inserted.capture());
        assertThat(inserted.getValue().getUsername()).isEqualTo("admin");
        assertThat(inserted.getValue().getRole()).isEqualTo("ADMIN");
        assertThat(inserted.getValue().getEnabled()).isTrue();
        assertThat(inserted.getValue().getPasswordHash()).startsWith("$2");
        assertThat(encoder.matches("local-only-passphrase", inserted.getValue().getPasswordHash())).isTrue();
    }

    @Test
    void emptyUserTableRejectsEnabledBootstrapWithoutSecurePassword() {
        properties.setEnabled(true);
        properties.setUsername("admin");
        properties.setDisplayName("管理员");
        properties.setPassword("short");
        when(users.selectCount(any())).thenReturn(0L);

        assertThatThrownBy(() -> initializer().run(new DefaultApplicationArguments()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("12-72");
        verify(users, never()).insert(any(UserDO.class));
    }

    private BootstrapAdminInitializer initializer() {
        return new BootstrapAdminInitializer(users, encoder, properties);
    }
}
