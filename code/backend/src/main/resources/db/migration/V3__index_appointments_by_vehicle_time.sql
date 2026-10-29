CREATE INDEX idx_appointment_vehicle_time
    ON appointment (vehicle_id, starts_at, ends_at);
