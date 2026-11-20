package com.smartprint.printer;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;

@Repository
class PrintAttemptHistory {
    private final JdbcTemplate jdbc;

    PrintAttemptHistory(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    void started(PrintJob job) {
        jdbc.update("""
            insert into print_job_attempts
                (print_job_id, attempt_number, shop_id, printer_id, agent_id, started_at, status)
            values (?, ?, ?, ?, ?, ?, ?)
            """, job.getId(), job.getAttemptCount(), job.getShopId(), job.getPrinterId(),
            job.getAgentId(), Timestamp.from(job.getClaimedAt()), job.getStatus());
    }

    void finished(PrintJob job) {
        jdbc.update("""
            update print_job_attempts set finished_at = ?, status = ?, failure_reason = ?
            where print_job_id = ? and attempt_number = ?
            """, Timestamp.from(job.getCompletedAt()), job.getStatus(), job.getFailureReason(),
            job.getId(), job.getAttemptCount());
    }

    List<Map<String, Object>> forOrder(long shopId, String orderNumber) {
        return jdbc.queryForList("""
            select j.order_file_id as "fileId", f.original_name as "fileName",
                   a.attempt_number as "attemptNumber",
                   p.name as "printerName", a.started_at as "startedAt",
                   a.finished_at as "finishedAt", a.status, a.failure_reason as "failureReason"
            from print_job_attempts a join print_jobs j on j.id = a.print_job_id
            join order_files f on f.id = j.order_file_id
            join print_queue_entries q on q.id = j.queue_entry_id
            join orders o on o.id = q.order_id
            left join printers p on p.id = a.printer_id
            where a.shop_id = ? and o.order_number = ?
            order by a.started_at, a.print_job_id, a.attempt_number
            """, shopId, orderNumber);
    }
}
