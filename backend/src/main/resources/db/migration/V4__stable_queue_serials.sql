-- Keep the public daily serial stable when an admin reorders the queue.
-- A null priority falls back to the entry ID for new approvals.
ALTER TABLE print_queue_entries ADD COLUMN sort_priority BIGINT;

WITH ranked AS (
    SELECT id, row_number() OVER (
        PARTITION BY shop_id ORDER BY queue_date, queue_number
    ) AS priority
    FROM print_queue_entries
)
UPDATE print_queue_entries AS entry SET sort_priority = ranked.priority
FROM ranked WHERE entry.id = ranked.id;

-- Expanded selections can contain hundreds of comma-separated page numbers.
ALTER TABLE print_configs ALTER COLUMN page_selection TYPE TEXT;

-- Orders left by earlier versions still need a retention deadline.
UPDATE orders AS purchase
SET purge_at = purchase.created_at + settings.file_retention_hours * INTERVAL '1 hour'
FROM shop_settings AS settings
WHERE settings.shop_id = purchase.shop_id AND purchase.purge_at IS NULL
AND EXISTS (SELECT 1 FROM order_files AS file
            WHERE file.order_id = purchase.id AND file.processing_status <> 'PURGED');

CREATE INDEX idx_orders_purge_due ON orders(purge_at) WHERE purge_at IS NOT NULL;

-- A retry after an interrupted HTTP response can recover the original order.
ALTER TABLE orders ADD COLUMN request_key UUID;
CREATE UNIQUE INDEX ux_orders_shop_request_key ON orders(shop_id, request_key)
WHERE request_key IS NOT NULL;
