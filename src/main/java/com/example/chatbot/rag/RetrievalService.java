package com.example.chatbot.rag;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@EnableConfigurationProperties(RagProperties.class)
public class RetrievalService {

    private final AstraChunkStore chunkStore;
    private final RagProperties ragProperties;

    public boolean isEnabled() {
        return ragProperties.enabled();
    }

    /**
     * Retrieves the top-k most relevant chunks for a user's message and formats
     * them as a context block to prepend to the system prompt. Returns null if
     * RAG is disabled or nothing relevant was found, so callers can skip
     * augmentation cleanly rather than injecting an empty block.
     */
    public String retrieveContextBlock(UUID userId, String userMessage) {
        if (!ragProperties.enabled()) return null;

        List<AstraChunkStore.ChunkMatch> matches =
                chunkStore.searchTopK(userId, userMessage, ragProperties.topK());

        if (matches.isEmpty()) return null;

        StringBuilder sb = new StringBuilder();
        sb.append("The following excerpts from the user's uploaded documents may be relevant. ");
        sb.append("Use them to answer if relevant; ignore them if not, and never claim information ");
        sb.append("from them that isn't actually there.\n\n");

        for (AstraChunkStore.ChunkMatch m : matches) {
            sb.append("---\nSource: ").append(m.filename()).append("\n");
            sb.append(m.content()).append("\n");
        }
        sb.append("---");

        return sb.toString();
    }
}
