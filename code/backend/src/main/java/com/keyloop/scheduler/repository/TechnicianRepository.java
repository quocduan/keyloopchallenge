package com.keyloop.scheduler.repository;

import com.keyloop.scheduler.domain.Technician;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Pageable;

public interface TechnicianRepository extends JpaRepository<Technician, Long> {
    List<Technician> findAllByDealership_IdAndServiceType_IdOrderByIdAsc(Long dealershipId, Long serviceTypeId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    @Query("select t from Technician t where t.dealership.id = :dealershipId " +
            "and t.serviceType.id = :serviceTypeId and t.id > :afterId order by t.id")
    List<Technician> findCandidatesByDealershipAndServiceTypeAfterId(
            @Param("dealershipId") Long dealershipId, @Param("serviceTypeId") Long serviceTypeId,
            @Param("afterId") Long afterId, Pageable pageable);
}
