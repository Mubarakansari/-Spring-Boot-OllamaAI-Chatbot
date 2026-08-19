package com.example.chatbot.service;

import com.example.chatbot.claude.ClaudeProperties;
import com.example.chatbot.claude.ClaudeService;
import com.example.chatbot.claude.PromptService;
import com.example.chatbot.rag.RetrievalService;
import com.example.chatbot.dto.ChatDtos.ChatRequest;
import com.example.chatbot.dto.ChatDtos.ChatResponse;
import com.example.chatbot.entity.Conversation;
import com.example.chatbot.entity.User;
import com.example.chatbot.repository.ConversationRepository;
import com.example.chatbot.repository.MessageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ChatServiceTest {

    @Mock private ConversationRepository conversationRepository;
    @Mock private MessageRepository messageRepository;
    @Mock private ClaudeService claudeService; // mocked - no real Claude API calls, no credits used
    @Mock private PromptService promptService;
    @Mock private RetrievalService retrievalService; // RAG mocked off - no Astra calls in unit tests
    private ClaudeProperties claudeProperties = new ClaudeProperties("test-key", "claude-sonnet-5", 1024, 1.0, 60, 20);

    private ChatService chatService;

    private User testUser;

    @BeforeEach
    void setUp() {
        chatService = new ChatService(conversationRepository, messageRepository, claudeService, promptService, claudeProperties, retrievalService);
        testUser = User.builder().id(UUID.randomUUID()).email("test@example.com").passwordHash("hash").build();
        lenient().when(retrievalService.isEnabled()).thenReturn(false);
    }

    @Test
    void handleChat_createsNewConversation_whenNoConversationIdGiven() {
        when(conversationRepository.save(any(Conversation.class)))
                .thenAnswer(inv -> {
                    Conversation c = inv.getArgument(0);
                    c.setId(UUID.randomUUID());
                    return c;
                });
        when(messageRepository.findByConversationIdOrderByCreatedAtDesc(any(), any())).thenReturn(List.of());
        when(promptService.getDefaultSystemPrompt()).thenReturn("You are helpful.");
        when(claudeService.sendMessage(any(), any()))
                .thenReturn(new ClaudeService.ClaudeReply("Hello there!", 10, 5));

        ChatRequest request = new ChatRequest("Hi Claude", null);
        ChatResponse response = chatService.handleChat(testUser, request);

        assertThat(response.message()).isEqualTo("Hello there!");
        assertThat(response.inputTokens()).isEqualTo(10);
        assertThat(response.outputTokens()).isEqualTo(5);
        verify(messageRepository, times(2)).save(any()); // user message + assistant message
    }

    @Test
    void handleChat_reusesExistingConversation_whenConversationIdGiven() {
        UUID convId = UUID.randomUUID();
        Conversation existing = Conversation.builder().id(convId).user(testUser).build();
        when(conversationRepository.findByIdAndUserId(convId, testUser.getId())).thenReturn(Optional.of(existing));
        when(messageRepository.findByConversationIdOrderByCreatedAtDesc(any(), any())).thenReturn(List.of());
        when(promptService.getDefaultSystemPrompt()).thenReturn("sys");
        when(claudeService.sendMessage(any(), any()))
                .thenReturn(new ClaudeService.ClaudeReply("Reply", 1, 1));

        ChatRequest request = new ChatRequest("Follow up", convId);
        ChatResponse response = chatService.handleChat(testUser, request);

        assertThat(response.conversationId()).isEqualTo(convId);
        verify(conversationRepository, never()).save(argThat(c -> c.getId() == null));
    }
}
