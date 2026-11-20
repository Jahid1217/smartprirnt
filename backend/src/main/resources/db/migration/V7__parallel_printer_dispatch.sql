ALTER TABLE printers ADD COLUMN model VARCHAR(200);
ALTER TABLE printers ADD COLUMN pending_jobs INTEGER NOT NULL DEFAULT 0;
ALTER TABLE printers ADD COLUMN active_jobs INTEGER NOT NULL DEFAULT 0;
ALTER TABLE printers ADD COLUMN last_assigned_at TIMESTAMPTZ;

CREATE INDEX idx_print_jobs_reservations ON print_jobs(shop_id, printer_id) WHERE status = 'PRINTING';

CREATE TABLE print_job_attempts (
    print_job_id BIGINT NOT NULL REFERENCES print_jobs(id),
    attempt_number INTEGER NOT NULL,
    shop_id BIGINT NOT NULL REFERENCES shops(id),
    printer_id BIGINT REFERENCES printers(id),
    agent_id BIGINT REFERENCES print_agents(id),
    started_at TIMESTAMPTZ,
    finished_at TIMESTAMPTZ,
    status VARCHAR(25) NOT NULL,
    failure_reason TEXT,
    PRIMARY KEY (print_job_id, attempt_number)
);

INSERT INTO print_job_attempts(print_job_id, attempt_number, shop_id, printer_id, agent_id,
                              started_at, finished_at, status, failure_reason)
SELECT id, attempt_count, shop_id, printer_id, agent_id, claimed_at, completed_at, status, failure_reason
FROM print_jobs WHERE attempt_count > 0 AND status <> 'PENDING';

ALTER TABLE print_queue_entries ADD COLUMN assignment_token UUID;
