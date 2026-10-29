package com.keyloop.scheduler.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "technician")
public class Technician {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String name;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "dealership_id", nullable = false)
    private Dealership dealership;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "service_type_id", nullable = false)
    private ServiceType serviceType;

    protected Technician() {}
    public Long getId() { return id; }
    public String getName() { return name; }
    public Dealership getDealership() { return dealership; }
    public ServiceType getServiceType() { return serviceType; }
}
