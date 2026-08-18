package com.example.chatbot.rag;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentChunkerTest {

    private final DocumentChunker chunker = new DocumentChunker();

    @Test
    void chunk_splitsLongTextIntoOverlappingPieces() {
        String text = "word ".repeat(1000); // ~5000 chars
        List<String> chunks = chunker.chunk(text, 1000, 100);

        assertThat(chunks).isNotEmpty();
        chunks.forEach(c -> assertThat(c.length()).isLessThanOrEqualTo(1000));
    }

    @Test
    void chunk_returnsEmptyList_forBlankInput() {
        assertThat(chunker.chunk("   ", 1000, 100)).isEmpty();
    }

    @Test
    void chunk_returnsSingleChunk_whenTextShorterThanChunkSize() {
        List<String> chunks = chunker.chunk("A short document.", 1000, 100);
        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0)).isEqualTo("A short document.");
    }
}
