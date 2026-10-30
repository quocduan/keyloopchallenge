# TODO

- [x] Add indexes to improve query performance.
  - [x] Add status-aware composite indexes for technician and service-bay appointment overlap checks.
- [ ] Review whether entity graphs are needed alongside raw queries.
- [x] Implement locking in application code instead of relying on database-level locking.
- [x] Implement the full test list.
  - [ ] check test coverage
  - [x] switch test code to using jpa+ hibernate
  - [ ] change test db from H2 --> MySQL
- [x] user test --> add more human covered testcases
- [x] check whether Flyway is still working while using jpa+hibernate
- [ ] Check any missing technologies in the code from the current selected technologies table
  - [ ] using micrometer, logback/logstash to collect log for external logging system (refer to VD proj)
- [ ] Create script for walkthrough video
  - [ ] plan first: design first, choose technologies,  list testcase, scaffold code
  - [ ] verify code against the chosen technologies, assess/judge code and check what need to implement more ( index, lock, code style,...) and add them into todo.md file to about any missing
  
- [ ] judge entire BE code for scalability, performance, reliability, maintainability, and observability.
- [x] use enum for status/ or find other convenient structure
- [x] idempotency for appointment creation via the `Idempotency-Key` header
- [ ] concurrency ShedLock / OpenFeign
- [ ] implement circut breaker for production use
## challenge:
- some flow or generated code are not reasonable --> adjust
  - ex: process for idempotency key
- redis cluster advertise internal ip address ==> can not join the debug run from host. ==> has to run in docker
- manual test for idempotency/ concurrency
