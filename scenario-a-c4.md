# Scenario A: Unified Service Scheduler — C4 Model

This model follows the challenge requirements: a customer requests a service appointment for a vehicle, service type, dealership, and desired time; the system checks for a qualified technician and service bay across the full service duration; a successful booking is stored as an appointment associating the customer, vehicle, technician, and bay.

## Level 1 — System Context

```mermaid
C4Context
    title System Context — Unified Service Scheduler

    Person(customer, "Customer", "Requests and manages a service appointment for a vehicle.")
    System(scheduler, "Unified Service Scheduler", "Finds a suitable appointment slot and records confirmed bookings.")

    Rel(customer, scheduler, "Submits appointment request; views booking result")
```

## Level 2 — Container Diagram

```mermaid
C4Container
    title Container Diagram — Unified Service Scheduler

    Person(customer, "Customer", "Requests an appointment and receives the result.")

    System_Boundary(scheduler_boundary, "Unified Service Scheduler") {
        Container(web, "Booking Web App", "Web application", "Collects vehicle, service type, dealership, and desired time; displays availability and confirmation.")
        Container(api, "Scheduler API", "REST/HTTPS", "Validates requests, coordinates availability checks, and creates confirmed appointments.")
        ContainerDb(db, "Scheduler Database", "Relational database", "Stores customers, vehicles, dealerships, service types, technician qualifications, service bays, and appointments.")
    }

    Rel(customer, web, "Uses", "HTTPS")
    Rel(web, api, "Submits booking request / reads result", "JSON over HTTPS")
    Rel(api, db, "Reads availability data; stores confirmed appointment", "SQL")
```

## Level 3 — Scheduler API Components

```mermaid
C4Component
    title Component Diagram — Scheduler API

    Container_Boundary(api_boundary, "Scheduler API") {
        Component(booking, "Booking Endpoint", "HTTP controller", "Accepts appointment requests and returns validation errors or a booking result.")
        Component(validation, "Request Validator", "Application component", "Checks required identifiers, requested time, and service duration inputs.")
        Component(orchestrator, "Scheduling Orchestrator", "Application service", "Coordinates eligibility, availability, and appointment creation as one booking workflow.")
        Component(eligibility, "Technician Qualification Checker", "Domain service", "Finds technicians qualified for the requested service type at the selected dealership.")
        Component(availability, "Resource Availability Checker", "Domain service", "Checks technician and service-bay schedules for the entire service duration.")
        Component(repository, "Appointment Repository", "Persistence adapter", "Loads resource schedules and persists the confirmed appointment and its resource associations.")
    }

    ContainerDb(db, "Scheduler Database", "Relational database", "Stores appointment and resource scheduling data.")

    Rel(booking, validation, "Validates request")
    Rel(booking, orchestrator, "Starts scheduling workflow")
    Rel(orchestrator, eligibility, "Gets qualified technicians")
    Rel(orchestrator, availability, "Finds a slot with both resources free")
    Rel(eligibility, repository, "Reads qualifications")
    Rel(availability, repository, "Reads technician and bay bookings")
    Rel(orchestrator, repository, "Creates confirmed appointment")
    Rel(repository, db, "Reads and writes", "SQL")
```

## Booking flow and key rules

![BPMN 2.0 booking flow](scenario-a-booking-flow.png)

1. The customer submits a vehicle, service type, dealership, and desired time through the web app.
2. The API validates the request and resolves the requested service duration.
3. The scheduler finds technicians qualified for that service at that dealership.
4. It checks candidate technicians and service bays for a continuous free interval covering the entire service duration. A partial overlap or availability of only one resource is insufficient.
5. If no pair of resources is available, the request is not confirmed and the customer receives an unavailable result (and may choose another time).
6. If both resources are available, the system creates one persistent appointment associating customer, vehicle, technician, and service bay.

## Assumptions captured in the model

- The booking user is modeled as the customer; the challenge does not specify separate staff-facing workflows.
- The scheduler owns its booking and resource data. No external dealer-management system or notification provider is required by the stated scenario.
- Appointment creation must be atomic with resource allocation so two concurrent requests cannot confirm overlapping use of the same technician or bay. The diagram leaves the database transaction/locking technique as an implementation decision.
- Authentication, payment, cancellation, rescheduling, and notification behavior are outside the stated acceptance criteria and are not modeled.
