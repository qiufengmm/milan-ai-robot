package com.milanai.robot.controller;

import com.milanai.robot.service.KnowledgeAccessService;
import com.milanai.robot.service.UserAdminService;
import com.milanai.robot.utils.Response;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/admin/users")
public class UserAdminController {
    private final UserAdminService users;
    private final KnowledgeAccessService access;

    public UserAdminController(UserAdminService users, KnowledgeAccessService access) {
        this.users = users;
        this.access = access;
    }

    @GetMapping
    public Response<List<UserAdminService.UserSummary>> list() {
        access.requireAdministrator();
        return Response.success(users.list());
    }

    @PostMapping
    public Response<UserAdminService.UserSummary> create(@Valid @RequestBody CreateUserRequest request) {
        access.requireAdministrator();
        return Response.success(users.create(request.username(), request.password(), request.displayName()));
    }

    @PatchMapping("/{userId}/enabled")
    public Response<Void> setEnabled(@PathVariable Long userId, @Valid @RequestBody SetEnabledRequest request) {
        access.requireAdministrator();
        users.setEnabled(userId, request.enabled());
        return Response.success();
    }

    public record CreateUserRequest(
            @NotBlank @Pattern(regexp = "[A-Za-z0-9._-]{3,80}") String username,
            @NotBlank @Size(min = 12, max = 72) String password,
            @NotBlank @Size(max = 120) String displayName) {}

    public record SetEnabledRequest(@NotNull Boolean enabled) {}
}
