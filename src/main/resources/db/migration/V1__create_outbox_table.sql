-- Outbox Events Table
CREATE TABLE IF NOT EXISTS outbox_events (
id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
aggregate_id VARCHAR(100) NOT NULL,
event_type VARCHAR(50) NOT NULL,
payload JSONB NOT NULL,
status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
retry_count INTEGER NOT NULL DEFAULT 0,
max_retry INTEGER NOT NULL DEFAULT 3,
created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
updated_at TIMESTAMP,
processed_at TIMESTAMP,
error_message TEXT,
kafka_topic VARCHAR(100),
kafka_key VARCHAR(100),
kafka_partition INTEGER,
kafka_offset BIGINT,
version BIGINT NOT NULL DEFAULT 0
);
-- Indexes for performance
CREATE INDEX idx_outbox_status_created ON outbox_events(status, created_at);
CREATE INDEX idx_outbox_aggregate_id ON outbox_events(aggregate_id);
CREATE INDEX idx_outbox_event_type ON outbox_events(event_type);
CREATE INDEX idx_outbox_processed_at ON outbox_events(processed_at) WHERE processed_at IS NOT NULL;
CREATE INDEX idx_outbox_status_updated ON outbox_events(status, updated_at);
-- Partial index for pending events (most frequently queried)
CREATE INDEX idx_outbox_pending ON outbox_events(created_at)
WHERE status = 'PENDING';
-- Partial index for failed events
CREATE INDEX idx_outbox_failed ON outbox_events(updated_at, retry_count)
WHERE status = 'FAILED';
-- Comments
COMMENT ON TABLE outbox_events IS 'Outbox pattern event store';
COMMENT ON COLUMN outbox_events.aggregate_id IS 'Business entity identifier';
COMMENT ON COLUMN outbox_events.event_type IS 'Type of domain event';
COMMENT ON COLUMN outbox_events.payload IS 'Event payload in JSON format';
COMMENT ON COLUMN outbox_events.status IS 'Event processing status: PENDING, PROCESSING, PROCESSED, FAILED, DEAD_LETTER';
COMMENT ON COLUMN outbox_events.retry_count IS 'Number of retry attempts';
COMMENT ON COLUMN outbox_events.kafka_partition IS 'Kafka partition where message was published';
COMMENT ON COLUMN outbox_events.kafka_offset IS 'Kafka offset of published message';
