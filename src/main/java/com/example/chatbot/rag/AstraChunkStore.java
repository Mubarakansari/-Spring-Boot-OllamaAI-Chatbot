package com.example.chatbot.rag;

import com.datastax.astra.client.DataAPIClient;
import com.datastax.astra.client.collections.Collection;
import com.datastax.astra.client.collections.commands.options.CollectionFindOptions;
import com.datastax.astra.client.collections.definition.CollectionDefinition;
import com.datastax.astra.client.collections.definition.documents.Document;
import com.datastax.astra.client.core.query.Filters;
import com.datastax.astra.client.core.query.Sort;
import com.datastax.astra.client.core.vector.SimilarityMetric;
import com.datastax.astra.client.databases.Database;
import com.datastax.astra.client.databases.DatabaseOptions;
import com.datastax.astra.client.exceptions.DataAPIException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Stores and searches document chunks in an Astra DB collection. Each chunk
 * document's text is embedded server-side via Astra's $vectorize (no
 * separate embedding client needed - replaces VoyageEmbeddingClient +
 * VectorSearchRepository's raw pgvector JDBC). Lazily connects on first use
 * (mirrors the old VoyageEmbeddingClient pattern) so a missing/blank Astra
 * config only breaks RAG calls, not app startup.
 */
@Repository
@EnableConfigurationProperties(RagProperties.class)
public class AstraChunkStore {

    private static final Logger log = LoggerFactory.getLogger(AstraChunkStore.class);
    private static final String COLLECTION_NAME = "document_chunks";

    private final RagProperties properties;
    private volatile Collection<Document> collection;

    public AstraChunkStore(RagProperties properties) {
        this.properties = properties;
    }

    public record ChunkMatch(String filename, String content) {}

    /** Max times a single chunk can be halved chasing the embedding provider's token limit before it's dropped. */
    private static final int MAX_SPLIT_DEPTH = 5;

    /**
     * Inserts one chunk at a time. The embedding provider (NV-Embed-QA) has
     * a hard 512-token input limit; no fixed character count can guarantee
     * staying under it, since real-world content (tax forms, tables of
     * numbers/codes) can tokenize far more densely than prose. So instead of
     * guessing a "safe" chunk size, a chunk the provider rejects for being
     * too long is halved and each half retried (recursively, bounded by
     * MAX_SPLIT_DEPTH) - self-healing regardless of content density. Returns
     * how many pieces actually made it in, so the caller can record the
     * real count instead of assuming every chunk landed as-is.
     */
    public int saveChunks(UUID documentId, UUID userId, String filename, List<String> chunkTexts) {
        int stored = 0;
        for (String text : chunkTexts) {
            stored += insertWithSplitFallback(documentId, userId, filename, text, MAX_SPLIT_DEPTH);
        }
        return stored;
    }

    private int insertWithSplitFallback(UUID documentId, UUID userId, String filename, String text,
            int splitsRemaining) {
        try {
            collection().insertOne(Document.create()
                    .append("documentId", documentId.toString())
                    .append("userId", userId.toString())
                    .append("filename", filename)
                    // Plain field for reading back on search (see below for why -
                    // $vectorize triggers embedding but isn't returned by default
                    // find() projections, so a normal field is more reliable).
                    .append("content", text)
                    .vectorize(text));
            return 1;
        } catch (DataAPIException e) {
            if (splitsRemaining <= 0 || text.length() < 50) {
                log.warn("Dropping an unembeddable chunk ({} chars) for document '{}' ({}): {}",
                        text.length(), filename, documentId, e.getMessage());
                return 0;
            }
            int mid = text.length() / 2;
            int splitAt = text.lastIndexOf(' ', mid);
            if (splitAt <= 0) {
                splitAt = mid;
            }
            String first = text.substring(0, splitAt).strip();
            String second = text.substring(splitAt).strip();
            log.warn("Chunk too dense for the embedding provider's token limit ({} chars); splitting document "
                    + "'{}' ({}) chunk in half and retrying", text.length(), filename, documentId);
            int stored = 0;
            if (!first.isEmpty()) {
                stored += insertWithSplitFallback(documentId, userId, filename, first, splitsRemaining - 1);
            }
            if (!second.isEmpty()) {
                stored += insertWithSplitFallback(documentId, userId, filename, second, splitsRemaining - 1);
            }
            return stored;
        }
    }

    /** Cosine-similarity search via Astra's server-side query embedding, scoped to one user. */
    public List<ChunkMatch> searchTopK(UUID userId, String queryText, int k) {
        CollectionFindOptions options = new CollectionFindOptions()
                .sort(Sort.vectorize(queryText))
                .limit(k);
        return collection().find(Filters.eq("userId", userId.toString()), options)
                .stream()
                .map(d -> new ChunkMatch(d.getString("filename"), d.getString("content")))
                .toList();
    }

    public void deleteByDocumentId(UUID documentId) {
        collection().deleteMany(Filters.eq("documentId", documentId.toString()));
    }

    private Collection<Document> collection() {
        Collection<Document> c = collection;
        if (c == null) {
            synchronized (this) {
                c = collection;
                if (c == null) {
                    c = connect();
                    collection = c;
                }
            }
        }
        return c;
    }

    private Collection<Document> connect() {
        if (properties.astraDatabaseId() == null || properties.astraDatabaseId().isBlank()
                || properties.astraToken() == null || properties.astraToken().isBlank()) {
            throw new IllegalStateException(
                    "ASTRA_DB_ID / ASTRA_DB_TOKEN are not set. Get them from https://astra.datastax.com and export them.");
        }

        DataAPIClient client = new DataAPIClient(properties.astraToken());
        Database db = client.getDatabase(
                UUID.fromString(properties.astraDatabaseId()),
                properties.astraRegion(),
                new DatabaseOptions().token(properties.astraToken()).keyspace(properties.astraKeyspace()));

        if (!db.getDatabaseAdmin().keyspaceExists(properties.astraKeyspace())) {
            log.info("Creating Astra keyspace '{}'", properties.astraKeyspace());
            db.getDatabaseAdmin().createKeyspace(properties.astraKeyspace());
            awaitKeyspaceReady(db, properties.astraKeyspace());
        }

        if (!db.collectionExists(COLLECTION_NAME)) {
            log.info("Creating Astra collection '{}' (provider={}, model={}, dim={})",
                    COLLECTION_NAME, properties.embeddingProvider(), properties.embeddingModel(),
                    properties.embeddingDimension());
            db.createCollection(COLLECTION_NAME, new CollectionDefinition()
                    .vector(properties.embeddingDimension(), SimilarityMetric.COSINE)
                    .vectorize(properties.embeddingProvider(), properties.embeddingModel()));
        }

        return db.getCollection(COLLECTION_NAME);
    }

    /**
     * createKeyspace() only kicks off provisioning on Astra's backend - it
     * isn't immediately queryable. Poll keyspaceExists() until it is (or give
     * up after ~30s) rather than racing the very next call against it.
     */
    private void awaitKeyspaceReady(Database db, String keyspace) {
        for (int i = 0; i < 15; i++) {
            if (db.getDatabaseAdmin().keyspaceExists(keyspace)) return;
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        log.warn("Astra keyspace '{}' did not become ready within the expected time", keyspace);
    }
}
