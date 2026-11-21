alter table print_agents add column revoked_at timestamptz;

create table print_agent_pairings (
    id uuid primary key,
    shop_id bigint not null references shops(id),
    code_hash varchar(64) not null unique,
    device_name varchar(150) not null,
    expires_at timestamptz not null,
    agent_id bigint references print_agents(id)
);
create index print_agent_pairings_expiry_idx on print_agent_pairings(expires_at);

alter table print_job_attempts add column spooler_reference varchar(40);
alter table print_job_attempts add column spooler_status varchar(20);

alter table print_queue_entries add column selected_printer_id bigint references printers(id);
