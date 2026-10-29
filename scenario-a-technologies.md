# Scenario A: Recommended Technologies

For **Scenario A**, this stack supports a Spring REST backend, OpenAPI documentation, an interactive Vue booking page, and MySQL.

| Area | Recommended technology | Why it fits |
|---|---|---|
| Backend | **Java 21 + Spring Boot 3** | A mature foundation for the scheduling API and business rules. |
| REST API | **Spring MVC** | Handles booking requests and availability queries over HTTP. |
| Persistence | **Spring Data JPA + Hibernate** | Maps appointments, vehicles, technicians, service bays, and qualifications to the database. |
| Database | **MySQL 8 + InnoDB** | Familiar relational database with transactions and row-level locking. |
| Schema migrations | **Flyway** | Versions database changes and keeps development environments consistent. |
| Request validation | **Jakarta Bean Validation** | Validates required IDs, date/time values, and request fields at the API boundary. |
| API documentation | **OpenAPI via springdoc-openapi** | Provides an OpenAPI specification and Swagger UI for exploring the REST API. |
| Frontend | **Vue 3 + Vite** | Supports a responsive, interactive booking flow with fast local development. |
| Frontend state and routing | **Vue Router**, with **Pinia** if shared state is needed | Manages booking pages and shared selections such as dealership, vehicle, service, and time. |
| Frontend API calls | **Fetch** or **Axios** | Connects the Vue UI to the Spring REST endpoints. |
| Testing | **JUnit 5, Spring Boot Test, Testcontainers with MySQL** | Tests business rules and database behavior using MySQL rather than a different database engine. |
| Local development | **Docker Compose** | Starts MySQL and the backend consistently; Vue can run locally or as a container. |
| Observability | **Spring Boot Actuator + Micrometer** | Exposes health checks and basic application metrics. |

## Suggested API endpoints

- `GET /api/dealerships/{id}/availability` — accepts vehicle, service type, and time range; returns available slots.
- `POST /api/appointments` — requests a booking.
- `GET /api/appointments/{id}` — retrieves the confirmed appointment.

The Vue page can guide the customer through selecting a dealership, vehicle, service type, and time, then display available slots and the booking result.

## Booking safety

The backend should check availability and reserve the vehicle, qualified technician, and service bay within a transaction before creating the appointment. With MySQL, use **InnoDB transactions and row locks or a shared reservation strategy** to prevent concurrent requests from creating overlapping appointments for the same vehicle or reusing the same technician or bay. Test that behavior against MySQL with Testcontainers.

For a focused implementation, start with **Spring Boot, MySQL, Flyway, OpenAPI, and Vue 3**, then add the tests and observability components as the booking flow takes shape.
