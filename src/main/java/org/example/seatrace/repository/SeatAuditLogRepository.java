package org.example.seatrace.repository;

import org.example.seatrace.entity.SeatAuditLog;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SeatAuditLogRepository extends JpaRepository<SeatAuditLog, Long> {
}
