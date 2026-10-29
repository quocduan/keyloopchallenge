package com.keyloop.scheduler.repository;

import com.keyloop.scheduler.domain.Appointment;
import com.keyloop.scheduler.domain.AppointmentStatus;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AppointmentRepository extends JpaRepository<Appointment, Long> {
    @Query("""
            select (count(a) > 0) from Appointment a
            where a.vehicle.id = :vehicleId and a.status = :status
              and a.startsAt < :requestedEnd and a.endsAt > :requestedStart
            """)
    boolean vehicleHasOverlap(@Param("vehicleId") Long vehicleId,
                              @Param("status") AppointmentStatus status,
                              @Param("requestedStart") LocalDateTime requestedStart,
                              @Param("requestedEnd") LocalDateTime requestedEnd);

    @Query("""
            select (count(a) > 0) from Appointment a
            where a.technician.id = :technicianId and a.status = :status
              and a.startsAt < :requestedEnd and a.endsAt > :requestedStart
            """)
    boolean technicianHasOverlap(@Param("technicianId") Long technicianId,
                                 @Param("status") AppointmentStatus status,
                                 @Param("requestedStart") LocalDateTime requestedStart,
                                 @Param("requestedEnd") LocalDateTime requestedEnd);

    @Query("""
            select (count(a) > 0) from Appointment a
            where a.serviceBay.id = :serviceBayId and a.status = :status
              and a.startsAt < :requestedEnd and a.endsAt > :requestedStart
            """)
    boolean serviceBayHasOverlap(@Param("serviceBayId") Long serviceBayId,
                                 @Param("status") AppointmentStatus status,
                                 @Param("requestedStart") LocalDateTime requestedStart,
                                 @Param("requestedEnd") LocalDateTime requestedEnd);

    @Query("""
            select a from Appointment a
            join fetch a.dealership
            join fetch a.vehicle
            join fetch a.serviceType
            join fetch a.technician
            join fetch a.serviceBay
            where a.id = :id
            """)
    Optional<Appointment> findDetailedById(@Param("id") Long id);
}
