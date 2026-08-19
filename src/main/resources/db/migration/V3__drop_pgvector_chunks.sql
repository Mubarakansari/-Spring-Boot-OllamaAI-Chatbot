-- Chunk storage + vector search moved to Astra DB (see AstraChunkStore).
-- The `documents` table stays here - it's just status-tracking metadata
-- tied to `users` via a normal FK, not a vector concern.
DROP TABLE IF EXISTS document_chunks;
DROP EXTENSION IF EXISTS vector;
