CREATE INDEX idx_appointment_technician_status_time
    ON appointment (technician_id, status, starts_at, ends_at);

CREATE INDEX idx_appointment_bay_status_time
    ON appointment (service_bay_id, status, starts_at, ends_at);

DROP INDEX idx_appointment_technician_time ON appointment;
DROP INDEX idx_appointment_bay_time ON appointment;
