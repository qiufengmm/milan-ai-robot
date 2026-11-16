package com.milanai.robot.controller;

import com.milanai.robot.aspect.ApiOperationLog;
import com.milanai.robot.model.vo.chat.DeleteChatReqVO;
import com.milanai.robot.model.vo.chat.EnterpriseNewChatReqVO;
import com.milanai.robot.model.vo.chat.EnterpriseRenameChatReqVO;
import com.milanai.robot.model.vo.chat.FindChatHistoryMessagePageListReqVO;
import com.milanai.robot.model.vo.chat.FindChatHistoryPageListReqVO;
import com.milanai.robot.service.EnterpriseConversationService;
import com.milanai.robot.utils.PageResponse;
import com.milanai.robot.utils.Response;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/chat")
public class EnterpriseConversationController {
    private final EnterpriseConversationService conversations;

    public EnterpriseConversationController(EnterpriseConversationService conversations) {
        this.conversations = conversations;
    }

    @PostMapping("/new")
    @ApiOperationLog(description = "新建员工问答会话")
    public Response<?> newChat(@Valid @RequestBody EnterpriseNewChatReqVO request) {
        return Response.success(conversations.createChat(request.getKnowledgeBaseId(), request.getMessage()));
    }

    @GetMapping("/{uuid}")
    public Response<?> getChat(@PathVariable String uuid) {
        return Response.success(conversations.getChat(uuid));
    }

    @PostMapping("/list")
    @ApiOperationLog(description = "查询本人问答历史")
    public PageResponse<?> list(@Valid @RequestBody FindChatHistoryPageListReqVO request) {
        return conversations.listChats(request.getCurrent(), request.getSize());
    }

    @PostMapping("/message/list")
    @ApiOperationLog(description = "查询本人会话消息")
    public PageResponse<?> messages(@Valid @RequestBody FindChatHistoryMessagePageListReqVO request) {
        return conversations.listMessages(request);
    }

    @PostMapping("/summary/rename")
    @ApiOperationLog(description = "重命名本人会话")
    public Response<?> rename(@Valid @RequestBody EnterpriseRenameChatReqVO request) {
        conversations.renameChat(request.getUuid(), request.getSummary());
        return Response.success();
    }

    @PostMapping("/delete")
    @ApiOperationLog(description = "删除本人会话")
    public Response<?> delete(@Valid @RequestBody DeleteChatReqVO request) {
        conversations.deleteChat(request.getUuid());
        return Response.success();
    }
}
