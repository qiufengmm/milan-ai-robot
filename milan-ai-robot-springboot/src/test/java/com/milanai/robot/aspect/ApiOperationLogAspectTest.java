package com.milanai.robot.aspect;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ApiOperationLogAspectTest {

    private final ApiOperationLogAspect aspect = new ApiOperationLogAspect();

    @Test
    void summariesNeverIncludeRequestOrResponsePayloads() {
        String arguments = aspect.summarizeArgs(new Object[]{"private question", Map.of("token", "secret")});
        String result = aspect.summarizeResult("private model response");

        assertThat(arguments).contains("2").doesNotContain("private question", "secret");
        assertThat(result).contains("String").doesNotContain("private model response");
    }
}
