package com.example.chatbot.repository;

import com.example.chatbot.entity.Message;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface MessageRepository extends JpaRepository<Message, UUID> {

    List<Message> findByConversationIdOrderByCreatedAtAsc(UUID conversationId);

    // Used to pull only the last N messages for context-window management
    List<Message> findByConversationIdOrderByCreatedAtDesc(UUID conversationId, Pageable pageable);
}
