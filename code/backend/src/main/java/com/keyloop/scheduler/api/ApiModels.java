package com.keyloop.scheduler.api;

import com.keyloop.scheduler.domain.AppointmentStatus;
import java.time.LocalDateTime;
import java.util.List;

public final class ApiModels {
    private ApiModels() {}

    public record NamedOption(long id, String name) {}
    public record ServiceOption(long id, String name, int durationMinutes) {}
    public record VehicleOption(long id, String vin, String label) {}
    public record ReferenceData(List<NamedOption> dealerships, List<VehicleOption> vehicles, List<ServiceOption> serviceTypes) {}
    public record AvailabilityResult(boolean available, LocalDateTime startsAt, LocalDateTime endsAt,
                                     String message) {}
    public record AppointmentView(long id, long dealershipId, String dealershipName,
                                  long vehicleId, String vehicleLabel, long serviceTypeId,
                                  String serviceName, long technicianId, String technicianName,
                                  long serviceBayId, String serviceBayName,
                                  LocalDateTime startsAt, LocalDateTime endsAt, AppointmentStatus status) {}
    public record ApiError(String message) {}
}
