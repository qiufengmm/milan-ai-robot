package com.milanai.robot.controller;

import com.milanai.robot.service.AnswerSourceService;
import com.milanai.robot.service.EnterpriseKnowledgeChatService.SourceCard;
import com.milanai.robot.utils.Response;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/customer-service/chat/sources")
public class AnswerSourceController {
    private final AnswerSourceService sources;

    public AnswerSourceController(AnswerSourceService sources) {
        this.sources = sources;
    }

    @GetMapping("/{answerId}")
    public Response<List<SourceCard>> get(@PathVariable Long answerId) {
        return Response.success(sources.getSources(answerId));
    }
}
