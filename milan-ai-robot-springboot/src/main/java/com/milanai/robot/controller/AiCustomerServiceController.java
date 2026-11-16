package com.milanai.robot.controller;

import com.milanai.robot.aspect.ApiOperationLog;
import com.milanai.robot.model.vo.customerService.*;
import com.milanai.robot.service.CustomerService;
import com.milanai.robot.service.EnterpriseConversationService;
import com.milanai.robot.service.EnterpriseKnowledgeChatService;
import com.milanai.robot.service.KnowledgeAccessService;
import com.milanai.robot.service.KnowledgeBaseService;
import com.milanai.robot.utils.PageResponse;
import com.milanai.robot.utils.Response;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

@RestController
@RequestMapping("/customer-service")
public class AiCustomerServiceController {
    private final CustomerService customerService;
    private final KnowledgeBaseService knowledgeBases;
    private final KnowledgeAccessService access;
    private final EnterpriseConversationService conversations;
    private final EnterpriseKnowledgeChatService chat;

    public AiCustomerServiceController(CustomerService customerService, KnowledgeBaseService knowledgeBases,
            KnowledgeAccessService access, EnterpriseConversationService conversations,
            EnterpriseKnowledgeChatService chat) {
        this.customerService = customerService;
        this.knowledgeBases = knowledgeBases;
        this.access = access;
        this.conversations = conversations;
        this.chat = chat;
    }

    @PostMapping("/file/check")
    @ApiOperationLog(description = "检查文件是否存在")
    public Response<CheckFileRspVO> checkFile(@Valid @RequestBody CheckFileReqVO request) {
        access.requireEdit(request.getKnowledgeBaseId());
        return customerService.checkFile(request);
    }

    @PostMapping(value = "/file/upload-chunk", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Response<?> uploadChunk(@Valid @ModelAttribute UploadChunkReqVO request) {
        access.requireEdit(request.getKnowledgeBaseId());
        return customerService.uploadChunk(request);
    }

    @PostMapping("/file/merge-chunk")
    @ApiOperationLog(description = "文件分片合并")
    public Response<?> mergeChunk(@Valid @RequestBody MergeChunkReqVO request) {
        access.requireEdit(request.getKnowledgeBaseId());
        return customerService.mergeChunk(request);
    }

    @PostMapping("/file/delete")
    @ApiOperationLog(description = "删除问答文件")
    public Response<?> deleteMarkdownFile(@Valid @RequestBody DeleteMarkdownFileReqVO request) {
        access.requireEdit(request.getKnowledgeBaseId());
        return customerService.deleteMarkdownFile(request);
    }

    @PostMapping("/file/list")
    @ApiOperationLog(description = "问答文件分页查询")
    public PageResponse<FindMarkdownFilePageListRspVO> findMarkdownFilePageList(
            @Valid @RequestBody FindMarkdownFilePageListReqVO request) {
        access.requireRead(request.getKnowledgeBaseId());
        return customerService.findMarkdownFilePageList(request);
    }

    @PostMapping("/file/update")
    @ApiOperationLog(description = "修改问答文件信息")
    public Response<?> updateMarkdownFile(@Valid @RequestBody UpdateMarkdownFileReqVO request) {
        access.requireEdit(request.getKnowledgeBaseId());
        return customerService.updateMarkdownFile(request);
    }

    @PostMapping(value = "/chat/completion", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> chat(@Valid @RequestBody EnterpriseChatCompletionReqVO request) {
        var turn = conversations.beginRequest(request.getChatId(), request.getRequestId(), request.getMessage());
        try {
            var library = knowledgeBases.require(turn.chat().getKnowledgeBaseId());
            return chat.generate(turn, library);
        } catch (RuntimeException exception) {
            conversations.fail(turn.assistantMessageId(), "", "REQUEST_SETUP_FAILED");
            throw exception;
        }
    }
}
