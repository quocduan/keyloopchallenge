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

- `GET /api/reference-data` — dealerships, vehicles, and service types for the UI.
- `GET /api/availability?dealershipId=1&vehicleId=1&serviceTypeId=1&desiredStart=2026-11-01T09:00:00` — checks the requested interval.
- `POST /api/appointments` — reserves a qualified technician and bay and creates an appointment.
- `GET /api/appointments/{id}` — retrieves an appointment.

The backend maps dealerships, vehicles, service types, technicians, service bays, and appointments with JPA entities and Spring Data repositories. Booking requests first acquire shared Redis locks for the dealership and vehicle through Spring Integration's `RedisLockRegistry`, then use pessimistic MySQL row locks in a transaction, check resource overlap across the complete service duration, and persist the appointment. The Redis locks coordinate backend instances; the database locks remain the final protection for the booking data. The local Compose setup starts a six-node Redis Cluster (three primaries and three replicas) and initializes it before the backend starts.

Backend logs are emitted as JSON to stdout. Set `APP_ENVIRONMENT` to label the deployment environment; each HTTP request also receives an `X-Request-Id` (incoming safe values are reused) and returns it in the response. Booking events include outcome and dealership ID, with booking ID on success. A valid W3C `traceparent` contributes its trace ID. Do not add customer, VIN, or other personal details to log fields.

All sample data is for local development only. Authentication, authorization, timezone policy, cancellation, rescheduling, and production deployment configuration are not included in this starter.
