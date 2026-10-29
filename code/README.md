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

Start MySQL first (or use `docker compose up mysql`). Then:

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

## API overview

- `GET /api/reference-data` — dealerships, vehicles, and service types for the UI.
- `GET /api/availability?dealershipId=1&vehicleId=1&serviceTypeId=1&desiredStart=2026-11-01T09:00:00` — checks the requested interval.
- `POST /api/appointments` — reserves a qualified technician and bay and creates an appointment.
- `GET /api/appointments/{id}` — retrieves an appointment.

The backend maps dealerships, vehicles, service types, technicians, service bays, and appointments with JPA entities and Spring Data repositories. Booking requests lock the dealership row with a pessimistic JPA lock inside a MySQL transaction, check both resources for overlap across the complete service duration, then persist the appointment. This serializes bookings made through this API per dealership. If appointments can be written by other systems, replace this with a shared reservation/locking strategy.

All sample data is for local development only. Authentication, authorization, timezone policy, cancellation, rescheduling, and production deployment configuration are not included in this starter.
