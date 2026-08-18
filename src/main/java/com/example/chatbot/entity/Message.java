package com.example.chatbot.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "messages")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Message {

    @Id
    @GeneratedValue
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "conversation_id", nullable = false)
    private Conversation conversation;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role role;

    // TEXT column - chat messages can be long
    @Column(nullable = false, columnDefinition = "text")
    private String content;

    // Optional: track token usage per message for cost observability
    private Integer inputTokens;
    private Integer outputTokens;

    @Column(nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();
}
