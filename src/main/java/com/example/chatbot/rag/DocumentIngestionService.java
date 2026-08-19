package com.example.chatbot.rag;

import com.example.chatbot.dto.DocumentDtos.DocumentView;
import com.example.chatbot.entity.Document;
import com.example.chatbot.entity.Document.DocumentStatus;
import com.example.chatbot.entity.User;
import com.example.chatbot.exception.AppExceptions.ResourceNotFoundException;
import com.example.chatbot.repository.DocumentRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@EnableConfigurationProperties(RagProperties.class)
public class DocumentIngestionService {

    private static final Logger log = LoggerFactory.getLogger(DocumentIngestionService.class);

    private final DocumentRepository documentRepository;
    private final AstraChunkStore chunkStore;
    private final DocumentChunker chunker;
    private final RagProperties ragProperties;

    /**
     * Synchronous ingestion: extract -> chunk -> store (Astra embeds each
     * chunk server-side via $vectorize). For large files or high upload
     * volume, move this to an async job queue (e.g. a Spring @Async method
     * or a proper task queue) and let the client poll DocumentView.status -
     * the schema (PROCESSING/READY/FAILED) already supports that; this
     * method just runs it inline for simplicity here.
     */
    @Transactional
    public DocumentView ingest(User user, MultipartFile file) {
        if (!ragProperties.enabled()) {
            throw new IllegalStateException(
                    "RAG is disabled. Set RAG_ENABLED=true and run under the postgres profile.");
        }

        Document document = Document.builder()
                .user(user)
                .filename(file.getOriginalFilename())
                .status(DocumentStatus.PROCESSING)
                .build();
        documentRepository.save(document);

        try {
            String text = chunker.extractText(file);
            List<String> chunkTexts = chunker.chunk(text, ragProperties.chunkSizeChars(),
                    ragProperties.chunkOverlapChars());

            if (chunkTexts.isEmpty()) {
                document.setStatus(DocumentStatus.FAILED);
                documentRepository.save(document);
                throw new IllegalArgumentException("No extractable text found in the uploaded file.");
            }

            int storedChunkCount = chunkStore.saveChunks(document.getId(), user.getId(), document.getFilename(),
                    chunkTexts);

            document.setStatus(DocumentStatus.READY);
            document.setChunkCount(storedChunkCount);
            documentRepository.save(document);

        } catch (Exception e) {
            log.error("Document ingestion failed for {}: {}", file.getOriginalFilename(), e.getMessage());
            document.setStatus(DocumentStatus.FAILED);
            documentRepository.save(document);
            if (e instanceof IOException io) {
                throw new UncheckedIOException(io);
            }
            throw (RuntimeException) e;
        }

        return toView(document);
    }

    @Transactional(readOnly = true)
    public List<DocumentView> listDocuments(User user) {
        return documentRepository.findByUserIdOrderByCreatedAtDesc(user.getId()).stream()
                .map(this::toView)
                .toList();
    }

    @Transactional
    public void deleteDocument(User user, UUID documentId) {
        Document document = documentRepository.findByIdAndUserId(documentId, user.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Document not found"));
        chunkStore.deleteByDocumentId(document.getId());
        documentRepository.delete(document);
    }

    private DocumentView toView(Document d) {
        return new DocumentView(d.getId(), d.getFilename(), d.getStatus().name(), d.getChunkCount(), d.getCreatedAt());
    }
}
