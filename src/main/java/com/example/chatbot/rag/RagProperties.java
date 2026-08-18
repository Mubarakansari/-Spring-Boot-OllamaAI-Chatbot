package com.example.chatbot.rag;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "rag")
public record RagProperties(
        String voyageApiKey,
        String embeddingModel,      // e.g. voyage-3-large
        int embeddingDimension,     // e.g. 1024
        int chunkSizeChars,         // approx chars per chunk
        int chunkOverlapChars,
        int topK,                   // how many chunks to retrieve per query
        boolean enabled             // global on/off switch for RAG augmentation
) {}
