package com.milanai.robot.service;

import com.milanai.robot.domain.mapper.ChatMessageMapper;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@EnableScheduling
public class GeneratingTurnRecovery {
    private static final int STALE_AFTER_MINUTES = 15;
    private final ChatMessageMapper messages;

    public GeneratingTurnRecovery(ChatMessageMapper messages) {
        this.messages = messages;
    }

    @Scheduled(fixedDelayString = "${enterprise.conversation.recovery-interval-ms:60000}",
            initialDelayString = "${enterprise.conversation.recovery-initial-delay-ms:60000}")
    @Transactional
    public void failInterruptedTurns() {
        messages.failStaleGeneratingMessages(STALE_AFTER_MINUTES);
    }
}
