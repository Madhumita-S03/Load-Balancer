package com.madhumita.dispatch.domain;

import jakarta.persistence.*;

/** Persistent fleet data. vehicleId is the stable business key used for upsert operations. */
@Entity
@Table(name = "vehicles",
        uniqueConstraints = @UniqueConstraint(name = "uk_vehicle_business_id", columnNames = "vehicle_id"))
public class Vehicle {
    @Id
    @SequenceGenerator(name = "vehicle_seq_gen", sequenceName = "vehicle_seq", allocationSize = 50)
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "vehicle_seq_gen")
    private Long id;

    @Version // Protects updates from lost-write races.
    private Long version;

    @Column(name = "vehicle_id", nullable = false, length = 100) private String vehicleId;
    @Column(nullable = false) private double capacity;
    @Column(name = "current_latitude", nullable = false) private double currentLatitude;
    @Column(name = "current_longitude", nullable = false) private double currentLongitude;
    @Column(name = "current_address", nullable = false, length = 500) private String currentAddress;

    protected Vehicle() { }

    public Vehicle(String vehicleId, double capacity, double currentLatitude, double currentLongitude, String currentAddress) {
        this.vehicleId = vehicleId;
        this.capacity = capacity;
        this.currentLatitude = currentLatitude;
        this.currentLongitude = currentLongitude;
        this.currentAddress = currentAddress;
    }

    public void updateFrom(Vehicle source) {
        this.capacity = source.capacity;
        this.currentLatitude = source.currentLatitude;
        this.currentLongitude = source.currentLongitude;
        this.currentAddress = source.currentAddress;
    }

    public Long getId() { return id; }
    public String getVehicleId() { return vehicleId; }
    public double getCapacity() { return capacity; }
    public double getCurrentLatitude() { return currentLatitude; }
    public double getCurrentLongitude() { return currentLongitude; }
    public String getCurrentAddress() { return currentAddress; }
}
