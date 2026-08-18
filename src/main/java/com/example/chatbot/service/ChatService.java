package com.example.chatbot.service;

import com.example.chatbot.claude.ClaudeService;
import com.example.chatbot.claude.ClaudeService.ClaudeReply;
import com.example.chatbot.claude.ClaudeProperties;
import com.example.chatbot.claude.PromptService;
import com.example.chatbot.rag.RetrievalService;
import com.example.chatbot.dto.ChatDtos.*;
import com.example.chatbot.entity.Conversation;
import com.example.chatbot.entity.Message;
import com.example.chatbot.entity.Role;
import com.example.chatbot.entity.User;
import com.example.chatbot.exception.AppExceptions.AccessDeniedAppException;
import com.example.chatbot.exception.AppExceptions.ResourceNotFoundException;
import com.example.chatbot.repository.ConversationRepository;
import com.example.chatbot.repository.MessageRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

@Service
@RequiredArgsConstructor
public class ChatService {

        private final ConversationRepository conversationRepository;
        private final MessageRepository messageRepository;
        private final ClaudeService claudeService;
        private final PromptService promptService;
        private final ClaudeProperties claudeProperties;
        private final RetrievalService retrievalService;

        /**
         * Handles one non-streaming chat turn:
         * 1. find-or-create the conversation (scoped to the user)
         * 2. persist the user's message
         * 3. build trimmed context and call Claude
         * 4. persist the assistant's reply
         */
        @Transactional
        public ChatResponse handleChat(User user, ChatRequest request) {
                Conversation conversation = resolveConversation(user, request.conversationId(), request.message());

                Message userMessage = Message.builder()
                                .conversation(conversation)
                                .role(Role.USER)
                                .content(request.message())
                                .build();
                messageRepository.save(userMessage);

                List<Message> context = buildContext(conversation.getId());
                String systemPrompt = buildSystemPrompt(user.getId(), request.message());

                ClaudeReply reply = claudeService.sendMessage(systemPrompt, context);

                Message assistantMessage = Message.builder()
                                .conversation(conversation)
                                .role(Role.ASSISTANT)
                                .content(reply.text())
                                .inputTokens(reply.inputTokens())
                                .outputTokens(reply.outputTokens())
                                .build();
                messageRepository.save(assistantMessage);

                conversation.setUpdatedAt(java.time.Instant.now());
                conversationRepository.save(conversation);

                return new ChatResponse(
                                conversation.getId(),
                                reply.text(),
                                assistantMessage.getCreatedAt(),
                                reply.inputTokens(),
                                reply.outputTokens());
        }

        /**
         * Streaming variant used by the SSE controller. Persists the user message
         * up front and the assistant message once the stream completes.
         */
        @Transactional
        public UUID startStreamingChat(User user, ChatRequest request,
                        Consumer<String> onToken,
                        Consumer<ChatResponse> onComplete,
                        Consumer<Throwable> onError) {
                Conversation conversation = resolveConversation(user, request.conversationId(), request.message());

                Message userMessage = Message.builder()
                                .conversation(conversation)
                                .role(Role.USER)
                                .content(request.message())
                                .build();
                messageRepository.save(userMessage);

                List<Message> context = buildContext(conversation.getId());
                UUID conversationId = conversation.getId();
                String systemPrompt = buildSystemPrompt(user.getId(), request.message());

                claudeService.streamMessage(
                                systemPrompt,
                                context,
                                onToken,
                                reply -> {
                                        Message assistantMessage = Message.builder()
                                                        .conversation(conversation)
                                                        .role(Role.ASSISTANT)
                                                        .content(reply.text())
                                                        .inputTokens(reply.inputTokens())
                                                        .outputTokens(reply.outputTokens())
                                                        .build();
                                        messageRepository.save(assistantMessage);
                                        conversation.setUpdatedAt(java.time.Instant.now());
                                        conversationRepository.save(conversation);

                                        onComplete.accept(new ChatResponse(
                                                        conversationId, reply.text(), assistantMessage.getCreatedAt(),
                                                        reply.inputTokens(), reply.outputTokens()));
                                },
                                onError);

                return conversationId;
        }

        @Transactional(readOnly = true)
        public List<ConversationSummary> listConversations(User user) {
                return conversationRepository.findByUserIdOrderByUpdatedAtDesc(user.getId()).stream()
                                .map(c -> new ConversationSummary(c.getId(), c.getTitle(), c.getCreatedAt(),
                                                c.getUpdatedAt()))
                                .toList();
        }

        @Transactional(readOnly = true)
        public ConversationDetail getConversation(User user, UUID conversationId) {
                Conversation conversation = conversationRepository.findByIdAndUserId(conversationId, user.getId())
                                .orElseThrow(() -> new ResourceNotFoundException("Conversation not found"));

                List<MessageView> messages = messageRepository.findByConversationIdOrderByCreatedAtAsc(conversationId)
                                .stream()
                                .map(m -> new MessageView(m.getId(), m.getRole().name(), m.getContent(),
                                                m.getCreatedAt()))
                                .toList();

                return new ConversationDetail(conversation.getId(), conversation.getTitle(),
                                conversation.getCreatedAt(), messages);
        }

        // ---- internal helpers ----

        /**
         * Combines the base system prompt with a RAG context block (if RAG is
         * enabled and relevant chunks were found for this user's message). This
         * runs one extra embedding call per chat turn when RAG is on - acceptable
         * latency-wise since it's a single small request, but worth knowing if
         * you're profiling chat latency.
         */
        private String buildSystemPrompt(UUID userId, String userMessage) {
                String base = promptService.getDefaultSystemPrompt();
                if (!retrievalService.isEnabled())
                        return base;

                String ragContext = retrievalService.retrieveContextBlock(userId, userMessage);
                if (ragContext == null)
                        return base;

                return base + "\n\n" + ragContext;
        }

        private Conversation resolveConversation(User user, UUID conversationId, String firstMessage) {
                if (conversationId != null) {
                        return conversationRepository.findByIdAndUserId(conversationId, user.getId())
                                        .orElseThrow(() -> new AccessDeniedAppException(
                                                        "Conversation not found or not accessible"));
                }
                Conversation conversation = Conversation.builder()
                                .user(user)
                                .title(deriveTitle(firstMessage))
                                .build();
                return conversationRepository.save(conversation);
        }

        private String deriveTitle(String firstMessage) {
                String trimmed = firstMessage.strip();
                return trimmed.length() <= 60 ? trimmed : trimmed.substring(0, 60) + "...";
        }

        /**
         * Context-window management: only send the last N messages to Claude
         * instead of the entire conversation history. This bounds both latency
         * and cost as conversations grow. For very long-running conversations,
         * replace this with a summarization step (see README "Context Management").
         */
        private List<Message> buildContext(UUID conversationId) {
                int limit = claudeProperties.maxHistoryMessages();
                List<Message> recent = messageRepository.findByConversationIdOrderByCreatedAtDesc(
                                conversationId, PageRequest.of(0, limit, Sort.by("createdAt").descending()));
                List<Message> ordered = new ArrayList<>(recent);
                Collections.reverse(ordered);
                return ordered;
        }
}
