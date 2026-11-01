# Keyloop Technical Assessment — Unified Service Scheduler

Scenario A: reserve a qualified technician and a service bay for the full duration of a vehicle service. The selected implementation layer is the backend: a Spring Boot REST API with MySQL persistence and OpenAPI documentation. A Vue booking page provides an interactive demonstration client.

## Submission artifacts

| Artifact | Location / status |
| --- | --- |
| System Design Document | [Architecture, data flow, assumptions, observability, and design-phase GenAI collaboration](System-Design-Document-scenario-a-c4.md) |
| Technology choices | [Selected technologies and justifications](scenario-a-technologies.md) |
| Working code | [Application source](code/) and [build, run, and test instructions](code/README.md) |
| AI Collaboration Narrative | [AI guidance, verification, refinement, and quality ownership](code/README.md#ai-collaboration-narrative) |
| Core business tests | [Backend test suite](code/backend/src/test/java/com/keyloop/scheduler/) and [test scenarios](code/backend/testcase.md) |
| Video submission (5–10 minutes) | Pending — no recording link has been added. |

The [project walkthrough](WALKTHROUGH.md) provides preparation material for the video. The recording must include an introduction and scenario, design and implementation highlights, a 1–2-minute AI collaboration story, an application demonstration, and lessons/challenges.

## Quick start

With Docker and Compose installed, run from the repository root:

```sh
cd code
docker compose up --build
```

Wait for the backend to start, then open the [booking UI](http://localhost:5173) or [Swagger UI](http://localhost:8080/swagger-ui.html). Flyway creates the schema and demo data. Choose a future time to check availability and book an appointment.

For local builds, separate-service startup, test database setup, and the backend test commands, follow the [application README](code/README.md). Docker packaging skips backend tests; run them separately before submission.

## Scope and known gaps

Bookings persist the vehicle, dealership, service type, technician, bay, interval, and status. Customer identity and its appointment association remain unimplemented, despite being requested by the challenge. Authentication, authorization, a complete timezone policy, cancellation/rescheduling, and production deployment configuration are also outside the current demo. See the [system design](System-Design-Document-scenario-a-c4.md) for implementation details and limitations.
