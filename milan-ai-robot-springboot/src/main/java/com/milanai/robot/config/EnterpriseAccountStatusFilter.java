package com.milanai.robot.config;

import com.milanai.robot.domain.dos.EnterpriseUserDetails;
import com.milanai.robot.domain.dos.UserDO;
import com.milanai.robot.domain.mapper.UserMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

public class EnterpriseAccountStatusFilter extends OncePerRequestFilter {
    private final UserMapper users;

    public EnterpriseAccountStatusFilter(UserMapper users) {
        this.users = users;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated()
                && authentication.getPrincipal() instanceof EnterpriseUserDetails principal) {
            UserDO current = users.selectById(principal.id());
            if (current == null || !Boolean.TRUE.equals(current.getEnabled())) {
                SecurityContextHolder.clearContext();
                var session = request.getSession(false);
                if (session != null) {
                    session.invalidate();
                }
                response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
