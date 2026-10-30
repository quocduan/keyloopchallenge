package com.keyloop.scheduler.repository;

import com.keyloop.scheduler.domain.Vehicle;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;


public interface VehicleRepository extends JpaRepository<Vehicle, Long> {
    java.util.List<Vehicle> findAllByOrderByMakeAscModelAsc();

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "0"))
    @Query("select v from Vehicle v where v.id = :id")
    Optional<Vehicle> findByIdForUpdateNowait(@Param("id") Long id);
}
