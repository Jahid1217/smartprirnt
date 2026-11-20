-- Seed the pilot shop referenced by smartprint.shop-code (default SHOP001).
-- Safe to re-run against databases that already contain the shop.
INSERT INTO shops (shop_code, name, timezone, currency)
VALUES ('SHOP001', 'SmartPrint Pilot Shop', 'Asia/Dhaka', 'BDT')
ON CONFLICT (shop_code) DO NOTHING;

-- Integrity checksum of the uploaded original, computed at upload time.
ALTER TABLE order_files ADD COLUMN checksum_sha256 VARCHAR(64);

-- Payment review audit fields.
ALTER TABLE payments
    ADD COLUMN updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    ADD COLUMN verified_at TIMESTAMPTZ,
    ADD COLUMN rejection_reason TEXT;

-- A provider transaction reference may only back one payment.
CREATE UNIQUE INDEX ux_payments_provider_transaction
    ON payments (provider, transaction_id)
    WHERE transaction_id IS NOT NULL;

CREATE INDEX idx_payments_status ON payments (status, created_at);
CREATE INDEX idx_orders_created_at ON orders (created_at DESC);
CREATE INDEX idx_order_files_order ON order_files (order_id);
