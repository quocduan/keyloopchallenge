package com.keyloop.scheduler.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "vehicle")
public class Vehicle {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String vin;
    private String make;
    private String model;
    @Column(name = "model_year")
    private int modelYear;

    protected Vehicle() {}
    public Long getId() { return id; }
    public String getVin() { return vin; }
    public String getMake() { return make; }
    public String getModel() { return model; }
    public int getModelYear() { return modelYear; }
    public String getLabel() { return modelYear + " " + make + " " + model + " (" + vin + ")"; }
}
