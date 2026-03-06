-- Migrate vector store from OpenAI (1536 dim) to Ollama nomic-embed-text (768 dim)
-- Mevcut index ve kolon kaldırılıp yeni boyutla yeniden oluşturuluyor
DROP INDEX IF EXISTS idx_vector_store_embedding;
ALTER TABLE vector_store DROP COLUMN IF EXISTS embedding;
ALTER TABLE vector_store ADD COLUMN embedding vector(768);
CREATE INDEX idx_vector_store_embedding ON vector_store
  USING ivfflat (embedding vector_cosine_ops)
  WITH (lists = 100);
COMMENT ON COLUMN vector_store.embedding
  IS 'Vector embedding (768 dimensions for nomic-embed-text via Ollama)';
