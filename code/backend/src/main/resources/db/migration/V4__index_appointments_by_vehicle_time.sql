CREATE INDEX idx_appointment_vehicle_status_time
    ON appointment (vehicle_id, status, starts_at, ends_at);

DROP INDEX idx_appointment_vehicle_time ON appointment;
