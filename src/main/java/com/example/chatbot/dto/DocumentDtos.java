package com.example.chatbot.dto;

import java.time.Instant;
import java.util.UUID;

public class DocumentDtos {

    public record DocumentView(
            UUID id,
            String filename,
            String status,
            Integer chunkCount,
            Instant createdAt
    ) {}
}
