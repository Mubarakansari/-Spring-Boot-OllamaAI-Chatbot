package com.example.chatbot.claude;

import com.example.chatbot.entity.Message;
import com.example.chatbot.entity.Role;
import com.example.chatbot.exception.ClaudeApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Thin, isolated wrapper around a local Ollama server (default
 * http://localhost:11434). This is the ONLY class in the app that talks to
 * the model directly - everything else (ChatService, controllers) depends on
 * this abstraction, so the underlying provider could be swapped later
 * without touching business logic.
 */
@Service
@RequiredArgsConstructor
public class ClaudeService {

    private static final Logger log = LoggerFactory.getLogger(ClaudeService.class);

    private final HttpClient httpClient;
    private final ClaudeProperties properties;
    private final ObjectMapper objectMapper;

    /** Simple result wrapper carrying the reply text plus token usage for cost tracking. */
    public record ClaudeReply(String text, int inputTokens, int outputTokens) {}

    /**
     * Non-streaming call: send the system prompt + trimmed conversation history,
     * get back the full reply at once.
     */
    public ClaudeReply sendMessage(String systemPrompt, List<Message> history) {
        HttpRequest request = buildRequest(systemPrompt, history, false);

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw ollamaError(response.statusCode(), response.body());
            }

            JsonNode json = objectMapper.readTree(response.body());
            String text = json.path("message").path("content").asText("");
            int inputTokens = json.path("prompt_eval_count").asInt(0);
            int outputTokens = json.path("eval_count").asInt(0);
            return new ClaudeReply(text, inputTokens, outputTokens);

        } catch (IOException e) {
            log.error("Could not reach Ollama at {}: {}", properties.baseUrl(), e.getMessage());
            throw new ClaudeApiException(
                    "Could not reach the local Ollama server. Is 'ollama serve' running at " + properties.baseUrl() + "?",
                    504, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ClaudeApiException("Request to Ollama was interrupted.", 504, e);
        }
    }

    /**
     * Streaming call: invokes onToken for each text delta as it arrives,
     * then onComplete once with final token usage. Used by the SSE endpoint.
     * Ollama streams newline-delimited JSON objects, one per token/chunk.
     */
    public void streamMessage(String systemPrompt, List<Message> history,
                               Consumer<String> onToken, Consumer<ClaudeReply> onComplete,
                               Consumer<Throwable> onError) {
        HttpRequest request = buildRequest(systemPrompt, history, true);
        StringBuilder fullText = new StringBuilder();
        int[] usage = {0, 0}; // [inputTokens, outputTokens]

        try {
            HttpResponse<Stream<String>> response = httpClient.send(request, HttpResponse.BodyHandlers.ofLines());

            if (response.statusCode() != 200) {
                String errorBody = response.body().reduce("", (a, b) -> a + b);
                throw ollamaError(response.statusCode(), errorBody);
            }

            try (Stream<String> lines = response.body()) {
                lines.forEach(line -> {
                    if (line.isBlank()) return;
                    try {
                        JsonNode json = objectMapper.readTree(line);
                        String piece = json.path("message").path("content").asText("");
                        if (!piece.isEmpty()) {
                            fullText.append(piece);
                            onToken.accept(piece);
                        }
                        if (json.path("done").asBoolean(false)) {
                            usage[0] = json.path("prompt_eval_count").asInt(0);
                            usage[1] = json.path("eval_count").asInt(0);
                        }
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                });
            }

            onComplete.accept(new ClaudeReply(fullText.toString(), usage[0], usage[1]));

        } catch (IOException e) {
            log.error("Could not reach Ollama at {}: {}", properties.baseUrl(), e.getMessage());
            onError.accept(new ClaudeApiException(
                    "Could not reach the local Ollama server. Is 'ollama serve' running at " + properties.baseUrl() + "?",
                    504, e));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            onError.accept(new ClaudeApiException("Request to Ollama was interrupted.", 504, e));
        } catch (RuntimeException e) {
            onError.accept(e);
        }
    }

    private HttpRequest buildRequest(String systemPrompt, List<Message> history, boolean stream) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", properties.model());
        body.put("stream", stream);

        ArrayNode messages = body.putArray("messages");
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            messages.addObject().put("role", "system").put("content", systemPrompt);
        }
        for (Message m : history) {
            String role = switch (m.getRole()) {
                case USER -> "user";
                case ASSISTANT -> "assistant";
                default -> null; // SYSTEM-role rows (if any) are handled via the system prompt, not history
            };
            if (role != null) {
                messages.addObject().put("role", role).put("content", m.getContent());
            }
        }

        ObjectNode options = body.putObject("options");
        options.put("temperature", properties.temperature());
        options.put("num_predict", properties.maxTokens());

        try {
            return HttpRequest.newBuilder()
                    .uri(URI.create(properties.baseUrl() + "/api/chat"))
                    .timeout(Duration.ofSeconds(properties.requestTimeoutSeconds()))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                    .build();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to serialize Ollama request", e);
        }
    }

    private ClaudeApiException ollamaError(int statusCode, String body) {
        String message = body;
        try {
            JsonNode json = objectMapper.readTree(body);
            if (json.has("error")) {
                message = json.get("error").asText();
            }
        } catch (IOException ignored) {
            // body wasn't JSON - fall back to the raw text above
        }
        log.error("Ollama returned an error: status={} message={}", statusCode, message);
        return new ClaudeApiException("AI service returned an error: " + message, statusCode, null);
    }
}
