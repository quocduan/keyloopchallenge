package com.keyloop.scheduler.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

@Entity
@Table(name = "appointment")
public class Appointment {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "dealership_id", nullable = false)
    private Dealership dealership;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "vehicle_id", nullable = false)
    private Vehicle vehicle;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "service_type_id", nullable = false)
    private ServiceType serviceType;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "technician_id", nullable = false)
    private Technician technician;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "service_bay_id", nullable = false)
    private ServiceBay serviceBay;
    @Column(name = "starts_at", nullable = false)
    private LocalDateTime startsAt;
    @Column(name = "ends_at", nullable = false)
    private LocalDateTime endsAt;
    @Column(nullable = false)
    private String status = "CONFIRMED";
    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    protected Appointment() {}

    public Appointment(Dealership dealership, Vehicle vehicle, ServiceType serviceType,
                       Technician technician, ServiceBay serviceBay,
                       LocalDateTime startsAt, LocalDateTime endsAt) {
        this.dealership = dealership;
        this.vehicle = vehicle;
        this.serviceType = serviceType;
        this.technician = technician;
        this.serviceBay = serviceBay;
        this.startsAt = startsAt;
        this.endsAt = endsAt;
    }

    public Long getId() { return id; }
    public Dealership getDealership() { return dealership; }
    public Vehicle getVehicle() { return vehicle; }
    public ServiceType getServiceType() { return serviceType; }
    public Technician getTechnician() { return technician; }
    public ServiceBay getServiceBay() { return serviceBay; }
    public LocalDateTime getStartsAt() { return startsAt; }
    public LocalDateTime getEndsAt() { return endsAt; }
    public String getStatus() { return status; }
}
