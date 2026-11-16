package com.milanai.robot.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.milanai.robot.domain.dos.EnterpriseUserDetails;
import com.milanai.robot.domain.dos.UserDO;
import com.milanai.robot.domain.mapper.UserMapper;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;

@Service
public class DatabaseUserDetailsService implements UserDetailsService {
    private final UserMapper users;

    public DatabaseUserDetailsService(UserMapper users) {
        this.users = users;
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        UserDO user = users.selectOne(Wrappers.<UserDO>lambdaQuery()
                .eq(UserDO::getUsername, username));
        if (user == null) {
            throw new UsernameNotFoundException("账号或密码错误");
        }
        String role = user.getRole().toUpperCase(Locale.ROOT);
        return new EnterpriseUserDetails(user.getId(), user.getUsername(), user.getDisplayName(), role,
                user.getPasswordHash(), Boolean.TRUE.equals(user.getEnabled()),
                List.of(new SimpleGrantedAuthority("ROLE_" + role)));
    }
}
