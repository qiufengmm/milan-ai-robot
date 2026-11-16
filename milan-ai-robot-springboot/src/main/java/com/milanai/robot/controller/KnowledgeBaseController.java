package com.milanai.robot.controller;

import com.milanai.robot.domain.dos.KnowledgeBaseDO;
import com.milanai.robot.model.vo.customerService.KnowledgeBaseReqVO;
import com.milanai.robot.service.KnowledgeBaseService;
import com.milanai.robot.service.KnowledgeAccessService;
import com.milanai.robot.service.RagIndexService;
import com.milanai.robot.utils.Response;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/customer-service/knowledge-bases")
public class KnowledgeBaseController {
    private final KnowledgeBaseService libraries;
    private final RagIndexService indexes;
    private final KnowledgeAccessService access;
    public KnowledgeBaseController(KnowledgeBaseService libraries, RagIndexService indexes,
                                   KnowledgeAccessService access) {
        this.libraries = libraries;
        this.indexes = indexes;
        this.access = access;
    }
    @GetMapping public Response<List<KnowledgeBaseDO>> list() {
        return Response.success(access.listAccessibleKnowledgeBases());
    }
    @PostMapping public Response<Long> create(@RequestBody @Validated KnowledgeBaseReqVO request) {
        requireAdministrator();
        return Response.success(libraries.create(request));
    }
    @PutMapping("/{id}") public Response<?> update(@PathVariable Long id,@RequestBody @Validated KnowledgeBaseReqVO request) {
        requireAdministrator();
        libraries.update(id,request); return Response.success();
    }
    @PostMapping("/{id}/reindex") public Response<Integer> reindex(@PathVariable Long id) {
        access.requireEdit(id);
        libraries.require(id); return Response.success(indexes.reindex(id));
    }

    private void requireAdministrator() {
        access.requireAdministrator();
    }
}
