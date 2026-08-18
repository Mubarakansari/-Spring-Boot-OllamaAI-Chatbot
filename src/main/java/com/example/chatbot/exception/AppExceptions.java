package com.example.chatbot.exception;

public class AppExceptions {

    public static class ResourceNotFoundException extends RuntimeException {
        public ResourceNotFoundException(String message) { super(message); }
    }

    public static class AccessDeniedAppException extends RuntimeException {
        public AccessDeniedAppException(String message) { super(message); }
    }

    public static class DuplicateEmailException extends RuntimeException {
        public DuplicateEmailException(String message) { super(message); }
    }

    public static class RateLimitExceededException extends RuntimeException {
        public RateLimitExceededException(String message) { super(message); }
    }
}
