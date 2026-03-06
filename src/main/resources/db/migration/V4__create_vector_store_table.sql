-- Install pgvector extension (must be done by superuser or in separate migration)
-- CREATE EXTENSION IF NOT EXISTS vector;
-- Vector Store Table for Spring AI
CREATE TABLE IF NOT EXISTS vector_store (
id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
content TEXT NOT NULL,
metadata JSONB,
embedding vector(1536), -- OpenAI text-embedding-3-small dimension
created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
-- Index for vector similarity search
CREATE INDEX idx_vector_store_embedding ON vector_store
USING ivfflat (embedding vector_cosine_ops)
WITH (lists = 100);
-- GIN index for metadata search
CREATE INDEX idx_vector_store_metadata ON vector_store USING GIN (metadata);
COMMENT ON TABLE vector_store IS 'Vector embeddings store for AI-powered search';
COMMENT ON COLUMN vector_store.embedding IS 'Vector embedding (1536 dimensions for OpenAI)';
