package com.orchestrix.domain.repository;

import com.orchestrix.domain.entity.ProviderHealthEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ProviderHealthRepository extends JpaRepository<ProviderHealthEntity, Long> {

    Optional<ProviderHealthEntity> findByProvider(String provider);
}
