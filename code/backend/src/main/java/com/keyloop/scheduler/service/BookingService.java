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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import org.springframework.http.HttpStatus;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.data.domain.PageRequest;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.integration.support.locks.LockRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import static net.logstash.logback.argument.StructuredArguments.keyValue;

@Service
public class BookingService {
    private static final Logger log = LoggerFactory.getLogger(BookingService.class);
    private final DealershipRepository dealershipRepository;
    private final VehicleRepository vehicleRepository;
    private final ServiceTypeRepository serviceTypeRepository;
    private final TechnicianRepository technicianRepository;
    private final ServiceBayRepository serviceBayRepository;
    private final AppointmentRepository appointmentRepository;
    private final LockRegistry bookingLockRegistry;
    private final MeterRegistry meterRegistry;
    @Value("${scheduler.booking.manual-test-delay-enabled:false}")
    private boolean manualTestDelayEnabled;

    public BookingService(DealershipRepository dealershipRepository,
                          VehicleRepository vehicleRepository,
                          ServiceTypeRepository serviceTypeRepository,
                          TechnicianRepository technicianRepository,
                          ServiceBayRepository serviceBayRepository,
                          AppointmentRepository appointmentRepository,
                          LockRegistry bookingLockRegistry,
                          MeterRegistry meterRegistry) {
        this.dealershipRepository = dealershipRepository;
        this.vehicleRepository = vehicleRepository;
        this.serviceTypeRepository = serviceTypeRepository;
        this.technicianRepository = technicianRepository;
        this.serviceBayRepository = serviceBayRepository;
        this.appointmentRepository = appointmentRepository;
        this.bookingLockRegistry = bookingLockRegistry;
        this.meterRegistry = meterRegistry;
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

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ApiModels.AppointmentView book(BookingRequests.AppointmentRequest request) {
        return book(request, null);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ApiModels.AppointmentView book(BookingRequests.AppointmentRequest request, String idempotencyKey) {
        return book(request, idempotencyKey, false);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ApiModels.AppointmentView book(BookingRequests.AppointmentRequest request, String idempotencyKey,
                                          boolean manualDelay) {
        if (idempotencyKey != null && !idempotencyKey.matches("[\\x21-\\x7E]{1,128}")) {
            throw new BookingException(HttpStatus.BAD_REQUEST,
                    "Idempotency-Key must contain 1 to 128 visible ASCII characters.");
        }
        String requestFingerprint = idempotencyKey == null ? null : fingerprint(request);
        long startedAtNanos = System.nanoTime();
        String outcome = "success";
        log.info("booking_attempt", keyValue("dealershipId", request.dealershipId()), keyValue("outcome", "attempted"));
        try {
        acquireIdempotencyLock(idempotencyKey);
        if (manualDelay && manualTestDelayEnabled) {
            try {
                Thread.sleep(15_000L);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new BookingException(HttpStatus.SERVICE_UNAVAILABLE,
                        "The manual booking test delay was interrupted; please retry.");
            }
        }
        if (idempotencyKey != null) {
            var existing = appointmentRepository.findDetailedByIdempotencyKey(idempotencyKey);
            if (existing.isPresent()) {
                Appointment appointment = existing.get();
                if (!requestFingerprint.equals(appointment.getRequestFingerprint())) {
                    throw new BookingException(HttpStatus.CONFLICT,
                            "Idempotency-Key was already used with a different request.");
                }
                return toView(appointment);
            }
        }

        Dealership dealership = dealershipRepository.findById(request.dealershipId())
                .orElseThrow(() -> new BookingException(HttpStatus.NOT_FOUND, "Dealership not found."));
        // Vehicle and resource row locks serialize only requests that compete for the same records.
        Vehicle vehicle;
        try {
            vehicle = vehicleRepository.findByIdForUpdateNowait(request.vehicleId())
                    .orElseThrow(() -> new BookingException(HttpStatus.NOT_FOUND, "Vehicle not found."));
        } catch (PessimisticLockingFailureException ex) {
            throw new BookingException(HttpStatus.CONFLICT,
                    "This vehicle is being booked concurrently; retry the request.");
        }
        BookingInputs inputs = loadInputs(dealership, vehicle, request.serviceTypeId(), request.desiredStart());
        LocalDateTime endsAt = request.desiredStart().plusMinutes(inputs.serviceType().getDurationMinutes());
        if (appointmentRepository.vehicleHasOverlap(vehicle.getId(), AppointmentStatus.CONFIRMED,
                request.desiredStart(), endsAt)) {
            throw new BookingException(HttpStatus.CONFLICT,
                    "Vehicle already has a confirmed appointment during the requested time.");
        }
        ResourcePair pair = findResourcePairSkipLocked(dealership, inputs.serviceType(),
                request.desiredStart(), endsAt);
        if (pair == null) {
            throw new BookingException(HttpStatus.CONFLICT,
                    "No qualified technician and service bay are both available for the full service duration.");
        }

        // SKIP LOCKED acquired both rows before returning the pair; verify current appointment data under those locks.
        if (appointmentRepository.technicianHasOverlap(pair.technician().getId(), AppointmentStatus.CONFIRMED,
                request.desiredStart(), endsAt)
                || appointmentRepository.serviceBayHasOverlap(pair.serviceBay().getId(), AppointmentStatus.CONFIRMED,
                request.desiredStart(), endsAt)) {
            // A committed booking may have appeared between the availability read and row lock.
            // The locks cannot be released before transaction completion, so fail fast for a client retry.
            throw new BookingException(HttpStatus.CONFLICT,
                    "The selected resources were just booked; retry the booking.");
        }

        Appointment appointment = appointmentRepository.save(new Appointment(
                dealership, inputs.vehicle(), inputs.serviceType(), pair.technician(), pair.serviceBay(),
                request.desiredStart(), endsAt, idempotencyKey, requestFingerprint));
        log.info("booking_completed", keyValue("bookingId", appointment.getId()),
                keyValue("dealershipId", dealership.getId()), keyValue("outcome", "success"));
        return toView(appointment);
        } catch (BookingException ex) {
            outcome = ex.getStatus() == HttpStatus.CONFLICT ? "conflict" : "rejected";
            if (ex.getStatus().is4xxClientError()) {
                log.warn("booking_rejected", keyValue("dealershipId", request.dealershipId()),
                        keyValue("outcome", ex.getStatus() == HttpStatus.CONFLICT ? "conflict" : "rejected"),
                        keyValue("status", ex.getStatus().value()));
            }
            throw ex;
        } catch (RuntimeException ex) {
            outcome = "error";
            log.error("booking_failed", keyValue("dealershipId", request.dealershipId()),
                    keyValue("outcome", "error"), keyValue("errorType", ex.getClass().getSimpleName()), ex);
            throw ex;
        } finally {
            meterRegistry.counter("booking.requests", "outcome", outcome).increment();
            meterRegistry.timer("booking.duration", "outcome", outcome)
                    .record(System.nanoTime() - startedAtNanos, TimeUnit.NANOSECONDS);
        }
    }

    private String fingerprint(BookingRequests.AppointmentRequest request) {
        String canonical = request.dealershipId() + "|" + request.vehicleId() + "|"
                + request.serviceTypeId() + "|" + request.desiredStart();
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable.", ex);
        }
    }

    @Transactional(readOnly = true)
    public ApiModels.AppointmentView getIdempotentResult(String idempotencyKey) {
        validateIdempotencyKey(idempotencyKey);
        Lock lock = bookingLockRegistry.obtain("idempotency:" + fingerprintKey(idempotencyKey));
        if (!lock.tryLock()) {
            throw new BookingException(HttpStatus.ACCEPTED,
                    "The request is being processed. Poll this endpoint again with the same Idempotency-Key.");
        }
        try {
            return appointmentRepository.findDetailedByIdempotencyKey(idempotencyKey)
                    .map(this::toView)
                    .orElseThrow(() -> new BookingException(HttpStatus.NOT_FOUND,
                            "No request was found for this Idempotency-Key."));
        } finally {
            lock.unlock();
        }
    }

    private void validateIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || !idempotencyKey.matches("[\\x21-\\x7E]{1,128}")) {
            throw new BookingException(HttpStatus.BAD_REQUEST,
                    "Idempotency-Key must contain 1 to 128 visible ASCII characters.");
        }
    }

    private void acquireIdempotencyLock(String idempotencyKey) {
        if (idempotencyKey == null) return;
        Lock lock = bookingLockRegistry.obtain("idempotency:" + fingerprintKey(idempotencyKey));
        if (!lock.tryLock()) {
            throw new BookingException(HttpStatus.ACCEPTED,
                    "The request is being processed. Poll GET /api/appointments/idempotency-result " +
                            "with the same Idempotency-Key.");
        }

        // Keep the idempotency lock until the database transaction commits or rolls back.
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                lock.unlock();
            }
        });
    }

    private String fingerprintKey(String key) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(key.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable.", ex);
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

    private ResourcePair findResourcePairSkipLocked(Dealership dealership, ServiceType serviceType,
                                                     LocalDateTime startsAt, LocalDateTime endsAt) {
        long technicianCursor = 0;
        while (true) {
            List<Technician> technicianCandidates = technicianRepository.findCandidatesByDealershipAndServiceTypeAfterId(
                    dealership.getId(), serviceType.getId(), technicianCursor, PageRequest.of(0, 1));
            Technician technician = technicianCandidates.isEmpty() ? null : technicianCandidates.get(0);
            if (technician == null) return null;
            technicianCursor = technician.getId();

            if (appointmentRepository.technicianHasOverlap(technician.getId(), AppointmentStatus.CONFIRMED,
                    startsAt, endsAt)) continue;

            long bayCursor = 0;
            while (true) {
                List<ServiceBay> bayCandidates = serviceBayRepository.findCandidatesByDealershipAfterId(
                        dealership.getId(), bayCursor, PageRequest.of(0, 1));
                ServiceBay serviceBay = bayCandidates.isEmpty() ? null : bayCandidates.get(0);
                if (serviceBay == null) break;
                bayCursor = serviceBay.getId();

                if (!appointmentRepository.serviceBayHasOverlap(serviceBay.getId(), AppointmentStatus.CONFIRMED,
                        startsAt, endsAt)) {
                    return new ResourcePair(technician, serviceBay);
                }
            }
        }
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
