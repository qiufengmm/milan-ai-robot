package com.milanai.robot.controller;

import com.milanai.robot.service.AnswerFeedbackService;
import com.milanai.robot.service.AnswerFeedbackService.EmployeeFeedback;
import com.milanai.robot.service.AnswerFeedbackService.MaintenanceFeedback;
import com.milanai.robot.utils.Response;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/customer-service/chat/feedback")
public class AnswerFeedbackController {
    private final AnswerFeedbackService feedback;

    public AnswerFeedbackController(AnswerFeedbackService feedback) {
        this.feedback = feedback;
    }

    @PostMapping("/{answerId}")
    public Response<EmployeeFeedback> submit(@PathVariable Long answerId, @Valid @RequestBody FeedbackRequest request) {
        return Response.success(feedback.submit(answerId, request.description()));
    }

    @GetMapping("/mine")
    public Response<List<EmployeeFeedback>> mine() {
        return Response.success(feedback.listMine());
    }

    @GetMapping("/knowledge-bases/{knowledgeBaseId}")
    public Response<List<MaintenanceFeedback>> queue(@PathVariable Long knowledgeBaseId) {
        return Response.success(feedback.listForMaintainer(knowledgeBaseId));
    }

    @PostMapping("/{feedbackId}/resolve")
    public Response<Void> resolve(@PathVariable Long feedbackId, @Valid @RequestBody ResolveRequest request) {
        feedback.resolve(feedbackId, request.resolutionNote());
        return Response.success();
    }

    public record FeedbackRequest(@NotBlank @Size(max = 500) String description) {}
    public record ResolveRequest(@NotBlank @Size(max = 500) String resolutionNote) {}
}
