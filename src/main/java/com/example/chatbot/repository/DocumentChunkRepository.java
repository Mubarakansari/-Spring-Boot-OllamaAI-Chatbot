package com.example.chatbot.repository;

import com.example.chatbot.entity.DocumentChunk;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.UUID;

/**
 * Plain JPA repository for chunk metadata (content, ordering). The vector
 * column itself is written/read separately through VectorSearchRepository
 * (raw JDBC) - see that class for why.
 */
public interface DocumentChunkRepository extends JpaRepository<DocumentChunk, UUID> {

    @Modifying
    @Query("DELETE FROM DocumentChunk c WHERE c.document.id = :documentId")
    void deleteByDocumentId(@Param("documentId") UUID documentId);
}
