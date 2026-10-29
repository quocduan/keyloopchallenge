CREATE TABLE dealership (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    name VARCHAR(160) NOT NULL
) ENGINE=InnoDB;

CREATE TABLE vehicle (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    vin VARCHAR(17) NOT NULL UNIQUE,
    make VARCHAR(80) NOT NULL,
    model VARCHAR(100) NOT NULL,
    model_year INT NOT NULL
) ENGINE=InnoDB;

CREATE TABLE service_type (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    name VARCHAR(120) NOT NULL,
    duration_minutes INT NOT NULL,
    CONSTRAINT chk_service_duration CHECK (duration_minutes > 0)
) ENGINE=InnoDB;

CREATE TABLE technician (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    dealership_id BIGINT NOT NULL,
    name VARCHAR(160) NOT NULL,
    service_type_id BIGINT NOT NULL,
    CONSTRAINT fk_technician_dealership FOREIGN KEY (dealership_id) REFERENCES dealership(id),
    CONSTRAINT fk_technician_service FOREIGN KEY (service_type_id) REFERENCES service_type(id),
    INDEX idx_technician_qualification (dealership_id, service_type_id)
) ENGINE=InnoDB;

CREATE TABLE service_bay (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    dealership_id BIGINT NOT NULL,
    name VARCHAR(80) NOT NULL,
    CONSTRAINT fk_bay_dealership FOREIGN KEY (dealership_id) REFERENCES dealership(id),
    INDEX idx_bay_dealership (dealership_id)
) ENGINE=InnoDB;

CREATE TABLE appointment (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    dealership_id BIGINT NOT NULL,
    vehicle_id BIGINT NOT NULL,
    service_type_id BIGINT NOT NULL,
    technician_id BIGINT NOT NULL,
    service_bay_id BIGINT NOT NULL,
    starts_at DATETIME(6) NOT NULL,
    ends_at DATETIME(6) NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'CONFIRMED',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_appointment_dealership FOREIGN KEY (dealership_id) REFERENCES dealership(id),
    CONSTRAINT fk_appointment_vehicle FOREIGN KEY (vehicle_id) REFERENCES vehicle(id),
    CONSTRAINT fk_appointment_service FOREIGN KEY (service_type_id) REFERENCES service_type(id),
    CONSTRAINT fk_appointment_technician FOREIGN KEY (technician_id) REFERENCES technician(id),
    CONSTRAINT fk_appointment_bay FOREIGN KEY (service_bay_id) REFERENCES service_bay(id),
    CONSTRAINT chk_appointment_interval CHECK (ends_at > starts_at),
    INDEX idx_appointment_technician_time (technician_id, starts_at, ends_at),
    INDEX idx_appointment_bay_time (service_bay_id, starts_at, ends_at)
) ENGINE=InnoDB;
