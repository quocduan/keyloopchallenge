package com.keyloop.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.keyloop.scheduler.api.BookingRequests;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class BackendApiIntegrationTest {
    private static final long DEALER_ID = 1L;
    private static final long VEHICLE_ID = 1L;
    private static final long SERVICE_ID = 1L;
    private static final LocalDateTime START = LocalDateTime.of(2035, 4, 15, 10, 0);

    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private com.keyloop.scheduler.service.BookingService bookingService;

    @BeforeEach
    void seedDatabase() {
        jdbc.update("DELETE FROM appointment");
        jdbc.update("DELETE FROM technician");
        jdbc.update("DELETE FROM service_bay");
        jdbc.update("DELETE FROM service_type");
        jdbc.update("DELETE FROM vehicle");
        jdbc.update("DELETE FROM dealership");

        jdbc.update("INSERT INTO dealership(id,name) VALUES (1,'Alpha Motors'),(2,'Beta Motors')");
        jdbc.update("INSERT INTO vehicle(id,vin,make,model,model_year) VALUES " +
                "(1,'VIN00000000000001','Honda','Accord',2023)," +
                "(2,'VIN00000000000002','Acura','TLX',2024)");
        jdbc.update("INSERT INTO service_type(id,name,duration_minutes) VALUES " +
                "(1,'Routine Service',60),(2,'Quick Check',30)");
        jdbc.update("INSERT INTO technician(id,dealership_id,name,service_type_id) VALUES " +
                "(1,1,'Alex Morgan',1),(2,1,'Jamie Lee',1),(3,1,'Taylor Kim',2),(4,2,'Sam Rivera',1)");
        jdbc.update("INSERT INTO service_bay(id,dealership_id,name) VALUES " +
                "(1,1,'Bay 1'),(2,1,'Bay 2'),(3,2,'Bay 1')");
    }

    @Test
    void referenceDataReturnsSortedOptionsAndVehicleLabels() throws Exception {
        mvc.perform(get("/api/reference-data"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dealerships[0].name").value("Alpha Motors"))
                .andExpect(jsonPath("$.dealerships[1].name").value("Beta Motors"))
                .andExpect(jsonPath("$.vehicles[0].label").value("2024 Acura TLX (VIN00000000000002)"))
                .andExpect(jsonPath("$.vehicles[0].id").value(2))
                .andExpect(jsonPath("$.vehicles[0].vin").value("VIN00000000000002"))
                .andExpect(jsonPath("$.vehicles[1].label").value("2023 Honda Accord (VIN00000000000001)"))
                .andExpect(jsonPath("$.serviceTypes[0].name").value("Quick Check"))
                .andExpect(jsonPath("$.serviceTypes[0].durationMinutes").value(30));
    }

    @Test
    void availabilityReturnsTrueAndCorrectEndTimeForFreeResources() throws Exception {
        mvc.perform(availabilityRequest(START))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(true))
                .andExpect(jsonPath("$.startsAt").value(wireTimestamp(START)))
                .andExpect(jsonPath("$.endsAt").value(wireTimestamp(START.plusMinutes(60))));
    }

    @Test
    void availabilityReturnsFalseWhenThereAreNoQualifiedTechnicians() throws Exception {
        jdbc.update("DELETE FROM technician WHERE dealership_id=? AND service_type_id=?", DEALER_ID, SERVICE_ID);
        assertAvailability(false);
    }

    @Test
    void availabilityReturnsFalseWhenThereAreNoBays() throws Exception {
        jdbc.update("DELETE FROM service_bay WHERE dealership_id=?", DEALER_ID);
        assertAvailability(false);
    }

    @Test
    void availabilityUsesAnotherTechnicianWhenFirstIsBusy() throws Exception {
        insertAppointment(2L, 1, 2, START, START.plusMinutes(60), "CONFIRMED");
        assertAvailability(true);
    }

    @Test
    void availabilityUsesAnotherBayWhenFirstIsBusy() throws Exception {
        insertAppointment(2L, 2, 1, START, START.plusMinutes(60), "CONFIRMED");
        assertAvailability(true);
    }

    @Test
    void availabilityReturnsFalseWhenAllTechniciansAreBusy() throws Exception {
        insertAppointment(2L, 1, 1, START, START.plusMinutes(60), "CONFIRMED");
        insertAppointment(2L, 2, 2, START, START.plusMinutes(60), "CONFIRMED");
        assertAvailability(false);
    }

    @Test
    void availabilityReturnsFalseWhenAllBaysAreBusy() throws Exception {
        insertAppointment(2L, 1, 1, START, START.plusMinutes(60), "CONFIRMED");
        insertAppointment(2L, 2, 2, START, START.plusMinutes(60), "CONFIRMED");
        assertAvailability(false);
    }

    @Test
    void availabilityRejectsVehicleOverlapEvenWhenOtherResourcesAreFree() throws Exception {
        insertAppointment(1, 1, START, START.plusMinutes(60), "CONFIRMED");

        mvc.perform(availabilityRequest(START))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(false))
                .andExpect(jsonPath("$.message").value(
                        "Vehicle already has a confirmed appointment during the requested time."));
    }

    @Test
    void availabilityTreatsAdjacentAppointmentsAsNonOverlapping() throws Exception {
        insertAppointment(2L, 1, 1, START.minusHours(1), START, "CONFIRMED");
        assertAvailability(true);

        seedDatabase();
        insertAppointment(2L, 1, 1, START.plusMinutes(60), START.plusMinutes(120), "CONFIRMED");
        assertAvailability(true);
    }

    @ParameterizedTest
    @MethodSource("overlappingIntervals")
    void availabilityRejectsPartialEnclosingAndEnclosedOverlaps(LocalDateTime existingStart,
                                                                 LocalDateTime existingEnd) throws Exception {
        jdbc.update("DELETE FROM technician WHERE id=2");
        jdbc.update("DELETE FROM service_bay WHERE id=2");
        insertAppointment(2L, 1, 1, existingStart, existingEnd, "CONFIRMED");
        assertAvailability(false);
    }

    static List<Object[]> overlappingIntervals() {
        return List.of(
                new Object[]{START.minusMinutes(30), START.plusMinutes(30)},
                new Object[]{START.plusMinutes(30), START.plusMinutes(90)},
                new Object[]{START.minusHours(1), START.plusHours(2)},
                new Object[]{START.plusMinutes(15), START.plusMinutes(45)});
    }

    @Test
    void cancelledAppointmentDoesNotBlockAvailability() throws Exception {
        insertAppointment(1, 1, START, START.plusMinutes(60), "CANCELLED");
        assertAvailability(true);
    }

    @Test
    void unknownInputsAndPastStartMapToExpectedAvailabilityErrors() throws Exception {
        mvc.perform(availabilityRequest(999, VEHICLE_ID, SERVICE_ID, START))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Dealership not found."));
        mvc.perform(availabilityRequest(DEALER_ID, 999, SERVICE_ID, START))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Vehicle not found."));
        mvc.perform(availabilityRequest(DEALER_ID, VEHICLE_ID, 999, START))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Service type not found."));
        mvc.perform(availabilityRequest(LocalDateTime.now().minusDays(1)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Appointment start must be in the future."));
    }

    @Test
    void startAtRequestTimeIsRejectedAsPastByTheServiceClock() throws Exception {
        LocalDateTime justBeforeValidation = LocalDateTime.now().minusNanos(1);
        mvc.perform(availabilityRequest(justBeforeValidation))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Appointment start must be in the future."));
    }

    @Test
    void bookingCreatesAppointmentWithQualifiedTechnicianBayAndCorrectEnd() throws Exception {
        String response = mvc.perform(bookingRequest(DEALER_ID, VEHICLE_ID, SERVICE_ID, START))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.dealershipName").value("Alpha Motors"))
                .andExpect(jsonPath("$.vehicleLabel").value("2023 Honda Accord (VIN00000000000001)"))
                .andExpect(jsonPath("$.serviceName").value("Routine Service"))
                .andExpect(jsonPath("$.startsAt").value(wireTimestamp(START)))
                .andExpect(jsonPath("$.endsAt").value(wireTimestamp(START.plusMinutes(60))))
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andReturn().getResponse().getContentAsString();

        long technicianId = jsonLong(response, "technicianId");
        long bayId = jsonLong(response, "serviceBayId");
        assertThat(jdbc.queryForObject("SELECT service_type_id FROM technician WHERE id=?", Integer.class,
                technicianId)).isEqualTo((int) SERVICE_ID);
        assertThat(jdbc.queryForObject("SELECT dealership_id FROM service_bay WHERE id=?", Integer.class,
                bayId)).isEqualTo((int) DEALER_ID);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM appointment", Integer.class)).isEqualTo(1);
    }

    @Test
    void bookingWithoutAFreeResourcePairReturnsConflictAndDoesNotInsert() throws Exception {
        jdbc.update("DELETE FROM technician WHERE id=2");
        jdbc.update("DELETE FROM service_bay WHERE id=2");
        insertAppointment(2L, 1, 1, START, START.plusMinutes(60), "CONFIRMED");

        mvc.perform(bookingRequest(DEALER_ID, VEHICLE_ID, SERVICE_ID, START))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(
                        "No qualified technician and service bay are both available for the full service duration."));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM appointment", Integer.class)).isEqualTo(1);
    }

    @Test
    void bookingSameVehicleForAnOverlappingServiceReturnsConflictEvenWhenResourcesAreFree() throws Exception {
        insertAppointment(1, 1, START, START.plusMinutes(60), "CONFIRMED");

        mvc.perform(bookingRequest(DEALER_ID, VEHICLE_ID, 2L, START))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(
                        "Vehicle already has a confirmed appointment during the requested time."));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM appointment", Integer.class)).isEqualTo(1);
    }

    @Test
    void bookingSameVehicleForNonOverlappingTimeRemainsAllowed() throws Exception {
        insertAppointment(1, 1, START.minusHours(1), START, "CONFIRMED");

        mvc.perform(bookingRequest(DEALER_ID, VEHICLE_ID, SERVICE_ID, START))
                .andExpect(status().isCreated());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM appointment", Integer.class)).isEqualTo(2);
    }

    @Test
    void failedAppointmentInsertLeavesNoPartialBooking() {
        jdbc.execute("CREATE TRIGGER fail_appointment_insert BEFORE INSERT ON appointment " +
                "FOR EACH ROW CALL \"com.keyloop.scheduler.BackendApiIntegrationTest$FailAppointmentInsert\"");
        try {
            assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> bookingService.book(
                    new BookingRequests.AppointmentRequest(DEALER_ID, VEHICLE_ID, SERVICE_ID, START))))
                    .isNotNull();
        } finally {
            jdbc.execute("DROP TRIGGER fail_appointment_insert");
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM appointment", Integer.class)).isZero();
    }

    @Test
    void bookingMapsMissingDealerVehicleServiceAndPastTimeErrors() throws Exception {
        mvc.perform(bookingRequest(999, VEHICLE_ID, SERVICE_ID, START))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.message").value("Dealership not found."));
        mvc.perform(bookingRequest(DEALER_ID, 999, SERVICE_ID, START))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.message").value("Vehicle not found."));
        mvc.perform(bookingRequest(DEALER_ID, VEHICLE_ID, 999, START))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.message").value("Service type not found."));
        mvc.perform(bookingRequest(DEALER_ID, VEHICLE_ID, SERVICE_ID, LocalDateTime.now().minusDays(1)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Appointment start must be in the future."));
    }

    @Test
    void appointmentCanBeRetrievedWithMappedFieldsAndUnknownIdIsNotFound() throws Exception {
        String response = mvc.perform(bookingRequest(DEALER_ID, VEHICLE_ID, SERVICE_ID, START))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long id = jsonLong(response, "id");

        mvc.perform(get("/api/appointments/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.dealershipId").value(DEALER_ID))
                .andExpect(jsonPath("$.vehicleLabel").value("2023 Honda Accord (VIN00000000000001)"))
                .andExpect(jsonPath("$.technicianName").isNotEmpty())
                .andExpect(jsonPath("$.serviceBayName").isNotEmpty())
                .andExpect(jsonPath("$.startsAt").value(wireTimestamp(START)))
                .andExpect(jsonPath("$.endsAt").value(wireTimestamp(START.plusMinutes(60))));

        mvc.perform(get("/api/appointments/99999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Appointment not found."));
    }

    @ParameterizedTest
    @MethodSource("invalidBookingBodies")
    void bookingRejectsMissingNullZeroOrNegativeIds(String body) throws Exception {
        mvc.perform(post("/api/appointments").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
    }

    static List<String> invalidBookingBodies() {
        String date = "\"desiredStart\":\"2035-04-15T10:00:00\"";
        return List.of(
                "{\"vehicleId\":1,\"serviceTypeId\":1," + date + "}",
                "{\"dealershipId\":null,\"vehicleId\":1,\"serviceTypeId\":1," + date + "}",
                "{\"dealershipId\":0,\"vehicleId\":1,\"serviceTypeId\":1," + date + "}",
                "{\"dealershipId\":-1,\"vehicleId\":1,\"serviceTypeId\":1," + date + "}",
                "{\"dealershipId\":1,\"serviceTypeId\":1," + date + "}",
                "{\"dealershipId\":1,\"vehicleId\":null,\"serviceTypeId\":1," + date + "}",
                "{\"dealershipId\":1,\"vehicleId\":0,\"serviceTypeId\":1," + date + "}",
                "{\"dealershipId\":1,\"vehicleId\":-1,\"serviceTypeId\":1," + date + "}",
                "{\"dealershipId\":1,\"vehicleId\":1," + date + "}",
                "{\"dealershipId\":1,\"vehicleId\":1,\"serviceTypeId\":null," + date + "}",
                "{\"dealershipId\":1,\"vehicleId\":1,\"serviceTypeId\":0," + date + "}",
                "{\"dealershipId\":1,\"vehicleId\":1,\"serviceTypeId\":-1," + date + "}");
    }

    @Test
    void bookingRejectsMissingOrMalformedDesiredStart() throws Exception {
        mvc.perform(post("/api/appointments").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dealershipId\":1,\"vehicleId\":1,\"serviceTypeId\":1}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/appointments").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dealershipId\":1,\"vehicleId\":1,\"serviceTypeId\":1," +
                                "\"desiredStart\":\"not-a-date\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void availabilityRejectsMissingAndMalformedQueryParameters() throws Exception {
        mvc.perform(get("/api/availability").queryParam("dealershipId", "1"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/availability").queryParam("dealershipId", "1")
                        .queryParam("vehicleId", "1").queryParam("serviceTypeId", "1")
                        .queryParam("desiredStart", "not-a-date"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void simultaneousBookingsForOneResourceProduceOneBookingAndOneConflict() throws Exception {
        jdbc.update("DELETE FROM technician WHERE id=2");
        jdbc.update("DELETE FROM service_bay WHERE id=2");
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<Integer> first = executor.submit(() -> bookConcurrently(ready, start));
            Future<Integer> second = executor.submit(() -> bookConcurrently(ready, start));
            if (!ready.await(5, TimeUnit.SECONDS)) throw new AssertionError("Booking workers did not become ready");
            start.countDown();
            List<Integer> outcomes = List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS));
            assertThat(outcomes.stream().filter(status -> status == 201).count()).isEqualTo(1);
            assertThat(outcomes.stream().filter(status -> status == 409).count()).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM appointment", Integer.class)).isEqualTo(1);
        }
    }

    @Test
    void simultaneousBookingsForSameVehicleProduceOneBookingAndOneConflict() throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<Integer> first = executor.submit(() -> bookConcurrently(ready, start));
            Future<Integer> second = executor.submit(() -> bookConcurrently(ready, start));
            if (!ready.await(5, TimeUnit.SECONDS)) throw new AssertionError("Booking workers did not become ready");
            start.countDown();
            List<Integer> outcomes = List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS));
            assertThat(outcomes.stream().filter(status -> status == 201).count()).isEqualTo(1);
            assertThat(outcomes.stream().filter(status -> status == 409).count()).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM appointment", Integer.class)).isEqualTo(1);
        }
    }

    @Test
    void simultaneousBookingsForSameVehicleAcrossDealershipsProduceOneBookingAndOneConflict() throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<Integer> first = executor.submit(() -> bookConcurrently(DEALER_ID, ready, start));
            Future<Integer> second = executor.submit(() -> bookConcurrently(2L, ready, start));
            if (!ready.await(5, TimeUnit.SECONDS)) throw new AssertionError("Booking workers did not become ready");
            start.countDown();
            List<Integer> outcomes = List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS));
            assertThat(outcomes.stream().filter(status -> status == 201).count()).isEqualTo(1);
            assertThat(outcomes.stream().filter(status -> status == 409).count()).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM appointment", Integer.class)).isEqualTo(1);
        }
    }

    private int bookConcurrently(CountDownLatch ready, CountDownLatch start) throws Exception {
        return bookConcurrently(DEALER_ID, ready, start);
    }

    private int bookConcurrently(long dealershipId, CountDownLatch ready, CountDownLatch start) throws Exception {
        ready.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) throw new AssertionError("Booking start was not released");
        return mvc.perform(bookingRequest(dealershipId, VEHICLE_ID, SERVICE_ID, START))
                .andReturn().getResponse().getStatus();
    }

    private void assertAvailability(boolean expected) throws Exception {
        mvc.perform(availabilityRequest(START))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(expected));
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder availabilityRequest(
            LocalDateTime startsAt) {
        return availabilityRequest(DEALER_ID, VEHICLE_ID, SERVICE_ID, startsAt);
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder availabilityRequest(
            long dealershipId, long vehicleId, long serviceTypeId, LocalDateTime startsAt) {
        return get("/api/availability").queryParam("dealershipId", Long.toString(dealershipId))
                .queryParam("vehicleId", Long.toString(vehicleId))
                .queryParam("serviceTypeId", Long.toString(serviceTypeId))
                .queryParam("desiredStart", startsAt.toString());
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder bookingRequest(
            long dealershipId, long vehicleId, long serviceTypeId, LocalDateTime startsAt) {
        String body = "{\"dealershipId\":" + dealershipId + ",\"vehicleId\":" + vehicleId +
                ",\"serviceTypeId\":" + serviceTypeId + ",\"desiredStart\":\"" + startsAt + "\"}";
        return post("/api/appointments").contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private void insertAppointment(long technicianId, long bayId, LocalDateTime start,
                                   LocalDateTime end, String status) {
        insertAppointment(VEHICLE_ID, technicianId, bayId, start, end, status);
    }

    private void insertAppointment(long vehicleId, long technicianId, long bayId, LocalDateTime start,
                                   LocalDateTime end, String status) {
        jdbc.update("INSERT INTO appointment(dealership_id,vehicle_id,service_type_id,technician_id," +
                        "service_bay_id,starts_at,ends_at,status) VALUES (?,?,?,?,?,?,?,?)",
                DEALER_ID, vehicleId, SERVICE_ID, technicianId, bayId, start, end, status);
    }

    private long jsonLong(String json, String field) throws Exception {
        return new com.fasterxml.jackson.databind.ObjectMapper().readTree(json).get(field).asLong();
    }

    private String wireTimestamp(LocalDateTime value) {
        return value.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"));
    }

    public static class FailAppointmentInsert implements org.h2.api.Trigger {
        @Override
        public void init(Connection connection, String schemaName, String triggerName, String tableName,
                         boolean before, int type) {}

        @Override
        public void fire(Connection connection, Object[] oldRow, Object[] newRow) throws SQLException {
            throw new SQLException("forced appointment insert failure");
        }

        @Override public void close() {}
        @Override public void remove() {}
    }
}
