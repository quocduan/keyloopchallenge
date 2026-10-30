package com.keyloop.scheduler.repository;

import com.keyloop.scheduler.domain.Dealership;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DealershipRepository extends JpaRepository<Dealership, Long> {
    java.util.List<Dealership> findAllByOrderByNameAsc();
}
