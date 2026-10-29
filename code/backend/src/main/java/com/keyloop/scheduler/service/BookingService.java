package com.keyloop.scheduler.service;

import com.keyloop.scheduler.api.ApiModels;
import com.keyloop.scheduler.api.BookingRequests;
import com.keyloop.scheduler.domain.Appointment;
import com.keyloop.scheduler.domain.AppointmentStatus;
import com.keyloop.scheduler.domain.Dealership;
import com.keyloop.scheduler.domain.ServiceBay;
import com.keyloop.scheduler.domain.ServiceType;
import com.keyloop.scheduler.domain.Technician;
import com.keyloop.scheduler.domain.Vehicle;
import com.keyloop.scheduler.repository.AppointmentRepository;
import com.keyloop.scheduler.repository.DealershipRepository;
import com.keyloop.scheduler.repository.ServiceBayRepository;
import com.keyloop.scheduler.repository.ServiceTypeRepository;
import com.keyloop.scheduler.repository.TechnicianRepository;
import com.keyloop.scheduler.repository.VehicleRepository;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.integration.support.locks.LockRegistry;

@Service
public class BookingService {
    private final DealershipRepository dealershipRepository;
    private final VehicleRepository vehicleRepository;
    private final ServiceTypeRepository serviceTypeRepository;
    private final TechnicianRepository technicianRepository;
    private final ServiceBayRepository serviceBayRepository;
    private final AppointmentRepository appointmentRepository;
    private final LockRegistry bookingLockRegistry;

    public BookingService(DealershipRepository dealershipRepository,
                          VehicleRepository vehicleRepository,
                          ServiceTypeRepository serviceTypeRepository,
                          TechnicianRepository technicianRepository,
                          ServiceBayRepository serviceBayRepository,
                          AppointmentRepository appointmentRepository,
                          LockRegistry bookingLockRegistry) {
        this.dealershipRepository = dealershipRepository;
        this.vehicleRepository = vehicleRepository;
        this.serviceTypeRepository = serviceTypeRepository;
        this.technicianRepository = technicianRepository;
        this.serviceBayRepository = serviceBayRepository;
        this.appointmentRepository = appointmentRepository;
        this.bookingLockRegistry = bookingLockRegistry;
    }

    @Transactional(readOnly = true)
    public ApiModels.ReferenceData referenceData() {
        var dealerships = dealershipRepository.findAllByOrderByNameAsc().stream()
                .map(dealership -> new ApiModels.NamedOption(dealership.getId(), dealership.getName())).toList();
        var vehicles = vehicleRepository.findAllByOrderByMakeAscModelAsc().stream()
                .map(vehicle -> new ApiModels.VehicleOption(vehicle.getId(), vehicle.getVin(), vehicle.getLabel())).toList();
        var serviceTypes = serviceTypeRepository.findAllByOrderByNameAsc().stream()
                .map(service -> new ApiModels.ServiceOption(service.getId(), service.getName(), service.getDurationMinutes())).toList();
        return new ApiModels.ReferenceData(dealerships, vehicles, serviceTypes);
    }

    @Transactional(readOnly = true)
    public ApiModels.AvailabilityResult availability(long dealershipId, long vehicleId, long serviceTypeId,
                                                       LocalDateTime startsAt) {
        BookingInputs inputs = loadInputs(dealershipId, vehicleId, serviceTypeId, startsAt);
        LocalDateTime endsAt = startsAt.plusMinutes(inputs.serviceType().getDurationMinutes());
        boolean vehicleBooked = appointmentRepository.vehicleHasOverlap(inputs.vehicle().getId(),
                AppointmentStatus.CONFIRMED, startsAt, endsAt);
        boolean available = !vehicleBooked
                && findResourcePair(inputs.dealership(), inputs.serviceType(), startsAt, endsAt) != null;
        return new ApiModels.AvailabilityResult(available, startsAt, endsAt,
                vehicleBooked ? "Vehicle already has a confirmed appointment during the requested time."
                        : available ? "A qualified technician and service bay are available."
                        : "No resource pair is available for the full service duration.");
    }

    @Transactional
    public ApiModels.AppointmentView book(BookingRequests.AppointmentRequest request) {
        acquireBookingLocks(request);
        // The dealership row lock serializes bookings for this dealership through this API.
        Dealership dealership = dealershipRepository.findByIdForUpdate(request.dealershipId())
                .orElseThrow(() -> new BookingException(HttpStatus.NOT_FOUND, "Dealership not found."));
        // Locking the vehicle serializes its bookings even when requests target different dealerships.
        Vehicle vehicle = vehicleRepository.findByIdForUpdate(request.vehicleId())
                .orElseThrow(() -> new BookingException(HttpStatus.NOT_FOUND, "Vehicle not found."));
        BookingInputs inputs = loadInputs(dealership, vehicle, request.serviceTypeId(), request.desiredStart());
        LocalDateTime endsAt = request.desiredStart().plusMinutes(inputs.serviceType().getDurationMinutes());
        if (appointmentRepository.vehicleHasOverlap(vehicle.getId(), AppointmentStatus.CONFIRMED,
                request.desiredStart(), endsAt)) {
            throw new BookingException(HttpStatus.CONFLICT,
                    "Vehicle already has a confirmed appointment during the requested time.");
        }
        ResourcePair pair = findResourcePair(dealership, inputs.serviceType(), request.desiredStart(), endsAt);
        if (pair == null) {
            throw new BookingException(HttpStatus.CONFLICT,
                    "No qualified technician and service bay are both available for the full service duration.");
        }

        Appointment appointment = appointmentRepository.save(new Appointment(
                dealership, inputs.vehicle(), inputs.serviceType(), pair.technician(), pair.serviceBay(),
                request.desiredStart(), endsAt));
        return toView(appointment);
    }

    private void acquireBookingLocks(BookingRequests.AppointmentRequest request) {
        List<String> keys = new ArrayList<>(List.of(
                "dealership:" + request.dealershipId(), "vehicle:" + request.vehicleId()));
        keys.sort(String::compareTo);

        List<Lock> acquired = new ArrayList<>();
        try {
            for (String key : keys) {
                Lock lock = bookingLockRegistry.obtain(key);
                if (!lock.tryLock(10, TimeUnit.SECONDS)) {
                    throw new BookingException(HttpStatus.SERVICE_UNAVAILABLE,
                            "Booking is busy; please retry shortly.");
                }
                acquired.add(lock);
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            releaseLocks(acquired);
            throw new BookingException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Booking lock wait was interrupted; please retry.");
        } catch (RuntimeException ex) {
            releaseLocks(acquired);
            throw ex;
        }

        // Keep distributed locks until the database transaction commits or rolls back.
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                releaseLocks(acquired);
            }
        });
    }

    private void releaseLocks(List<Lock> locks) {
        for (int i = locks.size() - 1; i >= 0; i--) {
            locks.get(i).unlock();
        }
    }

    @Transactional(readOnly = true)
    public ApiModels.AppointmentView getAppointment(long id) {
        Appointment appointment = appointmentRepository.findDetailedById(id)
                .orElseThrow(() -> new BookingException(HttpStatus.NOT_FOUND, "Appointment not found."));
        return toView(appointment);
    }

    private BookingInputs loadInputs(long dealershipId, long vehicleId, long serviceTypeId, LocalDateTime startsAt) {
        Dealership dealership = dealershipRepository.findById(dealershipId)
                .orElseThrow(() -> new BookingException(HttpStatus.NOT_FOUND, "Dealership not found."));
        return loadInputs(dealership, vehicleId, serviceTypeId, startsAt);
    }

    private BookingInputs loadInputs(Dealership dealership, long vehicleId, long serviceTypeId, LocalDateTime startsAt) {
        Vehicle vehicle = vehicleRepository.findById(vehicleId)
                .orElseThrow(() -> new BookingException(HttpStatus.NOT_FOUND, "Vehicle not found."));
        return loadInputs(dealership, vehicle, serviceTypeId, startsAt);
    }

    private BookingInputs loadInputs(Dealership dealership, Vehicle vehicle, long serviceTypeId, LocalDateTime startsAt) {
        ServiceType serviceType = serviceTypeRepository.findById(serviceTypeId)
                .orElseThrow(() -> new BookingException(HttpStatus.NOT_FOUND, "Service type not found."));
        if (startsAt.isBefore(LocalDateTime.now())) {
            throw new BookingException(HttpStatus.BAD_REQUEST, "Appointment start must be in the future.");
        }
        return new BookingInputs(dealership, vehicle, serviceType);
    }

    private ResourcePair findResourcePair(Dealership dealership, ServiceType serviceType,
                                          LocalDateTime startsAt, LocalDateTime endsAt) {
        List<Technician> technicians = technicianRepository
                .findAllByDealership_IdAndServiceType_IdOrderByIdAsc(dealership.getId(), serviceType.getId());
        List<ServiceBay> serviceBays = serviceBayRepository.findAllByDealership_IdOrderByIdAsc(dealership.getId());
        for (Technician technician : technicians) {
            if (appointmentRepository.technicianHasOverlap(technician.getId(), AppointmentStatus.CONFIRMED, startsAt, endsAt)) continue;
            for (ServiceBay serviceBay : serviceBays) {
                if (!appointmentRepository.serviceBayHasOverlap(serviceBay.getId(), AppointmentStatus.CONFIRMED, startsAt, endsAt)) {
                    return new ResourcePair(technician, serviceBay);
                }
            }
        }
        return null;
    }

    private ApiModels.AppointmentView toView(Appointment appointment) {
        return new ApiModels.AppointmentView(appointment.getId(), appointment.getDealership().getId(),
                appointment.getDealership().getName(), appointment.getVehicle().getId(), appointment.getVehicle().getLabel(),
                appointment.getServiceType().getId(), appointment.getServiceType().getName(),
                appointment.getTechnician().getId(), appointment.getTechnician().getName(),
                appointment.getServiceBay().getId(), appointment.getServiceBay().getName(),
                appointment.getStartsAt(), appointment.getEndsAt(), appointment.getStatus());
    }

    private record BookingInputs(Dealership dealership, Vehicle vehicle, ServiceType serviceType) {}
    private record ResourcePair(Technician technician, ServiceBay serviceBay) {}
}
