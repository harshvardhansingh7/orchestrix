package com.orchestrix.domain.repository;

import com.orchestrix.domain.entity.TenantUsage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface TenantUsageRepository extends JpaRepository<TenantUsage, Long> {

    Optional<TenantUsage> findByTenantIdAndUsageDate(String tenantId, LocalDate usageDate);

    List<TenantUsage> findByTenantIdAndUsageDateBetween(String tenantId, LocalDate from, LocalDate to);
}
