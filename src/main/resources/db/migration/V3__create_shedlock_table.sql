-- ShedLock Table for distributed locking
CREATE TABLE IF NOT EXISTS shedlock (
name VARCHAR(64) NOT NULL PRIMARY KEY,
lock_until TIMESTAMP NOT NULL,
locked_at TIMESTAMP NOT NULL,
locked_by VARCHAR(255) NOT NULL
);
-- Index for performance
CREATE INDEX idx_shedlock_lock_until ON shedlock(lock_until);
COMMENT ON TABLE shedlock IS 'Distributed scheduler lock table';
