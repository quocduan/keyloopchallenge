# Unified Service Scheduler

Scenario A starter with a Spring Boot REST API, Spring Data JPA/Hibernate persistence, MySQL, OpenAPI/Swagger UI, and a Vue 3 booking page.

See the [system design](../System-Design-Document-scenario-a-c4.md), [technology choices](../scenario-a-technologies.md), and [submission overview](../README.md). Unless stated otherwise, commands below start in the `code/` directory.

## AI Collaboration Narrative

I used AI as an implementation and review partner, while retaining responsibility for requirements, architecture, and acceptance of the code. I started with the booking rules, C4 architecture, BPMN flow, and technology choices before requesting a scaffold. I then guided the AI through focused changes, using concrete acceptance criteria: a booking must reserve a qualified technician and bay for the full service duration, prevent overlapping bookings for the same vehicle, and persist the reservation atomically. Design decisions and follow-up work were recorded in the architecture documents and `todo.md` at the repository root.

I reviewed generated code against those requirements and the selected stack, challenged assumptions, and refined the implementation in small steps. For example, I directed the initial JDBC implementation to be replaced with the agreed Spring Data JPA/Hibernate approach. I also identified the missing same-vehicle overlap rule and required it to apply across services and dealerships, even when another technician and bay were available. When H2 limitations affected locking tests, I directed the tests to use MySQL rather than accepting changes to application behavior solely to satisfy the test database.

Verification combined code review, automated tests, and manual booking scenarios. The backend test cases cover validation, technician qualification, interval boundaries, persistence and rollback, concurrent resource allocation, and idempotent retries. MySQL integration tests exercise the database behavior that matters for booking correctness, including `NOWAIT`, `SKIP LOCKED`, and competing requests. Manual UI checks exercise the duplicate-request flow and polling for an in-flight booking. Failures were investigated to distinguish application defects from fixture, migration, or environment problems, then the relevant checks were repeated after corrections.

To assess final code quality, I reviewed scalability, performance, reliability, maintainability, and observability as well as functional behavior. This led to status-aware overlap indexes, finer-grained resource locking, bounded reference-data queries, explicit timeout/error handling, and booking metrics and structured logs. I checked that Flyway migrations and JPA mappings remained consistent and that the README described the implemented API behavior and operational constraints. AI suggestions were accepted only after checking their fit with the design and available evidence. Performance tests remain opt-in, coverage measurement is still a follow-up item, and the production limitations listed below remain outside this starter's scope.

## Requirements

- Docker Desktop / Docker Engine with Compose for the full container setup.
- Java 21 and Maven 3.9+ for local backend builds and tests.
- Node.js 22.18+ with npm for local frontend builds.

Docker builds supply their own Java, Maven, and Node runtimes. Check `java -version`, `mvn -version`, and `node --version` before building locally.

## Run with Docker Compose

From the repository root, first run `cd code`. Then build and start the application:

```sh
docker compose up --build
```

- Vue UI: http://localhost:5173
- Swagger UI: http://localhost:8080/swagger-ui.html
- OpenAPI JSON: http://localhost:8080/v3/api-docs
- MySQL: localhost:3306 (database `scheduler`, user `scheduler`)

Flyway creates the schema and sample dealership, vehicle, service, technician, and bay records on startup. Wait for the backend to start, then open the Vue UI, choose a future appointment time, check availability, and confirm a booking.

Stop services with `docker compose down`; named volumes retain database and Redis data. The Docker backend image skips tests during packaging, so run the test suite separately as described below.

## Build locally

For a backend build that includes tests, prepare the dedicated MySQL test database using the next section, then run from `code/`:

```sh
cd backend
mvn clean package
```

The executable JAR is `backend/target/scheduler-0.1.0-SNAPSHOT.jar` relative to `code/`. To compile and package without running tests, use `mvn clean package -DskipTests`; this does not verify booking behavior.

For the frontend, start from `code/` in another terminal:

```sh
cd frontend
npm ci
npm run build
```

The generated frontend files are in `frontend/dist/`. The supplied Docker setup runs the Vite development server; deploying the generated files requires a web server and API routing configuration.

## Run services separately

Start MySQL first. From this directory, run `docker compose up -d mysql`. Then start the backend with Redis locks disabled:

```sh
cd backend
SCHEDULER_BOOKING_REDIS_LOCKS_ENABLED=false mvn spring-boot:run
```

`SCHEDULER_BOOKING_REDIS_LOCKS_ENABLED` defaults to `true`, so Docker Compose uses shared Redis idempotency locks. Set it to `false` when running a single backend separately to use in-process idempotency locks without starting the Redis Cluster. Database row locks still protect resource allocation; in-process locks do not coordinate idempotency requests across multiple backend instances.

In another terminal:

```sh
cd frontend
npm ci
npm run dev
```

The frontend dev server proxies `/api` requests to `http://localhost:8080`.

## Backend tests

For the bundled local MySQL instance, run the following from `code/`. Once MySQL is healthy (`docker compose ps mysql`), create the separate test database and grant the demo user access:

```sh
docker compose up -d mysql
docker compose exec -T mysql mysql -uroot -plocal-root-password <<'SQL'
CREATE DATABASE IF NOT EXISTS scheduler_test;
GRANT ALL PRIVILEGES ON scheduler_test.* TO 'scheduler'@'%';
SQL
```

Then run from `code/`:

```sh
cd backend
mvn test
```

Redis is disabled in the test profile, so the backend tests do not require the Redis Cluster. There is no frontend automated test suite; `npm run build` checks that the demo client bundles successfully.

 Integration and opt-in performance tests use a dedicated MySQL database named `scheduler_test`; create it before running tests and grant the configured test user permission to create/drop tables and triggers. Set `TEST_MYSQL_URL`, `TEST_MYSQL_USERNAME`, and `TEST_MYSQL_PASSWORD` to point to another MySQL test database. The performance suite is opt-in: add `-DrunPerformanceTests=true` and configure its `performance.*` properties as needed. Do not point these tests at a database containing data you need; the suites delete and reseed their tables.

If a local MySQL Flyway migration fails, repair the schema history after correcting the migration, then restart with a clean build. For the default local database credentials:

```sh
cd backend
mvn -Dflyway.url='jdbc:mysql://localhost:3306/scheduler?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC' \
    -Dflyway.user=scheduler \
    -Dflyway.password=scheduler \
    -Dflyway.locations=filesystem:src/main/resources/db/migration \
    flyway:repair
SCHEDULER_BOOKING_REDIS_LOCKS_ENABLED=false mvn clean spring-boot:run
```

Use the URL and credentials configured for your local MySQL instance if they differ.

## API overview

- `GET /api/reference-data` — the first 20 dealerships, vehicles, and service types (bounded initial options).
- `GET /api/reference-data/{catalog}?q=&page=0&size=20` — search and page through `dealerships`, `vehicles`, or `service-types`. Returns `items`, `page`, `size`, and `hasNext`; pages start at zero, size is 1–100, page is 0–10000, and search is at most 100 characters. Vehicles match VIN, make, or model; other catalogs match name. Results have a stable ID tie-breaker. The booking form uses dropdowns with Previous/Next controls to page through these endpoints rather than loading entire catalogs.
- `GET /api/availability?dealershipId=1&vehicleId=1&serviceTypeId=1&desiredStart=2026-11-01T09:00:00` — checks the requested interval.
- `POST /api/appointments` — reserves a qualified technician and bay and creates an appointment.
- `GET /api/appointments/idempotency-result` — polls an appointment request using its `Idempotency-Key` header.
- `GET /api/appointments/{id}` — retrieves an appointment.

The backend maps dealerships, vehicles, service types, technicians, service bays, and appointments with JPA entities and Spring Data repositories. Booking requests acquire the vehicle row with `NOWAIT`, then scan technician and bay rows with `SKIP LOCKED`; they recheck interval overlaps while holding the selected row locks and persist the appointment. Requests competing for a locked vehicle fail immediately with `409`, while a booking can skip locked technician or bay candidates and try another pair. `READ COMMITTED` ensures overlap rechecks see appointments committed after candidate discovery. Redis coordinates in-flight idempotency keys across backend instances; database row locks protect resource allocation. The local Compose setup starts a six-node Redis Cluster (three primaries and three replicas) and initializes it before the backend starts.

Database connections set MySQL's session `innodb_lock_wait_timeout` to 3 seconds, including implicit foreign-key lock waits during inserts. Override it with `SCHEDULER_BOOKING_DB_LOCK_WAIT_SECONDS`. Booking transactions have a 25-second deadline (override with `SCHEDULER_BOOKING_TRANSACTION_TIMEOUT_SECONDS`), which leaves room for the 15-second manual demo delay. The transaction deadline is enforced at database operations; it does not interrupt Java code or act as an HTTP request timer. Pool acquisition is capped at 3 seconds; Redis connection and command timeouts are 2 seconds. Database lock/query timeouts and expired transactions return `503 Service Unavailable` with `Retry-After: 1`; retry the POST using the same idempotency key. Existing vehicle `NOWAIT` conflicts still return `409`. Keep upstream HTTP timeouts above the booking transaction budget with room for connection acquisition and response handling.

Appointment creation accepts an optional `Idempotency-Key` header (1–128 visible ASCII characters). Clients should reuse the same key when retrying a request: the API returns the original appointment for an identical request, and responds with `409 Conflict` if the key is reused with a different payload. Keys are stored uniquely with appointments and are retained for the lifetime of the appointment record. Requests without the header keep the original booking behavior.

If another request is currently processing the same key, creation responds immediately with `202 Accepted` and `Retry-After: 1`. Poll `GET /api/appointments/idempotency-result` with the same `Idempotency-Key`: it returns `202 Accepted` while processing, `200 OK` with the appointment when complete, or `404 Not Found` if no request has been recorded for that key. If the original request rolls back, retry the POST with the same key.

Redis idempotency locks have a two-minute lease that is automatically renewed every 40 seconds while held. Renewal stops on unlock after transaction completion; a stopped instance's locks expire without renewal. The renewal scheduler is managed by Spring and shuts down with the application. A Redis outage or process pause longer than the lease can still cause lock loss; database row locks and the unique idempotency constraint remain the final safeguards for persisted data.

To manually exercise the in-flight path in the UI, enable **Simulate duplicate request (15-second manual test)** before confirming. The local Compose backend enables this test-only delay: the first keyed request holds the idempotency lock for 15 seconds and the duplicate should receive `202` while the UI polls for the result. If running the backend outside Compose, set `SCHEDULER_BOOKING_MANUALTESTDELAYENABLED=true`; the default is disabled.

Backend logs use readable console output for the default `local` profile and JSON to stdout for other profiles. Set `APP_ENVIRONMENT` to select the default profile and label the deployment environment; each HTTP request also receives an `X-Request-Id` (incoming safe values are reused) and returns it in the response. Booking events include outcome and dealership ID, with booking ID on success. A valid W3C `traceparent` contributes its trace ID. Do not add customer, VIN, or other personal details to log fields.

All sample data is for local development only. Authentication, authorization, timezone policy, cancellation, rescheduling, and production deployment configuration are not included in this starter.

## Booking metrics

Actuator exposes these metrics after their corresponding operations have run:

| Metric | Type | Tags |
| --- | --- | --- |
| `booking.lock.wait` | Timer | `resource=vehicle/technician/bay/idempotency`; `outcome=acquired/unavailable/contended/error` |
| `booking.resource.search` | Timer | `outcome=found/unavailable/error` |
| `booking.resource.overlap.checks` | Distribution summary | `resource=technician/bay` |
| `booking.availability` | Timer | `outcome=available/vehicle_busy/resources_busy/error` |

`booking.lock.wait` measures the complete lock acquisition operation, including database candidate selection or Redis communication; it is not a database engine measurement of time blocked on a row lock. NOWAIT/SKIP LOCKED operations can finish quickly even under contention. `unavailable` means no matching row was returned (missing, busy, or skipped); `contended` means an explicit database lock failure or a rejected idempotency lock acquisition. Idempotency acquisition is measured for both POST booking and GET polling. There is no dealership lock in the current booking flow.

Each resource search records one overlap-check summary sample per resource, including zero when that resource was never queried. Values count attempted queries containing overlap predicates, both candidate selection and booking rechecks after locking. They do not count rows examined internally by SQL, and exclude the separate vehicle overlap check. Failures still record elapsed time and attempted query counts. Availability validation and dependency failures use `outcome=error`.

The three timers publish histogram buckets for Prometheus percentile queries. Tags contain fixed categories only; identifiers are not metric tags.

For example, after checking availability and submitting a booking:

```sh
curl -s http://localhost:8080/actuator/metrics/booking.lock.wait
curl -s http://localhost:8080/actuator/metrics/booking.resource.search
curl -s http://localhost:8080/actuator/metrics/booking.resource.overlap.checks
curl -s http://localhost:8080/actuator/metrics/booking.availability
curl -s http://localhost:8080/actuator/prometheus | rg 'booking_(lock_wait|resource_search|resource_overlap_checks|availability)'
```

Prometheus exports timer values in seconds, for example `booking_lock_wait_seconds_count`, `booking_lock_wait_seconds_sum`, and `booking_lock_wait_seconds_bucket`. Summary `_count` is the number of searches sampled and `_sum` is the total number of attempted overlap queries for that resource.
