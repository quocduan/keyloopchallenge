CREATE TABLE dealership (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    name VARCHAR(160) NOT NULL
);

CREATE TABLE vehicle (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    vin VARCHAR(17) NOT NULL UNIQUE,
    make VARCHAR(80) NOT NULL,
    model VARCHAR(100) NOT NULL,
    model_year INT NOT NULL
);

CREATE TABLE service_type (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    name VARCHAR(120) NOT NULL,
    duration_minutes INT NOT NULL CHECK (duration_minutes > 0)
);

CREATE TABLE technician (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    dealership_id BIGINT NOT NULL REFERENCES dealership(id),
    name VARCHAR(160) NOT NULL,
    service_type_id BIGINT NOT NULL REFERENCES service_type(id)
);

CREATE TABLE service_bay (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    dealership_id BIGINT NOT NULL REFERENCES dealership(id),
    name VARCHAR(80) NOT NULL
);

CREATE TABLE appointment (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    dealership_id BIGINT NOT NULL REFERENCES dealership(id),
    vehicle_id BIGINT NOT NULL REFERENCES vehicle(id),
    service_type_id BIGINT NOT NULL REFERENCES service_type(id),
    technician_id BIGINT NOT NULL REFERENCES technician(id),
    service_bay_id BIGINT NOT NULL REFERENCES service_bay(id),
    starts_at TIMESTAMP NOT NULL,
    ends_at TIMESTAMP NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'CONFIRMED',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK (ends_at > starts_at)
);

CREATE INDEX idx_appointment_vehicle_time ON appointment(vehicle_id, starts_at, ends_at);
