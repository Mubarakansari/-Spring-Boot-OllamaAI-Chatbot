package com.example.chatbot.rag;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "rag")
public record RagProperties(
        String astraDatabaseId,
        String astraToken,
        String astraRegion,
        String astraKeyspace,
        String embeddingProvider,   // e.g. nvidia - free, no separate API key needed
        String embeddingModel,      // e.g. NV-Embed-QA
        int embeddingDimension,     // e.g. 1024
        int chunkSizeChars,         // approx chars per chunk
        int chunkOverlapChars,
        int topK,                   // how many chunks to retrieve per query
        boolean enabled             // global on/off switch for RAG augmentation
) {}
