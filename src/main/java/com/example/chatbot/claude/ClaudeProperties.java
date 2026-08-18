package com.example.chatbot.claude;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "claude")
public record ClaudeProperties(
        String baseUrl,
        String model,
        int maxTokens,
        double temperature,
        int requestTimeoutSeconds,
        int maxHistoryMessages
) {}
