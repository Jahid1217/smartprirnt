CREATE TABLE shops (
 id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 shop_code VARCHAR(50) NOT NULL UNIQUE,
 name VARCHAR(150) NOT NULL,
 address TEXT,
 timezone VARCHAR(60) NOT NULL DEFAULT 'Asia/Dhaka',
 currency VARCHAR(3) NOT NULL DEFAULT 'BDT',
 status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
 created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE roles (
 id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 name VARCHAR(50) NOT NULL UNIQUE
);

CREATE TABLE users (
 id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 shop_id BIGINT REFERENCES shops(id),
 role_id BIGINT REFERENCES roles(id),
 full_name VARCHAR(150) NOT NULL,
 email VARCHAR(150) UNIQUE,
 password_hash TEXT NOT NULL,
 status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
 created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE customer_sessions (
 id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
 shop_id BIGINT NOT NULL REFERENCES shops(id),
 session_token_hash TEXT NOT NULL UNIQUE,
 customer_name VARCHAR(150),
 customer_phone VARCHAR(30),
 expires_at TIMESTAMPTZ NOT NULL,
 revoked_at TIMESTAMPTZ,
 created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE orders (
 id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 shop_id BIGINT NOT NULL REFERENCES shops(id),
 customer_session_id UUID REFERENCES customer_sessions(id),
 order_number VARCHAR(60) NOT NULL UNIQUE,
 configuration_mode VARCHAR(20) NOT NULL CHECK(configuration_mode IN ('COMMON','INDIVIDUAL')),
 collection_method VARCHAR(20) NOT NULL CHECK(collection_method IN ('COUNTER','TOKEN')),
 status VARCHAR(30) NOT NULL DEFAULT 'UPLOADED',
 total_amount NUMERIC(12,2) NOT NULL DEFAULT 0 CHECK(total_amount >= 0),
 currency VARCHAR(3) NOT NULL DEFAULT 'BDT',
 created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
 updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
 -- Required as the target of shop-scoped composite foreign keys.
 UNIQUE (id, shop_id)
);

CREATE TABLE order_files (
 id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 order_id BIGINT NOT NULL REFERENCES orders(id),
 original_name VARCHAR(255) NOT NULL,
 storage_key TEXT NOT NULL UNIQUE,
 mime_type VARCHAR(120) NOT NULL,
 file_size BIGINT NOT NULL CHECK(file_size > 0),
 page_count INTEGER,
 processed_storage_key TEXT,
 processing_status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
 UNIQUE(id, order_id)
);

CREATE TABLE print_configs (
 id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 order_id BIGINT NOT NULL REFERENCES orders(id),
 order_file_id BIGINT NOT NULL UNIQUE,
 color_mode VARCHAR(20) NOT NULL CHECK(color_mode IN ('COLOR','BW')),
 side_mode VARCHAR(20) NOT NULL CHECK(side_mode IN ('SIMPLEX','DUPLEX')),
 orientation VARCHAR(20) NOT NULL CHECK(orientation IN ('PORTRAIT','LANDSCAPE')),
 paper_size VARCHAR(20) NOT NULL DEFAULT 'A4',
 page_selection_mode VARCHAR(20) NOT NULL DEFAULT 'ALL',
 copies INTEGER NOT NULL DEFAULT 1 CHECK(copies BETWEEN 1 AND 100),
 FOREIGN KEY(order_file_id, order_id) REFERENCES order_files(id, order_id)
);

CREATE TABLE payments (
 id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 order_id BIGINT NOT NULL REFERENCES orders(id),
 payment_method VARCHAR(30) NOT NULL,
 provider VARCHAR(50),
 amount NUMERIC(12,2) NOT NULL CHECK(amount > 0),
 status VARCHAR(25) NOT NULL DEFAULT 'PENDING',
 transaction_id VARCHAR(150),
 created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE queue_counters (
 id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 shop_id BIGINT NOT NULL REFERENCES shops(id),
 queue_date DATE NOT NULL,
 next_number INTEGER NOT NULL DEFAULT 1 CHECK(next_number > 0),
 UNIQUE(shop_id, queue_date)
);

CREATE TABLE print_queue_entries (
 id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 shop_id BIGINT NOT NULL REFERENCES shops(id),
 order_id BIGINT NOT NULL UNIQUE,
 queue_date DATE NOT NULL,
 queue_number INTEGER NOT NULL,
 status VARCHAR(25) NOT NULL DEFAULT 'QUEUED',
 approved_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
 UNIQUE(shop_id, queue_date, queue_number),
 UNIQUE(id, shop_id),
 FOREIGN KEY(order_id, shop_id) REFERENCES orders(id, shop_id)
);

CREATE TABLE print_agents (
 id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 shop_id BIGINT NOT NULL REFERENCES shops(id),
 agent_code VARCHAR(80) NOT NULL UNIQUE,
 credential_hash TEXT NOT NULL,
 status VARCHAR(20) NOT NULL DEFAULT 'OFFLINE',
 last_seen_at TIMESTAMPTZ
);

CREATE TABLE printers (
 id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 shop_id BIGINT NOT NULL REFERENCES shops(id),
 agent_id BIGINT REFERENCES print_agents(id),
 printer_code VARCHAR(80) NOT NULL,
 name VARCHAR(150) NOT NULL,
 connection_type VARCHAR(20) NOT NULL,
 printer_uri TEXT NOT NULL,
 status VARCHAR(20) NOT NULL DEFAULT 'OFFLINE',
 enabled BOOLEAN NOT NULL DEFAULT TRUE,
 UNIQUE(shop_id, printer_code),
 UNIQUE(id, shop_id)
);

CREATE TABLE printer_capabilities (
 printer_id BIGINT PRIMARY KEY REFERENCES printers(id),
 color_supported BOOLEAN NOT NULL DEFAULT FALSE,
 duplex_supported BOOLEAN NOT NULL DEFAULT FALSE,
 paper_sizes TEXT[] NOT NULL DEFAULT ARRAY['A4']
);

CREATE TABLE print_jobs (
 id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 shop_id BIGINT NOT NULL REFERENCES shops(id),
 queue_entry_id BIGINT NOT NULL,
 order_file_id BIGINT NOT NULL REFERENCES order_files(id),
 printer_id BIGINT,
 status VARCHAR(25) NOT NULL DEFAULT 'PENDING',
 idempotency_key UUID NOT NULL DEFAULT gen_random_uuid() UNIQUE,
 agent_job_id VARCHAR(150),
 attempt_count INTEGER NOT NULL DEFAULT 0,
 failure_reason TEXT,
 created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
 FOREIGN KEY(queue_entry_id, shop_id) REFERENCES print_queue_entries(id, shop_id),
 FOREIGN KEY(printer_id, shop_id) REFERENCES printers(id, shop_id),
 UNIQUE(queue_entry_id, order_file_id)
);

CREATE INDEX idx_orders_shop_status ON orders(shop_id, status, created_at DESC);
CREATE INDEX idx_print_jobs_status ON print_jobs(status, created_at);
CREATE INDEX idx_queue_ordering ON print_queue_entries(shop_id, queue_date, queue_number);
