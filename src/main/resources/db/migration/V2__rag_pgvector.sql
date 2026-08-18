-- Requires the pgvector/pgvector Docker image (or `CREATE EXTENSION vector`
-- privileges on a managed Postgres instance that has the extension available,
-- e.g. RDS Postgres 15.2+/Aurora, Supabase, Neon).
CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE documents (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    filename VARCHAR(500) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PROCESSING',
    chunk_count INTEGER,
    created_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_documents_user_id ON documents(user_id);

-- Dimension (1024) must match RagProperties.embeddingDimension and the
-- Voyage model in use (voyage-3-large defaults to 1024, but supports
-- 256/512/1024/2048 via the output_dimension parameter - keep these in sync).
CREATE TABLE document_chunks (
    id UUID PRIMARY KEY,
    document_id UUID NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    content TEXT NOT NULL,
    chunk_index INTEGER,
    embedding vector(1024),
    created_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_document_chunks_user_id ON document_chunks(user_id);

-- Approximate nearest-neighbor index for fast cosine-distance search.
-- ivfflat needs data present to train well; for small/dev datasets an
-- exact scan (no index) is fine. Rebuild/tune `lists` as your corpus grows
-- (rule of thumb: lists ~= sqrt(row_count)).
CREATE INDEX idx_document_chunks_embedding ON document_chunks
    USING ivfflat (embedding vector_cosine_ops) WITH (lists = 100);
