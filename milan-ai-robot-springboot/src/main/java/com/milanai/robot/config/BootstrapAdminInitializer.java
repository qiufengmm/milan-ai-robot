package com.milanai.robot.config;

import com.milanai.robot.domain.dos.UserDO;
import com.milanai.robot.domain.mapper.UserMapper;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Component
public class BootstrapAdminInitializer implements ApplicationRunner {
    private final UserMapper users;
    private final PasswordEncoder passwordEncoder;
    private final BootstrapAdminProperties properties;

    public BootstrapAdminInitializer(UserMapper users, PasswordEncoder passwordEncoder,
                                     BootstrapAdminProperties properties) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!properties.isEnabled()) {
            return;
        }
        if (users.selectCount(null) != 0) {
            return;
        }
        validateConfiguration();
        LocalDateTime now = LocalDateTime.now();
        users.insert(UserDO.builder()
                .username(properties.getUsername().trim())
                .passwordHash(passwordEncoder.encode(properties.getPassword()))
                .displayName(properties.getDisplayName().trim())
                .role("ADMIN")
                .enabled(true)
                .createTime(now)
                .updateTime(now)
                .build());
    }

    private void validateConfiguration() {
        if (properties.getUsername() == null || properties.getUsername().isBlank()) {
            throw new IllegalStateException("enterprise.bootstrap-admin.username must be set when bootstrap is enabled");
        }
        if (properties.getDisplayName() == null || properties.getDisplayName().isBlank()) {
            throw new IllegalStateException("enterprise.bootstrap-admin.display-name must be set when bootstrap is enabled");
        }
        if (properties.getPassword() == null || properties.getPassword().length() < 12
                || properties.getPassword().getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72) {
            throw new IllegalStateException("enterprise.bootstrap-admin.password must be 12-72 UTF-8 bytes");
        }
    }
}
