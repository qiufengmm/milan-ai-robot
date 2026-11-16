package com.milanai.robot.service;

import com.milanai.robot.domain.mapper.KnowledgeDocumentMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("enterprise")
@Transactional
class KnowledgeDocumentWithdrawIntegrationTest {
    @Autowired private KnowledgeDocumentMapper documents;
    @Autowired private KnowledgeDocumentService service;

    @Test
    void withdrawingPublishedDocumentClearsItsCurrentFilePointer() {
        var document = documents.selectById(3L);
        assertThat(document).isNotNull();
        assertThat(document.getCurrentFileId()).isEqualTo(4L);

        service.withdraw(3L);

        assertThat(documents.selectById(3L).getCurrentFileId()).isNull();
    }
}
