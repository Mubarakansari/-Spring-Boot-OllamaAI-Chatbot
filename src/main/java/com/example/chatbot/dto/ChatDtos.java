package com.example.chatbot.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public class ChatDtos {

    // POST /api/chat request body
    public record ChatRequest(
            @NotBlank
            @Size(max = 8000, message = "Message too long")
            String message,

            // Optional: omit to start a new conversation
            UUID conversationId
    ) {}

    public record ChatResponse(
            UUID conversationId,
            String message,
            Instant createdAt,
            Integer inputTokens,
            Integer outputTokens
    ) {}

    public record MessageView(
            UUID id,
            String role,
            String content,
            Instant createdAt
    ) {}

    public record ConversationSummary(
            UUID id,
            String title,
            Instant createdAt,
            Instant updatedAt
    ) {}

    public record ConversationDetail(
            UUID id,
            String title,
            Instant createdAt,
            List<MessageView> messages
    ) {}
}
