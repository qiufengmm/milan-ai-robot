package com.milanai.robot.controller;

import com.milanai.robot.exception.GlobalExceptionHandler;
import com.milanai.robot.service.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class EnterpriseRequestValidationTest {
    private final CustomerService files = mock(CustomerService.class);
    private final KnowledgeAccessService access = mock(KnowledgeAccessService.class);
    private final KnowledgeBaseService libraries = mock(KnowledgeBaseService.class);
    private final EnterpriseConversationService conversations = mock(EnterpriseConversationService.class);
    private final EnterpriseKnowledgeChatService chat = mock(EnterpriseKnowledgeChatService.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new AiCustomerServiceController(
                files, libraries, access, conversations, chat), new EnterpriseConversationController(conversations))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @Test
    void missingKnowledgeBaseInJsonCannotReachAnyService() throws Exception {
        mvc.perform(post("/customer-service/file/check").contentType("application/json")
                .content("{\"fileMd5\":\"0123456789abcdef0123456789abcdef\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(files, libraries, access);
    }

    @Test
    void missingKnowledgeBaseInMultipartCannotReachUpload() throws Exception {
        mvc.perform(multipart("/customer-service/file/upload-chunk")
                .file(new MockMultipartFile("chunk", "policy.txt", "text/plain", "policy".getBytes()))
                .param("fileMd5", "0123456789abcdef0123456789abcdef")
                .param("fileName", "policy.txt").param("fileSize", "6")
                .param("chunkNumber", "0").param("totalChunks", "1"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(files, libraries, access);
    }

    @Test
    void missingRequestIdCannotStartAnIdempotentTurn() throws Exception {
        mvc.perform(post("/customer-service/chat/completion").contentType("application/json")
                .content("{\"chatId\":\"chat-1\",\"message\":\"VPN怎么连接\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(conversations, chat, libraries);
    }
    @Test
    void removedFreeChatPostIsRejectedWithoutStartingAnyTurn() throws Exception {
        mvc.perform(post("/chat/completion").contentType("application/json").content("{}"))
                .andExpect(status().isMethodNotAllowed());
        verifyNoInteractions(conversations, chat, libraries);
    }

}
