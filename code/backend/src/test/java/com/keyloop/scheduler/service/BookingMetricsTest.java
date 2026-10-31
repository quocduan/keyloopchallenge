package com.keyloop.scheduler.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

import com.keyloop.scheduler.api.BookingRequests;
import com.keyloop.scheduler.domain.*;
import com.keyloop.scheduler.repository.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.locks.Lock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.integration.support.locks.LockRegistry;

class BookingMetricsTest {
    private final DealershipRepository dealerships = mock(DealershipRepository.class);
    private final VehicleRepository vehicles = mock(VehicleRepository.class);
    private final ServiceTypeRepository services = mock(ServiceTypeRepository.class);
    private final TechnicianRepository technicians = mock(TechnicianRepository.class);
    private final ServiceBayRepository bays = mock(ServiceBayRepository.class);
    private final AppointmentRepository appointments = mock(AppointmentRepository.class);
    private final LockRegistry locks = mock(LockRegistry.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final BookingService service = new BookingService(dealerships, vehicles, services, technicians,
            bays, appointments, locks, meters);
    private final LocalDateTime start = LocalDateTime.now().plusDays(1);

    @BeforeEach
    void inputs() {
        Dealership dealership = mock(Dealership.class);
        Vehicle vehicle = mock(Vehicle.class);
        ServiceType type = mock(ServiceType.class);
        when(dealership.getId()).thenReturn(1L);
        when(vehicle.getId()).thenReturn(2L);
        when(type.getId()).thenReturn(3L);
        when(type.getDurationMinutes()).thenReturn(60);
        when(dealerships.findById(1L)).thenReturn(Optional.of(dealership));
        when(vehicles.findById(2L)).thenReturn(Optional.of(vehicle));
        when(vehicles.findByIdForUpdateNowait(2L)).thenReturn(Optional.of(vehicle));
        when(services.findById(3L)).thenReturn(Optional.of(type));
    }

    @ParameterizedTest
    @CsvSource({"true,false,false,vehicle_busy", "false,false,false,resources_busy",
            "false,true,false,resources_busy", "false,true,true,available"})
    void availabilityRecordsOutcomesAndSearchWork(boolean vehicleBusy, boolean technicianFree,
                                                  boolean bayFree, String outcome) {
        when(appointments.vehicleHasOverlap(anyLong(), any(), any(), any())).thenReturn(vehicleBusy);
        when(technicians.findAvailable(anyLong(), anyLong(), anyLong(), any(), any(), any(), any()))
                .thenReturn(technicianFree ? List.of(mock(Technician.class)) : List.of());
        when(bays.findAvailable(anyLong(), anyLong(), any(), any(), any(), any()))
                .thenReturn(bayFree ? List.of(mock(ServiceBay.class)) : List.of());

        service.availability(1, 2, 3, start);

        assertThat(meters.get("booking.availability").tag("outcome", outcome).timer().count()).isEqualTo(1);
        if (vehicleBusy) {
            assertThat(meters.find("booking.resource.search").timer()).isNull();
        } else {
            assertThat(meters.get("booking.resource.search")
                    .tag("outcome", technicianFree && bayFree ? "found" : "unavailable").timer().count()).isEqualTo(1);
            assertChecks("technician", 1);
            assertChecks("bay", technicianFree ? 1 : 0);
        }
    }

    @Test
    void failedSearchStillRecordsDurationAndAttemptedQueries() {
        when(technicians.findAvailable(anyLong(), anyLong(), anyLong(), any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("database unavailable"));

        assertThatThrownBy(() -> service.availability(1, 2, 3, start)).isInstanceOf(IllegalStateException.class);

        assertThat(meters.get("booking.availability").tag("outcome", "error").timer().count()).isEqualTo(1);
        assertThat(meters.get("booking.resource.search").tag("outcome", "error").timer().count()).isEqualTo(1);
        assertChecks("technician", 1);
        assertChecks("bay", 0);
    }

    @Test
    void bookingCountsCandidateQueriesAndPostLockRechecks() {
        Technician first = mock(Technician.class);
        Technician second = mock(Technician.class);
        when(first.getId()).thenReturn(10L);
        when(second.getId()).thenReturn(11L);
        when(technicians.findAvailableForUpdateSkipLocked(anyLong(), anyLong(), anyLong(), any(), any(), any(), any()))
                .thenReturn(List.of(first), List.of(second));
        when(appointments.technicianHasOverlap(eq(10L), any(), any(), any())).thenReturn(true);
        when(bays.findAvailableForUpdateSkipLocked(anyLong(), anyLong(), any(), any(), any(), any()))
                .thenReturn(List.of());

        assertThatThrownBy(() -> service.book(request())).isInstanceOf(BookingException.class);

        assertLock("vehicle", "acquired", 1);
        assertLock("technician", "acquired", 2);
        assertLock("bay", "unavailable", 1);
        assertChecks("technician", 4);
        assertChecks("bay", 1);
    }

    @Test
    void vehicleContentionIsRecordedBeforeConflictIsReturned() {
        when(vehicles.findByIdForUpdateNowait(2L)).thenThrow(new PessimisticLockingFailureException("busy"));
        assertThatThrownBy(() -> service.book(request())).isInstanceOf(BookingException.class);
        assertLock("vehicle", "contended", 1);
    }

    @ParameterizedTest
    @CsvSource({"true,acquired", "false,contended"})
    void pollingMeasuresIdempotencyAcquisitionAndReleasesOnlyAcquiredLock(boolean acquired, String outcome) {
        Lock lock = mock(Lock.class);
        when(locks.obtain(any())).thenReturn(lock);
        when(lock.tryLock()).thenReturn(acquired);
        when(appointments.findDetailedByIdempotencyKey("key")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getIdempotentResult("key")).isInstanceOf(BookingException.class);

        assertLock("idempotency", outcome, 1);
        verify(lock, times(acquired ? 1 : 0)).unlock();
    }

    @Test
    void bookingMeasuresIdempotencyContention() {
        Lock lock = mock(Lock.class);
        when(locks.obtain(any())).thenReturn(lock);
        assertThatThrownBy(() -> service.book(request(), "key")).isInstanceOf(BookingException.class);
        assertLock("idempotency", "contended", 1);
        verify(lock, never()).unlock();
    }

    @Test
    void redisFailureIsRecordedAndPropagated() {
        when(locks.obtain(any())).thenThrow(new IllegalStateException("redis unavailable"));
        assertThatThrownBy(() -> service.getIdempotentResult("key")).isInstanceOf(IllegalStateException.class);
        assertLock("idempotency", "error", 1);
    }

    private BookingRequests.AppointmentRequest request() {
        return new BookingRequests.AppointmentRequest(1L, 2L, 3L, start);
    }

    private void assertChecks(String resource, int expected) {
        var summary = meters.get("booking.resource.overlap.checks").tag("resource", resource).summary();
        assertThat(summary.count()).isEqualTo(1);
        assertThat(summary.totalAmount()).isEqualTo(expected);
    }

    private void assertLock(String resource, String outcome, int expected) {
        assertThat(meters.get("booking.lock.wait").tags("resource", resource, "outcome", outcome)
                .timer().count()).isEqualTo(expected);
    }
}
