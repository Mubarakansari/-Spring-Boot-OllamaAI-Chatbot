package com.example.chatbot.claude;

import org.springframework.stereotype.Service;

/**
 * Central place for system prompts. Keeping them here (instead of inline in
 * controllers/services) means they can be versioned, reused across chatbot
 * "personas", and changed without touching business logic.
 *
 * For a real production app, consider moving these into the database or a
 * config service so they can be edited without a redeploy.
 */
@Service
public class PromptService {

    private static final String DEFAULT_SYSTEM_PROMPT_V1 = """
            You are a helpful, concise assistant embedded in a product support chatbot.
            - Keep answers focused and avoid unnecessary preamble.
            - If you don't know something, say so rather than guessing.
            - Format code in fenced code blocks.
            """;

    public String getDefaultSystemPrompt() {
        return DEFAULT_SYSTEM_PROMPT_V1;
    }

    /**
     * Example of supporting multiple personas/use-cases from one service.
     * Extend this with a DB-backed lookup once you need runtime-editable prompts.
     */
    public String getSystemPrompt(String promptKey) {
        return switch (promptKey == null ? "default" : promptKey) {
            case "support" -> DEFAULT_SYSTEM_PROMPT_V1;
            case "sales" -> """
                    You are a friendly, knowledgeable sales assistant.
                    Highlight product value without being pushy. Never invent pricing or features.
                    """;
            default -> DEFAULT_SYSTEM_PROMPT_V1;
        };
    }
}
