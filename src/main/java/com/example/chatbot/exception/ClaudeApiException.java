package com.example.chatbot.exception;

/** Wraps any failure talking to the local Ollama server into a single app-level exception. */
public class ClaudeApiException extends RuntimeException {

    private final int statusCode;

    public ClaudeApiException(String message, int statusCode, Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
    }

    public int getStatusCode() {
        return statusCode;
    }
}
