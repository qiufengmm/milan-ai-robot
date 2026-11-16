package com.milanai.robot.controller;

import com.milanai.robot.domain.dos.EnterpriseUserDetails;
import com.milanai.robot.utils.Response;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/auth")
public class AuthController {
    private final AuthenticationManager authenticationManager;
    private final SessionAuthenticationStrategy sessionAuthenticationStrategy;
    private final HttpSessionSecurityContextRepository securityContextRepository = new HttpSessionSecurityContextRepository();
    private final CsrfTokenRepository csrfTokenRepository = new org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository();

    public AuthController(AuthenticationManager authenticationManager,
                          SessionAuthenticationStrategy sessionAuthenticationStrategy) {
        this.authenticationManager = authenticationManager;
        this.sessionAuthenticationStrategy = sessionAuthenticationStrategy;
    }

    @GetMapping("/csrf")
    public Response<CsrfResponse> csrf(HttpServletRequest request) {
        CsrfToken token = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
        if (token == null) {
            token = (CsrfToken) request.getAttribute("_csrf");
        }
        if (token == null) {
            throw new IllegalStateException("CSRF token is unavailable");
        }
        return Response.success(new CsrfResponse(token.getHeaderName(), token.getToken()));
    }

    @PostMapping("/login")
    public ResponseEntity<Response<CurrentUserResponse>> login(@Valid @RequestBody LoginRequest body,
                                                                HttpServletRequest request,
                                                                HttpServletResponse response) {
        try {
            Authentication authentication = authenticationManager.authenticate(
                    UsernamePasswordAuthenticationToken.unauthenticated(body.username(), body.password()));
            if (!(authentication.getPrincipal() instanceof EnterpriseUserDetails user)) {
                throw new AuthenticationServiceException("Unsupported authenticated principal");
            }
            sessionAuthenticationStrategy.onAuthentication(authentication, request, response);
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(authentication);
            SecurityContextHolder.setContext(context);
            securityContextRepository.saveContext(context, request, response);
            csrfTokenRepository.saveToken(null, request, response);
            return ResponseEntity.ok(Response.success(toResponse(user)));
        } catch (AuthenticationException exception) {
            SecurityContextHolder.clearContext();
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Response.fail("AUTHENTICATION_FAILED", "账号或密码错误，或账号已停用"));
        }
    }

    @PostMapping("/logout")
    public Response<Void> logout(HttpServletRequest request, HttpServletResponse response) {
        csrfTokenRepository.saveToken(null, request, response);
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        SecurityContextHolder.clearContext();
        return Response.success();
    }

    @GetMapping("/me")
    public Response<CurrentUserResponse> me(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof EnterpriseUserDetails user)) {
            throw new AuthenticationServiceException("Authentication is required");
        }
        return Response.success(toResponse(user));
    }

    private CurrentUserResponse toResponse(EnterpriseUserDetails user) {
        return new CurrentUserResponse(user.id(), user.username(), user.displayName(), user.role());
    }

    public record LoginRequest(@NotBlank String username, @NotBlank String password) {}
    public record CurrentUserResponse(Long id, String username, String displayName, String role) {}
    public record CsrfResponse(String headerName, String token) {}
}
