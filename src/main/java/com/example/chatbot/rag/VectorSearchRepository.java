package com.example.chatbot.rag;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Handles the pgvector `embedding` column directly via JdbcTemplate rather
 * than a JPA field mapping. Reason: mapping pgvector's `vector` type into
 * Hibernate cleanly requires a custom UserType whose exact API shifts
 * between pgvector-java versions - a plain `::vector` cast on a text literal
 * is a few lines, has zero extra dependencies, and is exactly what
 * Postgres's own documentation examples use.
 */
@Repository
@RequiredArgsConstructor
public class VectorSearchRepository {

    private final JdbcTemplate jdbcTemplate;

    public record ChunkMatch(UUID chunkId, UUID documentId, String filename, String content, double distance) {}

    public void saveEmbedding(UUID chunkId, float[] embedding) {
        jdbcTemplate.update(
                "UPDATE document_chunks SET embedding = ?::vector WHERE id = ?",
                toVectorLiteral(embedding), chunkId
        );
    }

    /**
     * Cosine-distance nearest-neighbor search (`<=>` operator), scoped to one
     * user so RAG retrieval can never surface another user's documents.
     * Lower distance = more similar.
     */
    public List<ChunkMatch> searchTopK(UUID userId, float[] queryEmbedding, int k) {
        String vectorLiteral = toVectorLiteral(queryEmbedding);
        String sql = """
                SELECT dc.id AS chunk_id, dc.document_id, d.filename, dc.content,
                       dc.embedding <=> ?::vector AS distance
                FROM document_chunks dc
                JOIN documents d ON d.id = dc.document_id
                WHERE dc.user_id = ? AND dc.embedding IS NOT NULL
                ORDER BY dc.embedding <=> ?::vector
                LIMIT ?
                """;
        return jdbcTemplate.query(sql,
                (rs, rowNum) -> new ChunkMatch(
                        UUID.fromString(rs.getString("chunk_id")),
                        UUID.fromString(rs.getString("document_id")),
                        rs.getString("filename"),
                        rs.getString("content"),
                        rs.getDouble("distance")
                ),
                vectorLiteral, userId, vectorLiteral, k
        );
    }

    private String toVectorLiteral(float[] vector) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(vector[i]);
        }
        return sb.append(']').toString();
    }
}
