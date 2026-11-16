package com.milanai.robot.controller;

import com.milanai.robot.config.SecurityConfig;
import com.milanai.robot.domain.mapper.UserMapper;
import com.milanai.robot.service.DatabaseUserDetailsService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AuthController.class)
@Import(SecurityConfig.class)
@ActiveProfiles("dev")
class EnterpriseOnlyRuntimeIntegrationTest {
    @Autowired private MockMvc mvc;
    @MockitoBean private AuthenticationManager authenticationManager;
    @MockitoBean private DatabaseUserDetailsService userDetailsService;
    @MockitoBean private UserMapper users;

    @Test
    void legacyProfileNameCannotRemoveTheEnterpriseLoginContract() throws Exception {
        mvc.perform(get("/auth/csrf"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.token").isNotEmpty())
                .andExpect(jsonPath("$.data.headerName").value("X-CSRF-TOKEN"));
    }

    @Test
    void legacyProfileNameCannotOpenBusinessResourcesToAnonymousUsers() throws Exception {
        mvc.perform(get("/chat/private-conversation"))
                .andExpect(status().isUnauthorized());
    }
}
