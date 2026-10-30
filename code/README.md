# Unified Service Scheduler

Scenario A starter with a Spring Boot REST API, Spring Data JPA/Hibernate persistence, MySQL, OpenAPI/Swagger UI, and a Vue 3 booking page.

## Requirements

- Java 21
- Maven 3.9+
- Node.js 22.18+ (required by current Vue tooling)
- Docker Desktop / Docker Engine with Compose

The machine used to create this scaffold currently has Java 11 and Node 18, so those runtimes must be upgraded before local builds will work.

## Run with Docker Compose

From this directory:

```sh
docker compose up --build
```

- Vue UI: http://localhost:5173
- Swagger UI: http://localhost:8080/swagger-ui.html
- OpenAPI JSON: http://localhost:8080/v3/api-docs
- MySQL: localhost:3306 (database `scheduler`, user `scheduler`)

Flyway creates the schema and sample dealership, vehicle, service, technician, and bay records on startup.

## Run services separately

Start MySQL and the Redis Cluster first. From this directory, run `docker compose up mysql redis-node-1 redis-node-2 redis-node-3 redis-node-4 redis-node-5 redis-node-6 redis-cluster-init`. Then:

```sh
cd backend
mvn spring-boot:run
```

In another terminal:

```sh
cd frontend
npm install
npm run dev
```

The frontend dev server proxies `/api` requests to `http://localhost:8080`.

## Backend tests

Run the backend suite with `cd backend && mvn test`. Integration and opt-in performance tests use a dedicated MySQL database named `scheduler_test`; create it before running tests and grant the configured test user permission to create/drop tables and triggers. Set `TEST_MYSQL_URL`, `TEST_MYSQL_USERNAME`, and `TEST_MYSQL_PASSWORD` to point to another MySQL test database. The performance suite is opt-in: add `-DrunPerformanceTests=true` and configure its `performance.*` properties as needed. Do not point these tests at a database containing data you need; the suites delete and reseed their tables.

If a local MySQL Flyway migration fails, repair the schema history after correcting the migration, then restart with a clean build. For the default local database credentials:

```sh
cd backend
mvn -Dflyway.url='jdbc:mysql://localhost:3306/scheduler?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC' \
    -Dflyway.user=scheduler \
    -Dflyway.password=scheduler \
    -Dflyway.locations=filesystem:src/main/resources/db/migration \
    flyway:repair
mvn clean spring-boot:run
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

To manually exercise the in-flight path in the UI, enable **Simulate duplicate request (15-second manual test)** before confirming. The local Compose backend enables this test-only delay: the first keyed request holds the idempotency lock for 15 seconds and the duplicate should receive `202` while the UI polls for the result. If running the backend outside Compose, set `SCHEDULER_BOOKING_MANUALTESTDELAYENABLED=true`; the default is disabled.

Backend logs use readable console output for the default `local` profile and JSON to stdout for other profiles. Set `APP_ENVIRONMENT` to select the default profile and label the deployment environment; each HTTP request also receives an `X-Request-Id` (incoming safe values are reused) and returns it in the response. Booking events include outcome and dealership ID, with booking ID on success. A valid W3C `traceparent` contributes its trace ID. Do not add customer, VIN, or other personal details to log fields.

All sample data is for local development only. Authentication, authorization, timezone policy, cancellation, rescheduling, and production deployment configuration are not included in this starter.
