package com.keyloop.scheduler.api;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.LocalDateTime;

public final class BookingRequests {
    private BookingRequests() {}

    public record AppointmentRequest(
            @NotNull @Positive Long dealershipId,
            @NotNull @Positive Long vehicleId,
            @NotNull @Positive Long serviceTypeId,
            @NotNull LocalDateTime desiredStart) {}
}
