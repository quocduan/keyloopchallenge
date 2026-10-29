package com.keyloop.scheduler;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.IntPredicate;
import java.util.function.Supplier;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import com.zaxxer.hikari.HikariDataSource;

/**
 * Opt-in HTTP load tests. Run with -DrunPerformanceTests=true; configure
 * -Dperformance.requests=N and -Dperformance.sustainedSeconds=N as needed.
 * Latency and throughput are reported, not compared with invented SLA values.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:h2:mem:scheduler-performance;MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;LOCK_TIMEOUT=10000")
@ActiveProfiles("test")
@EnabledIfSystemProperty(named = "runPerformanceTests", matches = "true")
class BackendPerformanceTest {
    private static final LocalDateTime START = LocalDateTime.of(2035, 4, 15, 10, 0);

    @LocalServerPort private int port;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSource dataSource;

    private HttpClient client;

    @BeforeEach
    void resetDatabase() {
        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        jdbc.update("DELETE FROM appointment");
        jdbc.update("DELETE FROM technician");
        jdbc.update("DELETE FROM service_bay");
        jdbc.update("DELETE FROM service_type");
        jdbc.update("DELETE FROM vehicle");
        jdbc.update("DELETE FROM dealership");
        jdbc.update("INSERT INTO dealership(id,name) VALUES (1,'Performance Motors')");
        jdbc.update("INSERT INTO vehicle(id,vin,make,model,model_year) VALUES " +
                "(1,'VIN00000000000001','Honda','Accord',2023)");
        jdbc.update("INSERT INTO service_type(id,name,duration_minutes) VALUES (1,'Routine Service',60)");
        jdbc.update("INSERT INTO technician(id,dealership_id,name,service_type_id) " +
                "VALUES (1,1,'Performance Technician',1)");
        jdbc.update("INSERT INTO service_bay(id,dealership_id,name) VALUES (1,1,'Bay 1')");
        for (int id = 2; id <= 10; id++) {
            jdbc.update("INSERT INTO technician(id,dealership_id,name,service_type_id) VALUES (?,?,?,1)",
                    id, 1, "Technician " + id);
            jdbc.update("INSERT INTO service_bay(id,dealership_id,name) VALUES (?,1,?)", id, "Bay " + id);
        }
    }

    @Test
    void referenceDataLoad() throws Exception {
        seedReferenceDataScale();
        Measurement measurement = run("reference-data", repeat(request("GET", "/api/reference-data", null),
                requestCount()), 8, status -> status == 200);
        assertThat(measurement.unexpectedStatuses()).isZero();
    }

    @Test
    void availabilityBaselineAndGrowth() throws Exception {
        Measurement baseline = run("availability-baseline", repeat(availability(START), requestCount()),
                8, status -> status == 200);
        assertThat(baseline.unexpectedStatuses()).isZero();

        int historySize = Integer.getInteger("performance.historySize", 1000);
        for (int i = 1; i <= historySize; i++) {
            LocalDateTime priorStart = START.minusDays(i);
            jdbc.update("INSERT INTO appointment(dealership_id,vehicle_id,service_type_id,technician_id," +
                            "service_bay_id,starts_at,ends_at,status) VALUES (1,1,1,1,1,?,?, 'CONFIRMED')",
                    priorStart, priorStart.plusMinutes(60));
        }
        int resourceCount = Integer.getInteger("performance.resources", 50);
        for (int id = 11; id <= resourceCount; id++) {
            jdbc.update("INSERT INTO technician(id,dealership_id,name,service_type_id) VALUES (?,?,?,1)",
                    id, 1, "Technician " + id);
            jdbc.update("INSERT INTO service_bay(id,dealership_id,name) VALUES (?,1,?)", id, "Bay " + id);
        }
        Measurement growth = run("availability-with-" + historySize + "-historical-appointments",
                repeat(availability(START), requestCount()), 8, status -> status == 200);
        assertThat(growth.unexpectedStatuses()).isZero();
    }

    @Test
    void bookingThroughputAcrossIndependentDealerships() throws Exception {
        int requests = requestCount();
        for (int id = 2; id <= requests; id++) {
            int resourceId = id + 100;
            jdbc.update("INSERT INTO dealership(id,name) VALUES (?,?)", id, "Dealer " + id);
            jdbc.update("INSERT INTO technician(id,dealership_id,name,service_type_id) VALUES (?,?,?,1)",
                    resourceId, id, "Technician " + id);
            jdbc.update("INSERT INTO service_bay(id,dealership_id,name) VALUES (?,?,?)",
                    resourceId, id, "Bay " + id);
        }
        List<Supplier<Integer>> tasks = IntStream.rangeClosed(1, requests)
                .mapToObj(id -> request("POST", "/api/appointments", bookingBody(id, START.plusDays(id))))
                .toList();
        Measurement measurement = run("booking-throughput-independent-dealerships", tasks,
                Math.min(16, requests), status -> status == 201);
        assertThat(measurement.unexpectedStatuses()).isZero();
        assertThat(measurement.statusCounts().getOrDefault(201, 0)).isEqualTo(requests);
    }

    @Test
    void bookingContentionMeasuresConflictRateAndPreventsDoubleBooking() throws Exception {
        jdbc.update("DELETE FROM technician WHERE id>1");
        jdbc.update("DELETE FROM service_bay WHERE id>1");
        int requests = requestCount();
        Measurement measurement = run("booking-contention-one-resource", repeat(
                        request("POST", "/api/appointments", bookingBody(1, START)), requests),
                Math.min(16, requests), status -> status == 201 || status == 409);
        assertThat(measurement.unexpectedStatuses()).isZero();
        assertThat(measurement.statusCounts().getOrDefault(201, 0)).isEqualTo(1);
        assertThat(measurement.statusCounts().getOrDefault(409, 0)).isEqualTo(requests - 1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM appointment", Integer.class)).isEqualTo(1);
    }

    @Test
    void mixedEndpointLoad() throws Exception {
        int initialStatus = request("POST", "/api/appointments", bookingBody(1, START)).get();
        assertThat(initialStatus).isEqualTo(201);
        long appointmentId = jdbc.queryForObject("SELECT MAX(id) FROM appointment", Long.class);
        int requests = requestCount();
        List<Supplier<Integer>> tasks = new ArrayList<>(requests);
        for (int i = 0; i < requests; i++) {
            tasks.add(switch (i % 4) {
                case 0 -> request("GET", "/api/reference-data", null);
                case 1 -> availability(START.plusDays(i + 1L));
                case 2 -> request("GET", "/api/appointments/" + appointmentId, null);
                default -> request("POST", "/api/appointments", bookingBody(1, START.plusDays(i + 1L)));
            });
        }
        Measurement measurement = run("mixed-reference-availability-booking-retrieval", tasks,
                Math.min(12, requests), status -> status == 200 || status == 201);
        assertThat(measurement.unexpectedStatuses()).isZero();
    }

    @Test
    void sustainedLoadRecordsLatencyAndErrorRate() throws Exception {
        int seconds = Integer.getInteger("performance.sustainedSeconds", 5);
        long deadline = System.nanoTime() + Duration.ofSeconds(seconds).toNanos();
        int concurrency = Math.max(1, Integer.getInteger("performance.sustainedConcurrency", 8));
        long totalStart = System.nanoTime();
        List<Long> latencyNanos = new ArrayList<>();
        List<Integer> statuses = new ArrayList<>();
        try (ExecutorService executor = Executors.newFixedThreadPool(concurrency)) {
            List<Future<List<Sample>>> workers = new ArrayList<>();
            for (int i = 0; i < concurrency; i++) {
                workers.add(executor.submit(() -> {
                    List<Sample> samples = new ArrayList<>();
                    while (System.nanoTime() < deadline) {
                        long requestStart = System.nanoTime();
                        int status = request("GET", "/api/reference-data", null).get();
                        samples.add(new Sample(status, System.nanoTime() - requestStart));
                    }
                    return samples;
                }));
            }
            for (Future<List<Sample>> worker : workers) {
                for (Sample sample : worker.get()) {
                    statuses.add(sample.status());
                    latencyNanos.add(sample.latencyNanos());
                }
            }
        }
        double elapsedSeconds = (System.nanoTime() - totalStart) / 1_000_000_000.0;
        Measurement measurement = Measurement.from(statuses, latencyNanos, elapsedSeconds,
                status -> status == 200);
        System.out.println("sustained-reference-data-" + seconds + "s: " + measurement +
                ", resources=" + resourceSnapshot());
        assertThat(measurement.unexpectedStatuses()).isZero();
    }

    @Test
    void capacityStepTestRecordsThroughputAndLatencyByConcurrency() throws Exception {
        int requestsPerStep = Math.max(20, requestCount() / 2);
        for (int concurrency : List.of(1, 2, 4, 8, 16)) {
            Measurement measurement = run("capacity-step-concurrency-" + concurrency,
                    repeat(request("GET", "/api/reference-data", null), requestsPerStep),
                    concurrency, status -> status == 200);
            assertThat(measurement.unexpectedStatuses()).isZero();
        }
        System.out.println("Compare measured throughput and p95/p99 latency to the deployment SLA; " +
                "no SLA is defined in this repository.");
    }

    private Measurement run(String name, List<Supplier<Integer>> actions, int concurrency,
                            IntPredicate acceptedStatus) throws Exception {
        List<Long> latencyNanos = new ArrayList<>(actions.size());
        List<Integer> statuses = new ArrayList<>(actions.size());
        long totalStart = System.nanoTime();
        try (ExecutorService executor = Executors.newFixedThreadPool(Math.max(1, concurrency))) {
            List<Future<Sample>> futures = new ArrayList<>(actions.size());
            for (Supplier<Integer> action : actions) {
                futures.add(executor.submit((Callable<Sample>) () -> {
                    long started = System.nanoTime();
                    int status = action.get();
                    return new Sample(status, System.nanoTime() - started);
                }));
            }
            for (Future<Sample> future : futures) {
                Sample sample = future.get();
                statuses.add(sample.status());
                latencyNanos.add(sample.latencyNanos());
            }
        }
        double elapsedSeconds = (System.nanoTime() - totalStart) / 1_000_000_000.0;
        Measurement measurement = Measurement.from(statuses, latencyNanos, elapsedSeconds, acceptedStatus);
        System.out.println(name + ": " + measurement + ", resources=" + resourceSnapshot());
        return measurement;
    }

    private Supplier<Integer> availability(LocalDateTime startsAt) {
        String path = "/api/availability?dealershipId=1&vehicleId=1&serviceTypeId=1&desiredStart=" +
                startsAt.toString();
        return request("GET", path, null);
    }

    private Supplier<Integer> request(String method, String path, String body) {
        return () -> {
            try {
                HttpRequest.Builder builder = HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(30));
                if (body != null) builder.header("Content-Type", "application/json");
                HttpRequest httpRequest = builder.method(method,
                        body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body))
                        .build();
                return client.send(httpRequest, HttpResponse.BodyHandlers.discarding()).statusCode();
            } catch (Exception exception) {
                throw new IllegalStateException("HTTP performance request failed", exception);
            }
        };
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private String bookingBody(long dealershipId, LocalDateTime startsAt) {
        return "{\"dealershipId\":" + dealershipId + ",\"vehicleId\":1," +
                "\"serviceTypeId\":1,\"desiredStart\":\"" + startsAt + "\"}";
    }

    private void seedReferenceDataScale() {
        int dealers = Integer.getInteger("performance.dealerships", 100);
        int vehicles = Integer.getInteger("performance.vehicles", 500);
        int services = Integer.getInteger("performance.serviceTypes", 20);
        for (int id = 2; id <= dealers; id++) {
            jdbc.update("INSERT INTO dealership(id,name) VALUES (?,?)", id, "Dealer " + id);
        }
        for (int id = 2; id <= vehicles; id++) {
            jdbc.update("INSERT INTO vehicle(id,vin,make,model,model_year) VALUES (?,?,?,?,?)", id,
                    String.format("%017d", id), "Make " + (id % 10), "Model " + id, 2000 + id % 25);
        }
        for (int id = 2; id <= services; id++) {
            jdbc.update("INSERT INTO service_type(id,name,duration_minutes) VALUES (?,?,?)", id,
                    "Service " + id, 30 + (id % 4) * 15);
        }
    }

    private List<Supplier<Integer>> repeat(Supplier<Integer> action, int count) {
        return java.util.Collections.nCopies(count, action);
    }

    private int requestCount() {
        return Math.max(1, Integer.getInteger("performance.requests", 100));
    }

    private String resourceSnapshot() {
        Runtime runtime = Runtime.getRuntime();
        long heapUsedBytes = runtime.totalMemory() - runtime.freeMemory();
        if (dataSource instanceof HikariDataSource hikari) {
            var pool = hikari.getHikariPoolMXBean();
            return "heapUsedBytes=" + heapUsedBytes + ", hikariActive=" + pool.getActiveConnections() +
                    ", hikariIdle=" + pool.getIdleConnections() + ", hikariTotal=" + pool.getTotalConnections() +
                    ", hikariMax=" + hikari.getMaximumPoolSize() +
                    "; collect database CPU externally during the run";
        }
        return "heapUsedBytes=" + heapUsedBytes + "; collect database CPU/connections externally during the run";
    }

    private record Sample(int status, long latencyNanos) {}

    private record Measurement(int requests, double requestsPerSecond, double p50Millis,
                               double p95Millis, double p99Millis, int unexpectedStatuses,
                               java.util.Map<Integer, Integer> statusCounts) {
        static Measurement from(List<Integer> statuses, List<Long> latencies, double elapsedSeconds,
                               IntPredicate acceptedStatus) {
            List<Long> sorted = latencies.stream().sorted().toList();
            java.util.Map<Integer, Integer> counts = new java.util.TreeMap<>();
            statuses.forEach(status -> counts.merge(status, 1, Integer::sum));
            long unexpected = statuses.stream().filter(status -> !acceptedStatus.test(status)).count();
            return new Measurement(statuses.size(), statuses.size() / elapsedSeconds,
                    percentile(sorted, 0.50), percentile(sorted, 0.95), percentile(sorted, 0.99),
                    Math.toIntExact(unexpected), counts);
        }

        private static double percentile(List<Long> sorted, double percentile) {
            if (sorted.isEmpty()) return 0;
            int index = Math.min(sorted.size() - 1, (int) Math.ceil(percentile * sorted.size()) - 1);
            return sorted.get(index) / 1_000_000.0;
        }

        @Override
        public String toString() {
            return "requests=" + requests + ", requestsPerSecond=" + requestsPerSecond +
                    ", p50Millis=" + p50Millis + ", p95Millis=" + p95Millis +
                    ", p99Millis=" + p99Millis + ", unexpectedStatuses=" + unexpectedStatuses +
                    ", statusCounts=" + statusCounts;
        }
    }
}
