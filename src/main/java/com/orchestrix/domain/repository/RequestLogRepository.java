package com.orchestrix.domain.repository;

import com.orchestrix.domain.entity.RequestLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
public interface RequestLogRepository extends JpaRepository<RequestLog, Long> {

    List<RequestLog> findTop50ByTenantIdOrderByCreatedAtDesc(String tenantId);

    long countByTenantIdAndCreatedAtAfter(String tenantId, Instant after);
}
