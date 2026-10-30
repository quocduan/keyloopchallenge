package com.keyloop.scheduler.repository;

import com.keyloop.scheduler.domain.AppointmentStatus;
import com.keyloop.scheduler.domain.Technician;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.hibernate.LockOptions;
import org.hibernate.jpa.SpecHints;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

public interface TechnicianRepository extends JpaRepository<Technician, Long> {
    String AVAILABLE_QUERY = """
            select t from Technician t
            where t.dealership.id = :dealershipId and t.id > :afterId
            and t.serviceType.id = :serviceTypeId
            and not exists (
                select a.id from Appointment a
                where a.technician.id = t.id and a.status = :status
                  and a.startsAt < :requestedEnd and a.endsAt > :requestedStart
            )
            order by t.id
            """;

    @Query(AVAILABLE_QUERY)
    List<Technician> findAvailable(
            @Param("dealershipId") Long dealershipId,
            @Param("serviceTypeId") Long serviceTypeId,
            @Param("afterId") Long afterId, @Param("status") AppointmentStatus status,
            @Param("requestedStart") LocalDateTime requestedStart,
            @Param("requestedEnd") LocalDateTime requestedEnd, Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = SpecHints.HINT_SPEC_LOCK_TIMEOUT, value = "" + LockOptions.SKIP_LOCKED))
    @Query(AVAILABLE_QUERY)
    List<Technician> findAvailableForUpdateSkipLocked(
            @Param("dealershipId") Long dealershipId,
            @Param("serviceTypeId") Long serviceTypeId,
            @Param("afterId") Long afterId, @Param("status") AppointmentStatus status,
            @Param("requestedStart") LocalDateTime requestedStart,
            @Param("requestedEnd") LocalDateTime requestedEnd, Pageable pageable);
}
