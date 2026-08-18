package com.example.chatbot.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * One embedded chunk of a Document. The `embedding` column uses pgvector's
 * native `vector(N)` type (see db/V2__rag_pgvector.sql for the DDL and the
 * `CREATE EXTENSION vector` statement). Similarity search uses the pgvector
 * `<=>` cosine-distance operator via a native query - see DocumentChunkRepository.
 *
 * The embedding itself is written/read through native SQL (see
 * DocumentIngestionService and DocumentChunkRepository) rather than a plain
 * JPA field mapping, since pgvector's binary format needs the PGvector
 * driver type. The field below exists for readability of the entity shape;
 * actual persistence goes through JdbcTemplate with PGvector.
 */
@Entity
@Table(name = "document_chunks")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DocumentChunk {

    @Id
    @GeneratedValue
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "document_id", nullable = false)
    private Document document;

    // Denormalized for fast per-user filtering without a join on every search
    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(nullable = false, columnDefinition = "text")
    private String content;

    private Integer chunkIndex;

    @Column(nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();
}
