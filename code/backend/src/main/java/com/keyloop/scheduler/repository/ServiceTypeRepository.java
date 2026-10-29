package com.keyloop.scheduler.repository;

import com.keyloop.scheduler.domain.ServiceType;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ServiceTypeRepository extends JpaRepository<ServiceType, Long> {
    java.util.List<ServiceType> findAllByOrderByNameAsc();
}
