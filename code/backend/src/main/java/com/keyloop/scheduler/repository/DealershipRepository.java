package com.keyloop.scheduler.repository;

import com.keyloop.scheduler.domain.Dealership;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;

public interface DealershipRepository extends JpaRepository<Dealership, Long> {
    Slice<Dealership> findByNameContainingIgnoreCase(String name, Pageable pageable);
}
