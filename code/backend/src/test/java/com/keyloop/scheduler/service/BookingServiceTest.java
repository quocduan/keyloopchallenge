package com.keyloop.scheduler.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.keyloop.scheduler.domain.Dealership;
import com.keyloop.scheduler.repository.AppointmentRepository;
import com.keyloop.scheduler.repository.DealershipRepository;
import com.keyloop.scheduler.repository.ServiceBayRepository;
import com.keyloop.scheduler.repository.ServiceTypeRepository;
import com.keyloop.scheduler.repository.TechnicianRepository;
import com.keyloop.scheduler.repository.VehicleRepository;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.http.HttpStatus;

class BookingServiceTest {
    private DealershipRepository dealershipRepository;
    private VehicleRepository vehicleRepository;
    private ServiceTypeRepository serviceTypeRepository;
    private BookingService service;

    @BeforeEach
    void setUp() {
        dealershipRepository = mock(DealershipRepository.class);
        vehicleRepository = mock(VehicleRepository.class);
        serviceTypeRepository = mock(ServiceTypeRepository.class);
        service = new BookingService(dealershipRepository, vehicleRepository, serviceTypeRepository,
                mock(TechnicianRepository.class), mock(ServiceBayRepository.class), mock(AppointmentRepository.class),
                mock(org.springframework.integration.support.locks.LockRegistry.class), new SimpleMeterRegistry());
    }

    @Test
    void availabilityReturnsNotFoundWhenDealershipDoesNotExist() {
        when(dealershipRepository.findById(17L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.availability(17L, 23L, 5L, futureStart()))
                .isInstanceOfSatisfying(BookingException.class, exception -> {
                    assertThat(exception.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(exception).hasMessage("Dealership not found.");
                });

        verify(dealershipRepository).findById(17L);
    }

    @Test
    void availabilityReturnsNotFoundWhenVehicleDoesNotExist() {
        when(dealershipRepository.findById(17L)).thenReturn(Optional.of(mock(Dealership.class)));
        when(vehicleRepository.findById(23L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.availability(17L, 23L, 5L, futureStart()))
                .isInstanceOfSatisfying(BookingException.class, exception -> {
                    assertThat(exception.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(exception).hasMessage("Vehicle not found.");
                });
    }

    @Test
    void availabilityRejectsStartTimeInThePast() {
        when(dealershipRepository.findById(17L)).thenReturn(Optional.of(mock(Dealership.class)));
        when(vehicleRepository.findById(23L)).thenReturn(Optional.of(mock(com.keyloop.scheduler.domain.Vehicle.class)));
        when(serviceTypeRepository.findById(5L)).thenReturn(Optional.of(mock(com.keyloop.scheduler.domain.ServiceType.class)));

        assertThatThrownBy(() -> service.availability(17L, 23L, 5L, LocalDateTime.now().minusMinutes(1)))
                .isInstanceOfSatisfying(BookingException.class, exception -> {
                    assertThat(exception.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(exception).hasMessage("Appointment start must be in the future.");
                });
    }

    private LocalDateTime futureStart() { return LocalDateTime.now().plusDays(1); }
}
