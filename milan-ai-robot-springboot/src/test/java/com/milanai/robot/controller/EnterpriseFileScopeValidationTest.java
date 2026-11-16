package com.milanai.robot.controller;

import com.milanai.robot.model.vo.customerService.*;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EnterpriseFileScopeValidationTest {
    @Test
    void omittedKnowledgeBaseIsInvalidForEveryFileOperation() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();
            for (Object request : List.of(new CheckFileReqVO(), new UploadChunkReqVO(),
                    new MergeChunkReqVO(), new DeleteMarkdownFileReqVO(),
                    new FindMarkdownFilePageListReqVO(), new UpdateMarkdownFileReqVO())) {
                assertThat(validator.validate(request))
                        .as(request.getClass().getSimpleName())
                        .anyMatch(violation -> "knowledgeBaseId".equals(violation.getPropertyPath().toString()));
            }
        }
    }
}
