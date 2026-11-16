package com.milanai.robot.controller;

import com.milanai.robot.config.SecurityConfig;
import com.milanai.robot.domain.dos.EnterpriseUserDetails;
import com.milanai.robot.domain.dos.UserDO;
import com.milanai.robot.domain.mapper.UserMapper;
import com.milanai.robot.service.DatabaseUserDetailsService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.assertj.core.api.Assertions.assertThat;

@WebMvcTest(AuthController.class)
@Import(SecurityConfig.class)
@ActiveProfiles("enterprise")
class AuthenticationIntegrationTest {
    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private AuthenticationManager authenticationManager;

    @MockitoBean
    private DatabaseUserDetailsService userDetailsService;

    @MockitoBean
    private UserMapper users;

    @Test
    void protectedIdentityEndpointRejectsAnonymousRequests() throws Exception {
        mvc.perform(get("/auth/me"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void loginRequiresCsrfToken() throws Exception {
        mvc.perform(post("/auth/login")
                        .contentType("application/json")
                        .content("{\"username\":\"hr\",\"password\":\"secret\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void successfulLoginPersistsIdentityInSession() throws Exception {
        when(users.selectById(7L)).thenReturn(enabledTestUser());
        EnterpriseUserDetails user = new EnterpriseUserDetails(
                7L, "hr", "HR Editor", "USER", "{noop}secret", true,
                List.of(new SimpleGrantedAuthority("ROLE_USER")));
        Authentication authenticated = new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities());
        when(authenticationManager.authenticate(any(Authentication.class))).thenReturn(authenticated);

        var login = mvc.perform(post("/auth/login")
                        .with(csrf())
                        .contentType("application/json")
                        .content("{\"username\":\"hr\",\"password\":\"secret\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.username").value("hr"))
                .andReturn();

        mvc.perform(get("/auth/me").session((org.springframework.mock.web.MockHttpSession) login.getRequest().getSession(false)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.displayName").value("HR Editor"));
    }

    @Test
    void successfulLoginRotatesAnExistingAnonymousSessionId() throws Exception {
        EnterpriseUserDetails user = new EnterpriseUserDetails(
                7L, "hr", "HR Editor", "USER", "{noop}secret", true,
                List.of(new SimpleGrantedAuthority("ROLE_USER")));
        when(authenticationManager.authenticate(any(Authentication.class)))
                .thenReturn(new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities()));
        var initial = mvc.perform(get("/auth/csrf")).andReturn();
        var session = (org.springframework.mock.web.MockHttpSession) initial.getRequest().getSession(false);
        String anonymousSessionId = session.getId();

        var login = mvc.perform(post("/auth/login").session(session).with(csrf())
                        .contentType("application/json")
                        .content("{\"username\":\"hr\",\"password\":\"secret\"}"))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(login.getRequest().getSession(false).getId()).isNotEqualTo(anonymousSessionId);
    }

    @Test
    void logoutClearsSessionIdentity() throws Exception {
        when(users.selectById(7L)).thenReturn(enabledTestUser());
        EnterpriseUserDetails user = new EnterpriseUserDetails(
                7L, "hr", "HR Editor", "USER", "{noop}secret", true,
                List.of(new SimpleGrantedAuthority("ROLE_USER")));
        when(authenticationManager.authenticate(any(Authentication.class)))
                .thenReturn(new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities()));
        var login = mvc.perform(post("/auth/login").with(csrf())
                        .contentType("application/json")
                        .content("{\"username\":\"hr\",\"password\":\"secret\"}"))
                .andReturn();
        var session = (org.springframework.mock.web.MockHttpSession) login.getRequest().getSession(false);

        mvc.perform(post("/auth/logout").session(session).with(csrf()))
                .andExpect(status().isOk());
        mvc.perform(get("/auth/me").session(session))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void disablingAccountInvalidatesAlreadyAuthenticatedSession() throws Exception {
        EnterpriseUserDetails user = new EnterpriseUserDetails(
                7L, "hr", "HR Editor", "USER", "{noop}secret", true,
                List.of(new SimpleGrantedAuthority("ROLE_USER")));
        when(authenticationManager.authenticate(any(Authentication.class)))
                .thenReturn(new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities()));
        var login = mvc.perform(post("/auth/login").with(csrf())
                        .contentType("application/json")
                        .content("{\"username\":\"hr\",\"password\":\"secret\"}"))
                .andReturn();
        var session = (org.springframework.mock.web.MockHttpSession) login.getRequest().getSession(false);
        when(users.selectById(7L)).thenReturn(UserDO.builder().id(7L).enabled(false).build());

        mvc.perform(get("/auth/me").session(session))
                .andExpect(status().isUnauthorized());
    }

    private UserDO enabledTestUser() {
        return UserDO.builder().id(7L).username("hr").displayName("HR Editor")
                .role("USER").enabled(true).build();
    }
}
