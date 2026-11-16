package com.milanai.robot.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.milanai.robot.domain.dos.UserDO;
import com.milanai.robot.domain.mapper.UserMapper;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.regex.Pattern;

@Service
public class UserAdminService {
    private static final Pattern USERNAME = Pattern.compile("[A-Za-z0-9._-]{3,80}");
    private final UserMapper users;
    private final KnowledgeAccessService access;
    private final PasswordEncoder passwordEncoder;

    public UserAdminService(UserMapper users, KnowledgeAccessService access, PasswordEncoder passwordEncoder) {
        this.users = users;
        this.access = access;
        this.passwordEncoder = passwordEncoder;
    }

    public List<UserSummary> list() {
        return users.selectList(Wrappers.<UserDO>lambdaQuery().orderByAsc(UserDO::getId))
                .stream().map(this::toSummary).toList();
    }

    @Transactional
    public UserSummary create(String username, String password, String displayName) {
        String cleanUsername = username == null ? "" : username.trim();
        String cleanDisplayName = displayName == null ? "" : displayName.trim();
        validate(cleanUsername, password, cleanDisplayName);
        if (users.selectCount(Wrappers.<UserDO>lambdaQuery().eq(UserDO::getUsername, cleanUsername)) > 0) {
            throw conflict("账号名已存在");
        }
        LocalDateTime now = LocalDateTime.now();
        UserDO user = UserDO.builder().username(cleanUsername).passwordHash(passwordEncoder.encode(password))
                .displayName(cleanDisplayName).role("USER").enabled(true).createTime(now).updateTime(now).build();
        try {
            users.insert(user);
        } catch (DataIntegrityViolationException exception) {
            throw conflict("账号名已存在");
        }
        return toSummary(user);
    }

    @Transactional
    public void setEnabled(Long userId, boolean enabled) {
        if (!enabled && access.currentUser().id().equals(userId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "不能停用当前登录的管理员账号");
        }
        UserDO existing = users.selectById(userId);
        if (existing == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "账号不存在");
        }
        existing.setEnabled(enabled);
        existing.setUpdateTime(LocalDateTime.now());
        users.updateById(existing);
    }

    private void validate(String username, String password, String displayName) {
        if (!USERNAME.matcher(username).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "账号名须为 3-80 位英文字母、数字、点、横线或下划线");
        }
        int passwordBytes = password == null ? 0 : password.getBytes(StandardCharsets.UTF_8).length;
        if (passwordBytes < 12 || passwordBytes > 72) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "初始密码须为 12-72 个 UTF-8 字节");
        }
        if (displayName.isBlank() || displayName.length() > 120) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "显示名称不能为空且不能超过 120 字符");
        }
    }

    private UserSummary toSummary(UserDO user) {
        return new UserSummary(user.getId(), user.getUsername(), user.getDisplayName(), user.getRole(), user.getEnabled());
    }

    private ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }

    public record UserSummary(Long id, String username, String displayName, String role, Boolean enabled) {}
}
