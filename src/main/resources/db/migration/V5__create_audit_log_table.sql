-- Audit Log Table (optional)
CREATE TABLE IF NOT EXISTS audit_logs (
id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
entity_type VARCHAR(50) NOT NULL,
entity_id VARCHAR(100) NOT NULL,
action VARCHAR(50) NOT NULL,
user_id VARCHAR(100),
old_value JSONB,
new_value JSONB,
timestamp TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
ip_address VARCHAR(45),
user_agent TEXT
);
-- Indexes
CREATE INDEX idx_audit_entity ON audit_logs(entity_type, entity_id);
CREATE INDEX idx_audit_timestamp ON audit_logs(timestamp);
CREATE INDEX idx_audit_user_id ON audit_logs(user_id);
COMMENT ON TABLE audit_logs IS 'Audit trail for all entity changes';
