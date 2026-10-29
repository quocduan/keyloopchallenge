package com.keyloop.scheduler.repository;

import com.keyloop.scheduler.domain.Dealership;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DealershipRepository extends JpaRepository<Dealership, Long> {
    java.util.List<Dealership> findAllByOrderByNameAsc();

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from Dealership d where d.id = :id")
    Optional<Dealership> findByIdForUpdate(@Param("id") Long id);
}
