package com.keyloop.scheduler.repository;

import com.keyloop.scheduler.domain.ServiceType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;

public interface ServiceTypeRepository extends JpaRepository<ServiceType, Long> {
    Slice<ServiceType> findByNameContainingIgnoreCase(String name, Pageable pageable);
}
