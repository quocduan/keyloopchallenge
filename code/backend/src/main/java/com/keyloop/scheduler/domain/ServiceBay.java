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
@Table(name = "service_bay")
public class ServiceBay {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String name;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "dealership_id", nullable = false)
    private Dealership dealership;

    protected ServiceBay() {}
    public Long getId() { return id; }
    public String getName() { return name; }
    public Dealership getDealership() { return dealership; }
}
