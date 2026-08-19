package com.example.chatbot.controller;

import com.example.chatbot.config.RateLimiter;
import com.example.chatbot.dto.ChatDtos.*;
import com.example.chatbot.entity.User;
import com.example.chatbot.exception.AppExceptions.RateLimitExceededException;
import com.example.chatbot.security.AppUserPrincipal;
import com.example.chatbot.service.ChatService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;

@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
public class ChatController {

    private final ChatService chatService;
    private final RateLimiter rateLimiter;

    /**
     * POST /api/chat - simple request/response, waits for the full Claude reply.
     */
    @PostMapping
    public ResponseEntity<ChatResponse> chat(@AuthenticationPrincipal AppUserPrincipal principal,
            @Valid @RequestBody ChatRequest request) {
        User user = principal.getUser();
        if (!rateLimiter.allow(user.getId().toString())) {
            throw new RateLimitExceededException("Too many requests. Please slow down.");
        }
        return ResponseEntity.ok(chatService.handleChat(user, request));
    }

    /**
     * POST /api/chat/stream - Server-Sent Events endpoint.
     * Emits "token" events as text arrives, then a final "done" event.
     * Frontend consumes this with EventSource or a fetch()-based SSE reader.
     */
    @PostMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(@AuthenticationPrincipal AppUserPrincipal principal,
            @Valid @RequestBody ChatRequest request) {
        User user = principal.getUser();
        if (!rateLimiter.allow(user.getId().toString())) {
            throw new RateLimitExceededException("Too many requests. Please slow down.");
        }

        SseEmitter emitter = new SseEmitter(0L); // no timeout; rely on completion/error to close

        Executors.newCachedThreadPool().submit(() -> {
            try {
                chatService.startStreamingChat(
                        user,
                        request,
                        token -> {
                            try {
                                emitter.send(SseEmitter.event().name("token").data(token));
                            } catch (Exception ignored) {
                                /* client likely disconnected */ }
                        },
                        finalResponse -> {
                            try {
                                emitter.send(SseEmitter.event().name("done").data(finalResponse));
                                emitter.complete();
                            } catch (Exception ignored) {
                            }
                        },
                        error -> {
                            try {
                                emitter.send(SseEmitter.event().name("error").data(error.getMessage()));
                            } catch (Exception ignored) {
                            }
                            // complete(), not completeWithError(): the client was already told via
                            // the "error" SSE event above. completeWithError() would instead make
                            // Spring re-dispatch this exception through the normal @ExceptionHandler
                            // resolution chain, which tries to write a JSON body onto a response
                            // already committed to text/event-stream and blows up.
                            emitter.complete();
                        });
            } catch (Exception e) {
                try {
                    emitter.send(SseEmitter.event().name("error").data(e.getMessage()));
                } catch (Exception ignored) {
                }
                emitter.complete();
            }
        });

        return emitter;
    }

    /**
     * GET /api/chat/conversations - list the authenticated user's conversations.
     */
    @GetMapping("/conversations")
    public ResponseEntity<List<ConversationSummary>> listConversations(
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return ResponseEntity.ok(chatService.listConversations(principal.getUser()));
    }

    /** GET /api/chat/conversations/{id} - full history of one conversation. */
    @GetMapping("/conversations/{id}")
    public ResponseEntity<ConversationDetail> getConversation(@AuthenticationPrincipal AppUserPrincipal principal,
            @PathVariable UUID id) {
        return ResponseEntity.ok(chatService.getConversation(principal.getUser(), id));
    }
}
