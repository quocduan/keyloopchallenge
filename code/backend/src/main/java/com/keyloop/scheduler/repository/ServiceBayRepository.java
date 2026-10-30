package com.keyloop.scheduler.repository;

import com.keyloop.scheduler.domain.ServiceBay;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Pageable;

public interface ServiceBayRepository extends JpaRepository<ServiceBay, Long> {
    List<ServiceBay> findAllByDealership_IdOrderByIdAsc(Long dealershipId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    @Query("select b from ServiceBay b where b.dealership.id = :dealershipId " +
            "and b.id > :afterId order by b.id")
    List<ServiceBay> findCandidatesByDealershipAfterId(@Param("dealershipId") Long dealershipId,
                                                       @Param("afterId") Long afterId, Pageable pageable);
}
