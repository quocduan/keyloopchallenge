# Unified Service Scheduler — Project Walkthrough

This walkthrough follows the project conversations from **28–31 October 2026**, showing how Scenario A evolved from design into a working booking application and then through a backend quality review. Times below use **Asia/Ho_Chi_Minh (UTC+7)** and refer to conversation turns, not necessarily commit times.

The source is the 21 earlier chats associated with this repository and its worktrees, including older paginated turns. Shared turns inherited by forked chats are counted once. This is a synthesis of decisions and outcomes, rather than a verbatim transcript. Historical test results are reports from those conversations; no application tests were rerun to produce this document. Current implementation details were cross-checked against the repository on 31 October.

## 1. Understand the challenge — 28 October, 17:11

The first discussion compared the four options in `KeyloopCodingChallange.pdf`:

| Scenario | Business area | Main requirement |
| --- | --- | --- |
| A — Unified Service Scheduler | Ownership | Reserve a qualified technician and service bay for a vehicle service |
| B — Intelligent Inventory Dashboard | Supply | Identify aging stock and persist proposed actions |
| C — Sales Lead Management Tool | Demand | Track leads and chronological follow-up activities |
| D — Unified Document Viewer | Operations | Combine vehicle documents from two mocked APIs queried in parallel |

The project proceeded with **Scenario A**. Its central rule is that both the technician and the bay must be free for the **entire service duration**, and a successful booking must persist the allocated resources.

**Walkthrough talking point:** Explain the business invariant before showing frameworks or code. A successful availability check alone cannot guarantee a booking; concurrent requests can change availability before confirmation.

## 2. Design the booking flow — 29 October, 08:23–08:54

The design work preceded implementation:

- `System-Design-Document-scenario-a-c4.md` describes system context, containers, components, and assumptions.
- `scenario-a-booking-flow.bpmn` models validation, technician qualification, full-duration availability, atomic reservation, and success/failure outcomes.
- `scenario-a-booking-flow.png` was embedded in the architecture document.

The intended flow became: select a dealership, a specific vehicle, a service, and a desired start; validate inputs; determine the end from service duration; find qualified resources; reserve safely; persist and return the appointment.

## 3. Choose the stack and scaffold — 29 October, 09:01–09:22

The initial recommendations included PostgreSQL and alternative frontend approaches. The user explicitly chose a **REST backend with OpenAPI, Vue.js for interactive booking, and MySQL for familiarity and simplicity**.

The technology decision was recorded in `scenario-a-technologies.md`, followed by a scaffold under `code/`:

| Layer | Selected implementation |
| --- | --- |
| Backend | Java 21, Spring Boot, Spring MVC, Jakarta Bean Validation |
| Persistence | Spring Data JPA/Hibernate, MySQL/InnoDB |
| Schema | Flyway migrations; Hibernate validates the schema |
| API exploration | springdoc-openapi and Swagger UI |
| Frontend | Vue 3 and Vite |
| Local environment | Docker Compose |
| Observability | Actuator, Micrometer, Logback |

The first generated backend used `JdbcTemplate` despite the selected JPA stack. That mismatch was corrected later. Vue Router and optional Pinia were discussed but were not needed for the single booking page and are absent from the current frontend dependencies.

Early builds were blocked by the machine's Java 11/Node 18 runtimes. The documented prerequisites became Java 21, Maven 3.9+, and Node 22.18+.

## 4. Correct the scaffold and establish tests — 29 October, 09:43–11:55

An ambiguous `JdbcTemplate.query` lambda in `validateInputs` prompted an initial fix using explicit count queries. The project then moved to its intended **JPA entities and Spring Data repositories** at 10:22, replacing that JDBC implementation.

Entities cover dealerships, vehicles, service types, technicians, service bays, and appointments. Controllers handle HTTP concerns; `BookingService` coordinates business rules and repositories handle persistence. The first JPA booking strategy used a pessimistic dealership-row lock within a transaction.

Testing progressed from three service validation tests to a broader checklist in `code/backend/testcase.md`, API integration tests, and opt-in performance tests. Topics included validation, resource qualification, interval overlap, persistence, retrieval, errors, concurrency, throughput, and latency. Performance thresholds remained dependent on a future service SLA.

Several failures were test/setup problems: timestamp assertions omitted seconds, Mockito needed a suitable mock maker, and performance fixtures reused an H2 database or IDs. These were corrected, but the later MySQL migration superseded the H2 setup.

### Add the missing vehicle invariant

At 11:39, discussion exposed a real gap: technician/bay checks did not prevent the **same vehicle** from receiving overlapping bookings when another resource pair was available. An initial response addressed a different test failure; the user clarified the requirement and directed the implementation.

The resulting rule rejects overlapping **confirmed** appointments for a vehicle, including different services and different dealerships. Adjacent intervals remain allowed. Tests, documentation, a vehicle-row lock, and a vehicle/time index were added. The implementation was subsequently rechecked after tests revealed missing vehicle checks in the active code.

**Walkthrough talking point:** The vehicle is a constrained resource too. Service type does not make simultaneous appointments for the same physical car valid.

A backend `TODO.md` was created and ignored; the root `todo.md` became the broader implementation and review checklist.

## 5. Strengthen the model and migrations — 29 October, 14:10–15:04

Appointment status changed from a free-form string to `AppointmentStatus`, persisted with `EnumType.STRING`: `CONFIRMED`, `CANCELLED`, `COMPLETED`, and `NO_SHOW`. Only confirmed appointments block availability. The enum does not itself implement cancellation or rescheduling workflows.

For large appointment tables, overlap indexes were adjusted to put equality filters before time ranges:

```text
(vehicle_id, status, starts_at, ends_at)
(technician_id, status, starts_at, ends_at)
(service_bay_id, status, starts_at, ends_at)
```

The overlap predicate is:

```sql
starts_at < requested_end AND ends_at > requested_start
```

This allows back-to-back appointments. Indexes help narrow the search, but both range predicates do not become equally selective merely by adding columns.

Migration debugging exposed duplicate V3 versions, stale files under `target/classes`, and MySQL refusing to drop an index supporting a foreign key. The corrected approach creates replacement indexes **before** dropping old ones and uses unique migration versions. Flyway repair and a clean startup were reported to apply V4/V5 successfully.

The current sequence is V1 schema, V2 seed data, V3 vehicle/time index, V4 status-aware vehicle index, V5 status-aware technician/bay indexes, and V6 idempotency fields. Flyway applies pending migrations on startup before Hibernate schema validation. The README retains local repair instructions for a failed migration after its cause is corrected.

## 6. Add cross-instance locking and logging — 29 October, 15:17–17:46

A technology audit identified missing custom metrics and the H2-versus-MySQL test gap. The locking discussion clarified that multiple instances of the booking service need shared coordination; a Java in-memory lock only coordinates one process.

`RedisLockRegistry` and a six-node Redis Cluster were implemented. Initially Redis coordinated dealership and vehicle locks, alongside database safeguards. Locks were released after transaction completion. The registry namespace uses `{booking}` so Redis Cluster places the Lua script's related keys in the same slot.

Two alternatives were discussed without being added: **ShedLock** fits scheduled-job coordination; **OpenFeign** is an HTTP client. Neither substitutes for transactional booking allocation.

Structured logging then added:

- JSON stdout output for non-local environments and readable local console logs.
- Safe incoming `X-Request-Id` reuse or UUID generation, with the ID returned in the response.
- MDC request correlation and a trace ID extracted from a valid `traceparent`.
- Booking attempt, success, rejection/conflict, and failure events without customer or VIN details.

Copying a trace ID into logs is not full distributed tracing. A later review also raised transaction-completion accuracy for success telemetry; do not present every observability recommendation as completed.

The initial Micrometer work added Prometheus export, booking outcome counters, and booking-duration timers. cAdvisor was discussed as complementary container monitoring: it does not expose the application's booking or JVM instrumentation.

## 7. Resolve Redis development-environment issues — 29 October, 17:59 to 30 October, 09:09

An exited `redis-cluster-init` container was explained as expected **when its one-time cluster setup exits successfully with code 0**. The backend depends on successful completion.

Host debugging exposed a more substantial issue: publishing Redis ports and using localhost seed addresses did not change the Docker-internal addresses advertised by Redis Cluster. Lettuce reached a seed and then failed when redirected to `172.23.0.x` nodes.

The experimental port mappings and `.env` import were explicitly reverted on 30 October. A shared/external Docker network would not make a host JVM join Docker's network by name.

The eventual supported options are to run the backend with the cluster inside Compose, or run one backend separately with Redis idempotency locks disabled. The final environment-variable switch is covered in section 12.

## 8. Implement idempotency and a visible in-flight flow — 30 October, 09:58–11:45

`POST /api/appointments` gained an optional `Idempotency-Key`. The client creates one key per logical booking and reuses it for retries. A deterministic payload fingerprint detects incompatible reuse; the key and fingerprint have different purposes.

| Request situation | Behavior |
| --- | --- |
| Same key, same completed payload | Return the original appointment |
| Same key, different completed payload | `409 Conflict` |
| Same key currently processing | Immediate `202 Accepted`, `Retry-After: 1` |
| Poll completed result | `200 OK` with appointment |
| Poll still processing | `202 Accepted` |
| Poll key with no recorded result | `404 Not Found`; retry POST if the original rolled back |

The first implementation waited for the key lock. The user requested an immediate “processing” response and polling instead, so `GET /api/appointments/idempotency-result` and frontend polling were added. V6 stores a unique key and request fingerprint with the appointment; a schema mismatch between `CHAR(64)` and Hibernate's expected `VARCHAR(64)` was corrected.

For manual demonstration, the UI gained **Simulate duplicate request (15-second manual test)**. The first request holds the key lock during a test-only delay so the duplicate can reliably receive `202`. The correct enablement variable is `SCHEDULER_BOOKING_MANUALTESTDELAYENABLED`; the earlier underscored spelling did not load as intended. Compose enables the demo; it is disabled by default elsewhere.

A Docker build failure during this work was traced to Maven Central connectivity, separately from Java source correctness.

## 9. Review quality and remove dealership serialization — 30 October, 11:48–17:18

The backend review evaluated scalability, performance, reliability, maintainability, and observability. It found dealership-wide serialization, repeated overlap queries, unbounded reference reads, insufficiently bounded waits, a fixed Redis lease, and narrow metric coverage.

The concurrency fix evolved through user questions about contention:

1. Remove dealership-wide allocation locking and lock the actual resources.
2. Acquire the vehicle row with **NOWAIT**, returning `409` promptly when held.
3. Select technician and bay candidates with **SKIP LOCKED**, trying alternatives instead of waiting on another transaction's candidates.
4. Recheck overlaps while holding the selected row locks.
5. Use **READ COMMITTED** so subsequent checks see appointments committed after candidate discovery.
6. Persist within the transaction; keep Redis coordination for idempotency keys.

The current allocation strategy therefore differs from the early dealership-plus-Redis design. Database locks protect resource allocation; Redis handles in-flight keyed requests.

### Test the real database instead of adapting production to H2

An attempted response to H2 lock-test failures changed repository queries to native SQL. The user rejected changing production behavior to accommodate H2 limitations and directed a move to MySQL. The repositories returned to JPA queries and Hibernate lock hints.

Tests briefly used MySQL Testcontainers, then switched to a separately running MySQL database because Docker was unavailable to the test process. Current integration and performance suites use `scheduler_test`, configured through `TEST_MYSQL_URL`, `TEST_MYSQL_USERNAME`, and `TEST_MYSQL_PASSWORD`. They run Flyway, clear/reseed data, and require a dedicated test database. Mockito-only tests remain database-independent.

A clean MySQL run was reported as **59 passing tests**, including seven performance tests, at that stage. Later patches added more cases, so this is historical evidence rather than the final suite count.

The VIN discussion also clarified that a VIN identifies a **specific physical vehicle**, not merely a model. Selecting a seeded vehicle does not implement customer registration, vehicle ownership verification, or VIN decoding.

## 10. Bound resource queries and catalog reads — 30 October, 17:22–19:17

The resource-selection review still found nested technician/bay overlap queries after the lock changes. The fix moved availability filtering into SQL/JPQL using **NOT EXISTS** and selected technicians and bays independently. Booking retained SKIP LOCKED and checks after locking.

Regression fixtures with many busy resources enforce a small query count (at most ten SQL statements for the covered scenarios), catching a return to queries inside nested loops. That bound describes those fixtures, not every possible database workload.

Reference data then changed from loading every row to:

- A bounded initial response: first 20 entries per catalog.
- Paginated catalog endpoints with optional backend search.
- Validated limits: size 1–100, page 0–10000, search length at most 100.
- Stable ordering with an ID tie-breaker.

The frontend initially gained search inputs. At the user's request those were removed; dropdowns and Previous/Next pagination remain. Backend search support remains available. API response DTOs use Java records; these are ordinary data carriers, not sealed records.

## 11. Bound waits, renew leases, and explain ownership — 30 October, 19:18–21:17

Timeout changes addressed waits beyond explicit NOWAIT/SKIP LOCKED acquisition:

| Boundary | Current default |
| --- | --- |
| Other MySQL/InnoDB row-lock waits, including implicit foreign-key waits | 3 seconds per session |
| Booking transaction deadline | 25 seconds |
| Hikari connection acquisition | 3 seconds |
| Redis connection / command timeout | 2 seconds each |
| Redis idempotency lease / renewal | 2 minutes / every 40 seconds |

Database timeout/expired transaction failures map to `503 Service Unavailable` with `Retry-After: 1`; clients retry with the same key. Vehicle NOWAIT conflicts retain `409`. The transaction deadline is enforced at database operations; it does not interrupt Java sleep or act as an HTTP timer.

The repositories use Hibernate constants (`LockOptions.NO_WAIT`, `LockOptions.SKIP_LOCKED`, `SpecHints.HINT_SPEC_LOCK_TIMEOUT`) instead of unexplained numeric literals. A positive JPA timeout hint does not supply MySQL's per-query timed wait, so the connection's `innodb_lock_wait_timeout` bounds other waits.

Redis renewal uses a Spring-managed scheduler and stops on unlock or application shutdown. A pause/outage longer than the remaining lease can still lose ownership; row locks and the unique idempotency constraint remain persistence safeguards. Seven focused renewal/configuration tests were reported passing with mocked Redis; that was not a live Redis failover test.

The polling explanation established an ownership rule: `obtain()` returns a handle, while `tryLock()` acquires ownership. A polling request that fails `tryLock()` returns `202` before entering the unlock block. A successful poll releases its own acquisition; it does not unlock the booking request's lock.

## 12. Add diagnostic metrics and finish local configuration — 30 October, 21:26 to 31 October, 08:46

Four additional metrics were implemented after first being discussed as proposals:

| Metric | Purpose |
| --- | --- |
| `booking.lock.wait` | Time the full lock-acquisition operation |
| `booking.resource.search` | Time resource selection |
| `booking.resource.overlap.checks` | Count attempted overlap queries per search and resource |
| `booking.availability` | Time availability checks and classify outcomes |

Tags use bounded categories rather than identifiers. Current lock resources are vehicle, technician, bay, and idempotency; the removed dealership lock is not instrumented. The timers publish Prometheus histogram buckets. Fourteen focused tests were reported passing.

Lock time includes query execution or Redis communication, rather than only time blocked inside a database engine. Overlap counts measure attempted queries, not scanned rows, and exclude the separate vehicle overlap query. These distinctions matter when interpreting dashboards.

The final configuration change makes Redis selection environment-driven:

```yaml
redis-locks-enabled: ${SCHEDULER_BOOKING_REDIS_LOCKS_ENABLED:true}
```

Setting it to `false` selects `DefaultLockRegistry` for one backend instance. The default uses shared `RedisLockRegistry`; its conditional configuration also creates the renewal scheduler. Current Git history records these final observability/configuration changes in commit `487277b` on 31 October at 08:53.

## 13. Demonstrate the resulting application

Use this sequence for a live walkthrough after explaining the development history:

1. Open the C4 and BPMN artifacts and state the vehicle/technician/bay invariants.
2. Show the repository layout, `BookingService`, resource repositories, Flyway migrations, and the test checklist.
3. Start the complete environment from the repository root:

   ```sh
   cd code
   docker compose up --build
   ```

4. Open the Vue UI at `http://localhost:5173`, Swagger UI at `http://localhost:8080/swagger-ui.html`, and the OpenAPI JSON at `http://localhost:8080/v3/api-docs`.
5. Select seeded catalog values and a future appointment time; check availability and create a booking. Show the returned appointment and allocated resources.
6. Attempt an overlapping booking for the same vehicle, including a different service. Show rejection; contrast with an adjacent interval that can succeed if resources are available.
7. Enable the duplicate-request simulation. In browser network tools show two POSTs sharing one key, the in-flight `202`, polling, and one resulting appointment.
8. Use the MySQL concurrency tests to demonstrate locked-candidate skipping and immediate vehicle conflicts. A manual UI run alone does not prove race safety.
9. Show request IDs in responses/logs and inspect metrics after exercising the relevant operations:

   ```sh
   curl -s http://localhost:8080/actuator/metrics/booking.lock.wait
   curl -s http://localhost:8080/actuator/metrics/booking.resource.search
   curl -s http://localhost:8080/actuator/metrics/booking.resource.overlap.checks
   curl -s http://localhost:8080/actuator/metrics/booking.availability
   curl -s http://localhost:8080/actuator/prometheus | rg 'booking_(lock_wait|resource_search|resource_overlap_checks|availability)'
   ```

For a single host-run backend, start MySQL from `code/`, then run:

```sh
cd backend
SCHEDULER_BOOKING_REDIS_LOCKS_ENABLED=false mvn spring-boot:run
```

Run `npm install` and `npm run dev` in `code/frontend` in another terminal. This local lock configuration does not coordinate idempotency across multiple backend instances.

For tests, provision a dedicated MySQL `scheduler_test` database and the required grants as described in `code/README.md`, then run from `code/backend`:

```sh
mvn clean test
# Include the opt-in performance suite:
mvn clean test -DrunPerformanceTests=true
```

The database suites delete/reseed their tables. Reports are written under `target/surefire-reports`. Performance tests need permission to bind an HTTP socket; earlier restricted-environment failures were not all source defects.

## 14. Remaining scope and lessons

The root `todo.md` still leaves coverage measurement, the walkthrough-video checklist, ShedLock/OpenFeign exploration, and a production circuit breaker unchecked. This file supplies a walkthrough narrative; it does not mark those tasks complete or implement those technologies.

Authentication/authorization, customer ownership verification, a timezone policy, cancellation/rescheduling workflows, production deployment, full distributed tracing, and live Redis outage/failover validation remain outside the demonstrated starter. Status enum values and trace IDs should not be mistaken for those complete features.

The conversations show a consistent engineering pattern: design first, compare generated code against explicit choices, expand tests around missing business rules, examine failures against the actual database, then review performance and operations. User corrections were material: they restored the vehicle invariant, rejected production changes made solely for H2, specified in-flight polling, simplified the catalog UI, and clarified the original project folder as the destination for worktree changes.

## Conversation source index

All 21 source chats were read through their available history. Forks include inherited earlier turns, so the narrative orders actual turn timestamps and removes duplicate turn IDs. The table orders chat creation times; titles are preserved as returned by the app, including truncated titles.

| Chat created (UTC+7) | Chat title | Chat ID |
| --- | --- | --- |
| 2026-10-28 17:11 | Brief the four scenarios | `01a181fd-31d6-7de3-807e-5b8a43b4e949` |
| 2026-10-29 09:42 | testing | `01a18589-f7bb-7671-973c-195d982e2cae` |
| 2026-10-29 10:09 | Fix validateInputs query ambiguity (2) | `01a185a2-9399-7670-a5ff-af4ac70f3f3f` |
| 2026-10-29 10:33 | Create a project TODO file | `01a185b7-eab8-7cf1-b8ca-491900ed96da` |
| 2026-10-29 10:38 | adjust code based on selected technologies | `01a185ad-6670-78d1-be2f-e71fd5100c57` |
| 2026-10-29 14:22 | flyway | `01a1868a-4b7a-7353-a753-6c58b1b4273d` |
| 2026-10-29 15:16 | Check scenario A technologies | `01a186ba-feea-7d60-817c-ce9e4a8bdad4` |
| 2026-10-29 15:32 | Add microservice booking logs | `01a186c9-e8b9-7661-97bf-b26621ac0c41` |
| 2026-10-29 16:30 | microservice-distributed lock | `01a186f3-faf0-7cc0-bb5a-38d4f98ef979` |
| 2026-10-29 16:44 | distributed-logging | `01a1870b-fec3-7e80-9b26-c2ca7b8f0409` |
| 2026-10-29 17:11 | implement micrometer to expose metrics | `01a18724-e41f-7cd1-aefd-334a9d65ba71` |
| 2026-10-29 17:59 | Diagnose exited Compose service | `01a18750-56a2-7111-bf62-b77a0dfd441b` |
| 2026-10-30 09:56 | Idempotency implementation | `01a18abc-549c-7f80-a4e5-e3ca48a05f1d` |
| 2026-10-30 11:48 | Review backend code quality | `01a18b23-1a84-7e71-b226-1bda687384db` |
| 2026-10-30 11:57 | Code quality-fix high severity-operations serialize per dea… | `01a18b2b-4a51-7681-8d07-0244bd6440d9` |
| 2026-10-30 12:53 | Clarify VIN meaning in scenario A | `01a18b5e-6142-7763-997c-f47718fcd921` |
| 2026-10-30 17:19 | Code quality - Resource selection can issue many queries pe… | `01a18c52-1aff-7262-bd4d-17fd90076474` |
| 2026-10-30 18:49 | Code quality-Reference data reads are unbounded | `01a18ca4-a9c1-7ec2-81a0-6cc2d507ee49` |
| 2026-10-30 19:18 | Code quality - bound booking lock waits and return retryabl… | `01a18cbf-314a-76a3-90c5-d8c5d46fd184` |
| 2026-10-30 19:45 | Code quality - automatically renew Redis idempotency locks | `01a18cd8-369d-79a2-b3d0-ade7667124c5` |
| 2026-10-30 21:23 | Code quality -add booking observability metrics | `01a18d31-f776-7ee3-a207-f0d6ebd529b4` |

