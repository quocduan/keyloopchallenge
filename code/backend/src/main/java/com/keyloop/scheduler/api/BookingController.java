package com.keyloop.scheduler.api;

import com.keyloop.scheduler.service.BookingService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import java.time.LocalDateTime;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class BookingController {
    private final BookingService bookingService;
    public BookingController(BookingService bookingService) { this.bookingService = bookingService; }

    @Operation(summary = "Get the first 20 options from each booking catalog")
    @GetMapping("/reference-data")
    public ApiModels.ReferenceData referenceData() { return bookingService.referenceData(); }

    @GetMapping("/reference-data/dealerships")
    public ApiModels.OptionPage<ApiModels.NamedOption> dealerships(@RequestParam(defaultValue = "") String q,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return bookingService.dealershipOptions(q, page, size);
    }

    @GetMapping("/reference-data/vehicles")
    public ApiModels.OptionPage<ApiModels.VehicleOption> vehicles(@RequestParam(defaultValue = "") String q,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return bookingService.vehicleOptions(q, page, size);
    }

    @GetMapping("/reference-data/service-types")
    public ApiModels.OptionPage<ApiModels.ServiceOption> serviceTypes(@RequestParam(defaultValue = "") String q,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return bookingService.serviceOptions(q, page, size);
    }

    @Operation(summary = "Check a requested interval for both required resources")
    @GetMapping("/availability")
    public ApiModels.AvailabilityResult availability(@RequestParam long dealershipId,
            @RequestParam long vehicleId, @RequestParam long serviceTypeId,
            @RequestParam LocalDateTime desiredStart) {
        return bookingService.availability(dealershipId, vehicleId, serviceTypeId, desiredStart);
    }

    @Operation(summary = "Book an appointment with a qualified technician and available service bay")
    @PostMapping("/appointments")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiModels.AppointmentView book(@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                                          @RequestHeader(value = "X-Manual-Booking-Delay", defaultValue = "false") boolean manualDelay,
                                          @Valid @RequestBody BookingRequests.AppointmentRequest request) {
        return bookingService.book(request, idempotencyKey, manualDelay);
    }

    @Operation(summary = "Poll the outcome of an appointment request by its idempotency key")
    @GetMapping("/appointments/idempotency-result")
    public ApiModels.AppointmentView idempotencyResult(
            @RequestHeader("Idempotency-Key") String idempotencyKey) {
        return bookingService.getIdempotentResult(idempotencyKey);
    }

    @Operation(summary = "Get a confirmed appointment")
    @GetMapping("/appointments/{id}")
    public ApiModels.AppointmentView get(@PathVariable long id) { return bookingService.getAppointment(id); }
}
