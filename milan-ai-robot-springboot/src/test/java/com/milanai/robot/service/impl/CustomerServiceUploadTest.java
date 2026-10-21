package com.milanai.robot.service.impl;

import com.milanai.robot.domain.dos.AiCustomerServiceMdStorageDO;
import com.milanai.robot.domain.mapper.AiCustomerServiceMdStorageMapper;
import com.milanai.robot.event.AiCustomerServiceMdUploadedEvent;
import com.milanai.robot.exception.BizException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

class CustomerServiceUploadTest {

    @TempDir
    Path directory;

    private final List<AiCustomerServiceMdUploadedEvent> events = new ArrayList<>();
    private CustomerServiceImpl service;

    @BeforeEach
    void setUp() {
        var mapper = mock(AiCustomerServiceMdStorageMapper.class);
        doAnswer(invocation -> {
            AiCustomerServiceMdStorageDO file = invocation.getArgument(0);
            file.setId(42L);
            return 1;
        }).when(mapper).insert(any(AiCustomerServiceMdStorageDO.class));
        var publisher = mock(ApplicationEventPublisher.class);
        doAnswer(invocation -> {
            events.add(invocation.getArgument(0));
            return null;
        }).when(publisher).publishEvent(any(Object.class));

        service = new CustomerServiceImpl();
        ReflectionTestUtils.setField(service, "mdStoragePath", directory.toString());
        ReflectionTestUtils.setField(service, "aiCustomerServiceMdStorageMapper", mapper);
        ReflectionTestUtils.setField(service, "eventPublisher", publisher);
    }

    @ParameterizedTest
    @ValueSource(strings = {"md", "markdown", "pdf", "PDF", "doc", "docx", "txt"})
    void acceptedUploadStoresBytesAndPublishesFileOwnership(String extension) throws Exception {
        String filename = "knowledge." + extension;
        byte[] content = "Warranty policy for knowledge upload.".getBytes(StandardCharsets.UTF_8);

        var response = service.uploadMarkdownFile(new MockMultipartFile("file", filename, "application/octet-stream", content));

        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getData()).isEqualTo(42L);
        assertThat(events).singleElement().satisfies(event -> {
            assertThat(event.getId()).isEqualTo(42L);
            assertThat(event.getMetadatas()).containsEntry("mdStorageId", 42L)
                    .containsEntry("originalFileName", filename);
            Path stored = Path.of(event.getFilePath());
            assertThat(stored.getParent()).isEqualTo(directory);
            assertThat(stored).hasBinaryContent(content);
        });
    }

    @Test
    void missingFileIsRejectedBeforeCreatingStorage() throws Exception {
        assertThatThrownBy(() -> service.uploadMarkdownFile(null))
                .isInstanceOf(BizException.class)
                .extracting("errorCode").isEqualTo("20001");
        assertNothingStored();
    }

    @Test
    void unsupportedExtensionIsRejectedBeforeCreatingStorage() throws Exception {
        var file = new MockMultipartFile("file", "program.exe", "application/octet-stream", new byte[]{1});

        assertThatThrownBy(() -> service.uploadMarkdownFile(file))
                .isInstanceOf(BizException.class)
                .extracting("errorCode").isEqualTo("20002");
        assertNothingStored();
    }

    private void assertNothingStored() throws Exception {
        assertThat(events).isEmpty();
        try (var files = Files.list(directory)) {
            assertThat(files.toList()).isEmpty();
        }
    }
}
