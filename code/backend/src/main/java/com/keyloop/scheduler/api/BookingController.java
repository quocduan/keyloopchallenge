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

    @Operation(summary = "Get booking form reference data")
    @GetMapping("/reference-data")
    public ApiModels.ReferenceData referenceData() { return bookingService.referenceData(); }

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
    public ApiModels.AppointmentView book(@Valid @RequestBody BookingRequests.AppointmentRequest request) {
        return bookingService.book(request);
    }

    @Operation(summary = "Get a confirmed appointment")
    @GetMapping("/appointments/{id}")
    public ApiModels.AppointmentView get(@PathVariable long id) { return bookingService.getAppointment(id); }
}
