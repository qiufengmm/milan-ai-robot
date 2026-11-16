package com.milanai.robot.controller;

import com.milanai.robot.config.SecurityConfig;
import com.milanai.robot.domain.mapper.UserMapper;
import com.milanai.robot.service.KnowledgeAccessService;
import com.milanai.robot.service.DatabaseUserDetailsService;
import com.milanai.robot.service.UserAdminService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(UserAdminController.class)
@Import(SecurityConfig.class)
@ActiveProfiles("enterprise")
class UserAdminControllerSecurityTest {
    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private UserAdminService users;

    @MockitoBean
    private KnowledgeAccessService access;

    @MockitoBean
    private UserMapper userMapper;

    @MockitoBean
    private DatabaseUserDetailsService databaseUserDetailsService;

    @Test
    void anonymousCannotReadAccountAdministration() throws Exception {
        mvc.perform(get("/admin/users"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void regularAccountCannotReadAccountAdministration() throws Exception {
        doThrow(new AccessDeniedException("admin only")).when(access).requireAdministrator();

        mvc.perform(get("/admin/users").with(user("employee").roles("USER")))
                .andExpect(status().isForbidden());
    }
}
