-- Transactions Table
CREATE TABLE IF NOT EXISTS transactions (
id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
transaction_reference VARCHAR(50) NOT NULL UNIQUE,
customer_id VARCHAR(50) NOT NULL,
amount NUMERIC(19, 4) NOT NULL CHECK (amount > 0),
currency VARCHAR(3) NOT NULL,
sender_account VARCHAR(50) NOT NULL,
receiver_account VARCHAR(50) NOT NULL,
description VARCHAR(500),
status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
updated_at TIMESTAMP,
version BIGINT NOT NULL DEFAULT 0
);
-- Indexes
CREATE INDEX idx_transaction_reference ON transactions(transaction_reference);
CREATE INDEX idx_transaction_customer_id ON transactions(customer_id);
CREATE INDEX idx_transaction_created_at ON transactions(created_at);
CREATE INDEX idx_transaction_status ON transactions(status);
-- Comments
COMMENT ON TABLE transactions IS 'Business transactions table';
COMMENT ON COLUMN transactions.transaction_reference IS 'Unique transaction reference number';
COMMENT ON COLUMN transactions.status IS 'Transaction status: PENDING, APPROVED, REJECTED, COMPLETED, FAILED';
