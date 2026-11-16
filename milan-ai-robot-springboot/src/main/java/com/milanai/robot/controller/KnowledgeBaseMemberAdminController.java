package com.milanai.robot.controller;

import com.milanai.robot.service.KnowledgeAccessService;
import com.milanai.robot.service.KnowledgeBaseMemberAdminService;
import com.milanai.robot.utils.Response;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/admin/knowledge-bases/{knowledgeBaseId}/members")
public class KnowledgeBaseMemberAdminController {
    private final KnowledgeAccessService access;
    private final KnowledgeBaseMemberAdminService members;

    public KnowledgeBaseMemberAdminController(KnowledgeAccessService access, KnowledgeBaseMemberAdminService members) {
        this.access = access;
        this.members = members;
    }

    @GetMapping
    public Response<List<KnowledgeBaseMemberAdminService.MemberSummary>> list(@PathVariable Long knowledgeBaseId) {
        access.requireAdministrator();
        return Response.success(members.list(knowledgeBaseId));
    }

    @PutMapping
    public Response<Void> assign(@PathVariable Long knowledgeBaseId,
                                 @Valid @RequestBody AssignMemberRequest request) {
        access.requireAdministrator();
        members.assign(request.userId(), knowledgeBaseId, request.accessLevel());
        return Response.success();
    }

    @DeleteMapping
    public Response<Void> revoke(@PathVariable Long knowledgeBaseId, @RequestParam Long userId) {
        access.requireAdministrator();
        members.revoke(userId, knowledgeBaseId);
        return Response.success();
    }

    public record AssignMemberRequest(Long userId,
            @NotBlank @Pattern(regexp = "READER|EDITOR") String accessLevel) {}
}
