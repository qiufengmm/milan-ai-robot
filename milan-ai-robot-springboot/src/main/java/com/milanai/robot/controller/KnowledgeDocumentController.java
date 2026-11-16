package com.milanai.robot.controller;

import com.milanai.robot.service.KnowledgeAccessService;
import com.milanai.robot.service.KnowledgeDocumentService;
import com.milanai.robot.utils.Response;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/customer-service/documents")
public class KnowledgeDocumentController {
    private final KnowledgeDocumentService documents;
    private final KnowledgeAccessService access;

    public KnowledgeDocumentController(KnowledgeDocumentService documents, KnowledgeAccessService access) {
        this.documents = documents;
        this.access = access;
    }

    @GetMapping
    public Response<List<KnowledgeDocumentService.DocumentSummary>> list(@RequestParam Long knowledgeBaseId) {
        access.requireEdit(knowledgeBaseId);
        return Response.success(documents.list(knowledgeBaseId));
    }

    @PostMapping("/{documentId}/publish")
    public Response<Void> publish(@PathVariable Long documentId, @Valid @RequestBody PublishRequest request) {
        var document = documents.require(documentId);
        access.requireEdit(document.getKnowledgeBaseId());
        documents.publish(documentId, request.fileId());
        return Response.success();
    }

    @PostMapping("/{documentId}/withdraw")
    public Response<Void> withdraw(@PathVariable Long documentId) {
        var document = documents.require(documentId);
        access.requireEdit(document.getKnowledgeBaseId());
        documents.withdraw(documentId);
        return Response.success();
    }

    @GetMapping("/{documentId}/files/{fileId}/chunks")
    public Response<List<KnowledgeDocumentService.ChunkPreview>> preview(@PathVariable Long documentId,
                                                                          @PathVariable Long fileId) {
        var document = documents.require(documentId);
        access.requireEdit(document.getKnowledgeBaseId());
        return Response.success(documents.preview(documentId, fileId));
    }

    public record PublishRequest(@NotNull Long fileId) {}
}
