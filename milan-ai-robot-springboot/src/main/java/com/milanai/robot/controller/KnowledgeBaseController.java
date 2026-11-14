package com.milanai.robot.controller;

import com.milanai.robot.domain.dos.KnowledgeBaseDO;
import com.milanai.robot.model.vo.customerService.KnowledgeBaseReqVO;
import com.milanai.robot.service.KnowledgeBaseService;
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
    public KnowledgeBaseController(KnowledgeBaseService libraries,RagIndexService indexes) {
        this.libraries=libraries; this.indexes=indexes;
    }
    @GetMapping public Response<List<KnowledgeBaseDO>> list() { return Response.success(libraries.list()); }
    @PostMapping public Response<Long> create(@RequestBody @Validated KnowledgeBaseReqVO request) {
        return Response.success(libraries.create(request));
    }
    @PutMapping("/{id}") public Response<?> update(@PathVariable Long id,@RequestBody @Validated KnowledgeBaseReqVO request) {
        libraries.update(id,request); return Response.success();
    }
    @PostMapping("/{id}/reindex") public Response<Integer> reindex(@PathVariable Long id) {
        libraries.require(id); return Response.success(indexes.reindex(id));
    }
}
