package com.keyloop.scheduler.service;

import com.keyloop.scheduler.api.ApiModels;
import com.keyloop.scheduler.api.BookingRequests;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BookingService {
    private final JdbcTemplate jdbc;

    public BookingService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public ApiModels.ReferenceData referenceData() {
        var dealers = jdbc.query("SELECT id,name FROM dealership ORDER BY name",
                (rs, n) -> new ApiModels.NamedOption(rs.getLong("id"), rs.getString("name")));
        var vehicles = jdbc.query("SELECT id,vin,make,model,model_year FROM vehicle ORDER BY make,model",
                (rs, n) -> new ApiModels.VehicleOption(rs.getLong("id"), rs.getString("vin"),
                        rs.getInt("model_year") + " " + rs.getString("make") + " " + rs.getString("model") + " (" + rs.getString("vin") + ")"));
        var services = jdbc.query("SELECT id,name,duration_minutes FROM service_type ORDER BY name",
                (rs, n) -> new ApiModels.ServiceOption(rs.getLong("id"), rs.getString("name"), rs.getInt("duration_minutes")));
        return new ApiModels.ReferenceData(dealers, vehicles, services);
    }

    public ApiModels.AvailabilityResult availability(long dealershipId, long vehicleId, long serviceTypeId,
                                                       LocalDateTime startsAt) {
        return evaluate(dealershipId, vehicleId, serviceTypeId, startsAt);
    }

    @Transactional
    public ApiModels.AppointmentView book(BookingRequests.AppointmentRequest request) {
        // Serializes booking attempts at this dealership; the lock is held through reservation and insert.
        Integer dealerExists = jdbc.query("SELECT id FROM dealership WHERE id=? FOR UPDATE",
                rs -> rs.next() ? rs.getInt(1) : null, request.dealershipId());
        if (dealerExists == null) throw new BookingException(HttpStatus.NOT_FOUND, "Dealership not found.");

        Slot slot = findSlot(request.dealershipId(), request.vehicleId(), request.serviceTypeId(), request.desiredStart());
        if (slot == null) throw new BookingException(HttpStatus.CONFLICT,
                "No qualified technician and service bay are both available for the full service duration.");

        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement("""
                    INSERT INTO appointment (dealership_id,vehicle_id,service_type_id,technician_id,service_bay_id,starts_at,ends_at,status)
                    VALUES (?,?,?,?,?,?,?,'CONFIRMED')
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, request.dealershipId());
            statement.setLong(2, request.vehicleId());
            statement.setLong(3, request.serviceTypeId());
            statement.setLong(4, slot.technicianId());
            statement.setLong(5, slot.bayId());
            statement.setObject(6, request.desiredStart());
            statement.setObject(7, slot.endsAt());
            return statement;
        }, keyHolder);
        Long appointmentId = keyHolder.getKey().longValue();
        return getAppointment(appointmentId);
    }

    public ApiModels.AppointmentView getAppointment(long id) {
        var rows = jdbc.query("""
                SELECT a.id,a.dealership_id,d.name dealership_name,a.vehicle_id,
                       CONCAT(v.model_year,' ',v.make,' ',v.model,' (',v.vin,')') vehicle_label,
                       a.service_type_id,s.name service_name,a.technician_id,t.name technician_name,
                       a.service_bay_id,b.name service_bay_name,a.starts_at,a.ends_at,a.status
                FROM appointment a JOIN dealership d ON d.id=a.dealership_id
                JOIN vehicle v ON v.id=a.vehicle_id JOIN service_type s ON s.id=a.service_type_id
                JOIN technician t ON t.id=a.technician_id JOIN service_bay b ON b.id=a.service_bay_id
                WHERE a.id=?
                """, (rs, n) -> mapAppointment(rs), id);
        if (rows.isEmpty()) throw new BookingException(HttpStatus.NOT_FOUND, "Appointment not found.");
        return rows.getFirst();
    }

    private ApiModels.AvailabilityResult evaluate(long dealershipId, long vehicleId, long serviceTypeId, LocalDateTime startsAt) {
        validateInputs(dealershipId, vehicleId, serviceTypeId, startsAt);
        int duration = jdbc.query("SELECT duration_minutes FROM service_type WHERE id=?",
                rs -> rs.next() ? rs.getInt(1) : 0, serviceTypeId);
        if (duration == 0) throw new BookingException(HttpStatus.NOT_FOUND, "Service type not found.");
        LocalDateTime endsAt = startsAt.plusMinutes(duration);
        List<Long> techs = jdbc.query("SELECT id FROM technician WHERE dealership_id=? AND service_type_id=? ORDER BY id",
                (rs, n) -> rs.getLong(1), dealershipId, serviceTypeId);
        List<Long> bays = jdbc.query("SELECT id FROM service_bay WHERE dealership_id=? ORDER BY id",
                (rs, n) -> rs.getLong(1), dealershipId);
        boolean available = hasFreePair(dealershipId, techs, bays, startsAt, endsAt);
        return new ApiModels.AvailabilityResult(available, startsAt, endsAt,
                available ? "A qualified technician and service bay are available." : "No resource pair is available for the full service duration.");
    }

    private Slot findSlot(long dealershipId, long vehicleId, long serviceTypeId, LocalDateTime startsAt) {
        validateInputs(dealershipId, vehicleId, serviceTypeId, startsAt);
        int duration = jdbc.query("SELECT duration_minutes FROM service_type WHERE id=?",
                rs -> rs.next() ? rs.getInt(1) : 0, serviceTypeId);
        if (duration == 0) throw new BookingException(HttpStatus.NOT_FOUND, "Service type not found.");
        LocalDateTime endsAt = startsAt.plusMinutes(duration);
        List<Long> techs = jdbc.query("SELECT id FROM technician WHERE dealership_id=? AND service_type_id=? ORDER BY id",
                (rs, n) -> rs.getLong(1), dealershipId, serviceTypeId);
        List<Long> bays = jdbc.query("SELECT id FROM service_bay WHERE dealership_id=? ORDER BY id",
                (rs, n) -> rs.getLong(1), dealershipId);
        for (Long tech : techs) {
            if (overlaps("technician_id", tech, startsAt, endsAt)) continue;
            for (Long bay : bays) {
                if (!overlaps("service_bay_id", bay, startsAt, endsAt)) return new Slot(tech, bay, endsAt);
            }
        }
        return null;
    }

    private boolean hasFreePair(long dealershipId, List<Long> techs, List<Long> bays,
                                LocalDateTime start, LocalDateTime end) {
        for (Long tech : techs) {
            if (overlaps("technician_id", tech, start, end)) continue;
            for (Long bay : bays) if (!overlaps("service_bay_id", bay, start, end)) return true;
        }
        return false;
    }

    private boolean overlaps(String column, long resourceId, LocalDateTime start, LocalDateTime end) {
        // column is selected only from the two fixed internal names above, never from request data.
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM appointment WHERE " + column +
                "=? AND status='CONFIRMED' AND starts_at < ? AND ends_at > ?", Integer.class, resourceId, end, start);
        return count != null && count > 0;
    }

    private void validateInputs(long dealershipId, long vehicleId, long serviceTypeId, LocalDateTime startsAt) {
        Integer dealershipCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM dealership WHERE id=?", Integer.class, dealershipId);
        if (dealershipCount == null || dealershipCount == 0)
            throw new BookingException(HttpStatus.NOT_FOUND, "Dealership not found.");
        Integer vehicleCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM vehicle WHERE id=?", Integer.class, vehicleId);
        if (vehicleCount == null || vehicleCount == 0)
            throw new BookingException(HttpStatus.NOT_FOUND, "Vehicle not found.");
        if (startsAt.isBefore(LocalDateTime.now())) throw new BookingException(HttpStatus.BAD_REQUEST, "Appointment start must be in the future.");
    }

    private ApiModels.AppointmentView mapAppointment(ResultSet rs) throws SQLException {
        return new ApiModels.AppointmentView(rs.getLong("id"), rs.getLong("dealership_id"), rs.getString("dealership_name"),
                rs.getLong("vehicle_id"), rs.getString("vehicle_label"), rs.getLong("service_type_id"), rs.getString("service_name"),
                rs.getLong("technician_id"), rs.getString("technician_name"), rs.getLong("service_bay_id"), rs.getString("service_bay_name"),
                rs.getTimestamp("starts_at").toLocalDateTime(), rs.getTimestamp("ends_at").toLocalDateTime(), rs.getString("status"));
    }

    private record Slot(long technicianId, long bayId, LocalDateTime endsAt) {}
}
