# Backend Test Cases

Checkboxes track whether automated test code exists; they do not indicate that the tests have been run or passed.

## Input validation

- [x] Unknown dealership returns 404.
- [x] Unknown vehicle returns 404.
- [x] A start time in the past returns 400.
- [x] Unknown service type returns 404.
- [x] A start time captured as current immediately before validation is rejected as past.

## Availability

- [x] A qualified technician and free bay are available: return `available=true` with the correct end time.
- [x] No qualified technicians: return `available=false`.
- [x] No service bays: return `available=false`.
- [x] The technician is busy, but another qualified technician is free: return `available=true`.
- [x] The bay is busy, but another bay is free: return `available=true`.
- [x] All qualified technicians are busy: return `available=false`.
- [x] All bays are busy: return `available=false`.
- [x] An appointment ending exactly at the requested start does not count as an overlap.
- [x] An appointment starting exactly at the requested end does not count as an overlap.
- [x] Partial, enclosing, and enclosed time overlaps count as conflicts.
- [x] Appointments with a non-`CONFIRMED` status do not block availability.
- [x] An overlapping confirmed appointment for the same vehicle makes availability false, even if another technician and bay are free.

## Booking

- [x] A valid request creates an appointment and returns its details.
- [x] The appointment end time matches the service duration.
- [x] The appointment uses a technician qualified for the requested service and a bay belonging to the dealership.
- [x] No available technician and bay returns 409 and creates no appointment.
- [x] Unknown dealership returns 404.
- [x] Unknown vehicle returns 404.
- [x] Unknown service type returns 404.
- [x] A past start time returns 400.
- [x] Booking the same vehicle for an overlapping appointment returns 409, even for a different service, and does not insert a second appointment.
- [x] Booking the same vehicle in a non-overlapping interval remains allowed.
- [x] An insert failure leaves no partial booking.
- [x] Concurrent requests for the same limited resources do not both reserve the same interval.
- [x] Concurrent requests for the same vehicle do not both book overlapping intervals when multiple resource pairs are available.
- [x] Concurrent requests for the same vehicle at different dealerships do not both book overlapping intervals.

## Appointment retrieval

- [x] An existing appointment returns all mapped fields correctly.
- [x] An unknown appointment ID returns 404.
- [x] The vehicle label and timestamps are mapped correctly.

## Reference data

- [x] Dealership, vehicle, and service options are returned.
- [x] Vehicle labels include year, make, model, and VIN.
- [x] Each option includes the expected ID and fields.
- [x] Results follow the ordering specified by the queries.

## HTTP API and request validation

- [x] Each endpoint returns the expected status and response body for success.
- [x] Missing, null, zero, or negative IDs in a booking request are rejected with 400.
- [x] Missing or malformed `desiredStart` is rejected.
- [x] Booking errors map to the intended 400, 404, and 409 responses with the expected error body.
- [x] Missing or malformed availability query parameters return a client error.

## Performance

The performance suite is opt-in (`-DrunPerformanceTests=true`). It records throughput, p50/p95/p99 response latency, response status counts, JVM heap usage, and Hikari connection-pool counts. Capture database CPU externally during the run. Set pass/fail latency and throughput thresholds from the service SLA before running; this project does not currently specify numeric targets.

- [x] **Reference data load:** Request `GET /api/reference-data` repeatedly with scaled dealership, vehicle, and service-type data; record latency and throughput.
- [x] **Availability baseline:** Measure `GET /api/availability` latency and throughput with 10 technicians and bays.
- [x] **Availability growth:** Repeat the availability load with increasing appointment history and resources; compare metrics to the agreed threshold and inspect query plans if performance degrades.
- [x] **Booking throughput:** Send concurrent valid `POST /api/appointments` requests across independent dealerships and time slots; measure successful bookings per second, latency, and error rate.
- [x] **Booking contention:** Send concurrent requests for the same dealership and overlapping time slots with limited resources; measure latency and conflict rate, and verify no double-bookings occur.
- [x] **Mixed endpoint load:** Run reference-data, availability, booking, and appointment-retrieval traffic together at a 25% mix per endpoint; record measurements for comparison to agreed thresholds.
- [x] **Sustained load:** Maintain concurrent traffic for a configurable duration; record latency, response statuses, JVM heap, and connection-pool counts. Monitor database CPU externally.
- [x] **Capacity step test:** Increase concurrency from 1 to 16; record throughput and latency at each step for capacity review.
