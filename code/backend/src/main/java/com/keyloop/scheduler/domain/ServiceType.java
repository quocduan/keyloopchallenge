package com.keyloop.scheduler.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "service_type")
public class ServiceType {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String name;
    @Column(name = "duration_minutes")
    private int durationMinutes;

    protected ServiceType() {}
    public Long getId() { return id; }
    public String getName() { return name; }
    public int getDurationMinutes() { return durationMinutes; }
}
