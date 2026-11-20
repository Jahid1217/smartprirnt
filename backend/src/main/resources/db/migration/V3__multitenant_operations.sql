-- Multi-shop onboarding, per-shop settings, secure customer links, queue and agent jobs.

INSERT INTO roles (name) VALUES ('ADMIN') ON CONFLICT (name) DO NOTHING;
INSERT INTO roles (name) VALUES ('AGENT') ON CONFLICT (name) DO NOTHING;

-- Admin login identity. Usernames are unique platform-wide and stored lower-case.
ALTER TABLE users ADD COLUMN username VARCHAR(60);
UPDATE users SET username = 'user-' || id WHERE username IS NULL;
ALTER TABLE users ALTER COLUMN username SET NOT NULL;
CREATE UNIQUE INDEX ux_users_username ON users (username);
ALTER TABLE users ADD COLUMN phone VARCHAR(30);
CREATE INDEX idx_users_shop ON users (shop_id);

-- Shop onboarding details and the rotatable QR token.
ALTER TABLE shops ADD COLUMN owner_name VARCHAR(150);
ALTER TABLE shops ADD COLUMN phone VARCHAR(30);
ALTER TABLE shops ADD COLUMN email VARCHAR(150);
ALTER TABLE shops ADD COLUMN qr_token UUID NOT NULL DEFAULT gen_random_uuid();
UPDATE shops SET owner_name = name WHERE owner_name IS NULL;
CREATE UNIQUE INDEX ux_shops_qr_token ON shops (qr_token);

-- Everything an admin can control about the customer experience.
CREATE TABLE shop_settings (
    shop_id BIGINT PRIMARY KEY REFERENCES shops(id) ON DELETE CASCADE,
    bw_per_page NUMERIC(10,2) NOT NULL DEFAULT 2.00 CHECK (bw_per_page >= 0),
    color_per_page NUMERIC(10,2) NOT NULL DEFAULT 10.00 CHECK (color_per_page >= 0),
    duplex_percent INTEGER NOT NULL DEFAULT 100 CHECK (duplex_percent BETWEEN 0 AND 300),
    color_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    duplex_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    landscape_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    page_selection_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    pdf_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    word_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    image_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    cash_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    bkash_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    nagad_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    rocket_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    bkash_number VARCHAR(30) NOT NULL DEFAULT '',
    nagad_number VARCHAR(30) NOT NULL DEFAULT '',
    rocket_number VARCHAR(30) NOT NULL DEFAULT '',
    queue_paused BOOLEAN NOT NULL DEFAULT FALSE,
    file_retention_hours INTEGER NOT NULL DEFAULT 24 CHECK (file_retention_hours BETWEEN 1 AND 720),
    default_printer_id BIGINT,
    color_printer_id BIGINT,
    bw_printer_id BIGINT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    FOREIGN KEY (default_printer_id) REFERENCES printers(id) ON DELETE SET NULL,
    FOREIGN KEY (color_printer_id) REFERENCES printers(id) ON DELETE SET NULL,
    FOREIGN KEY (bw_printer_id) REFERENCES printers(id) ON DELETE SET NULL
);

INSERT INTO shop_settings (shop_id) SELECT id FROM shops ON CONFLICT (shop_id) DO NOTHING;

-- Customer order access uses an unguessable token, never the order number alone.
ALTER TABLE orders ADD COLUMN public_token UUID NOT NULL DEFAULT gen_random_uuid();
CREATE UNIQUE INDEX ux_orders_public_token ON orders (public_token);
ALTER TABLE orders ADD COLUMN customer_name VARCHAR(150);
ALTER TABLE orders ADD COLUMN customer_phone VARCHAR(30);
ALTER TABLE orders ADD COLUMN purge_at TIMESTAMPTZ;

-- Page selection and the exact number of pages charged.
ALTER TABLE print_configs ADD COLUMN page_selection VARCHAR(200) NOT NULL DEFAULT 'ALL';
ALTER TABLE print_configs ADD COLUMN page_count INTEGER;
ALTER TABLE print_configs ADD COLUMN selected_page_count INTEGER;

ALTER TABLE print_queue_entries ADD COLUMN started_at TIMESTAMPTZ;
ALTER TABLE print_queue_entries ADD COLUMN completed_at TIMESTAMPTZ;

ALTER TABLE print_jobs ADD COLUMN agent_id BIGINT REFERENCES print_agents(id);
ALTER TABLE print_jobs ADD COLUMN claimed_at TIMESTAMPTZ;
ALTER TABLE print_jobs ADD COLUMN completed_at TIMESTAMPTZ;

ALTER TABLE printers ADD COLUMN color_supported BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE printers ADD COLUMN duplex_supported BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE printers ADD COLUMN last_seen_at TIMESTAMPTZ;

ALTER TABLE print_agents ADD COLUMN name VARCHAR(150);
ALTER TABLE print_agents ADD COLUMN created_at TIMESTAMPTZ NOT NULL DEFAULT NOW();
ALTER TABLE print_agents ADD COLUMN last_printer_count INTEGER NOT NULL DEFAULT 0;

CREATE INDEX idx_orders_public_token ON orders (public_token);
CREATE INDEX idx_queue_active ON print_queue_entries (shop_id, queue_date, status, queue_number);
CREATE INDEX idx_print_jobs_queue ON print_jobs (queue_entry_id, status);
CREATE INDEX idx_print_agents_shop ON print_agents (shop_id, status);
CREATE INDEX idx_printers_shop ON printers (shop_id, enabled);
