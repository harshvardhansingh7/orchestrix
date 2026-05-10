package com.orchestrix.domain.repository;

import com.orchestrix.domain.entity.Tenant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface TenantRepository extends JpaRepository<Tenant, Long> {

    Optional<Tenant> findByApiKeyHash(String apiKeyHash);

    Optional<Tenant> findByTenantId(String tenantId);
}
