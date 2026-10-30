package com.keyloop.scheduler.repository;

import com.keyloop.scheduler.domain.AppointmentStatus;
import com.keyloop.scheduler.domain.ServiceBay;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

public interface ServiceBayRepository extends JpaRepository<ServiceBay, Long> {
    String AVAILABLE_QUERY = """
            select b from ServiceBay b
            where b.dealership.id = :dealershipId and b.id > :afterId
            and not exists (
                select a.id from Appointment a
                where a.serviceBay.id = b.id and a.status = :status
                  and a.startsAt < :requestedEnd and a.endsAt > :requestedStart
            )
            order by b.id
            """;

    @Query(AVAILABLE_QUERY)
    List<ServiceBay> findAvailable(
            @Param("dealershipId") Long dealershipId,
            @Param("afterId") Long afterId, @Param("status") AppointmentStatus status,
            @Param("requestedStart") LocalDateTime requestedStart,
            @Param("requestedEnd") LocalDateTime requestedEnd, Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    @Query(AVAILABLE_QUERY)
    List<ServiceBay> findAvailableForUpdateSkipLocked(
            @Param("dealershipId") Long dealershipId,
            @Param("afterId") Long afterId, @Param("status") AppointmentStatus status,
            @Param("requestedStart") LocalDateTime requestedStart,
            @Param("requestedEnd") LocalDateTime requestedEnd, Pageable pageable);
}
