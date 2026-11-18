package com.madhumita.dispatch.domain;

import jakarta.persistence.*;

/** Persistent order data. The business orderId is the stable upsert key exposed by the API. */
@Entity
@Table(name = "delivery_orders",
        uniqueConstraints = @UniqueConstraint(name = "uk_delivery_order_business_id", columnNames = "order_id"),
        indexes = @Index(name = "idx_delivery_order_priority", columnList = "priority"))
public class DeliveryOrder {
    @Id
    @SequenceGenerator(name = "delivery_order_seq_gen", sequenceName = "delivery_order_seq", allocationSize = 50)
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "delivery_order_seq_gen")
    private Long id;

    @Version // Detects conflicting concurrent updates instead of silently overwriting another request.
    private Long version;

    @Column(name = "order_id", nullable = false, length = 100) private String orderId;
    @Column(nullable = false) private double latitude;
    @Column(nullable = false) private double longitude;
    @Column(nullable = false, length = 500) private String address;
    @Column(name = "package_weight", nullable = false) private double packageWeight;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 10) private Priority priority;

    protected DeliveryOrder() { }

    public DeliveryOrder(String orderId, double latitude, double longitude, String address,
                         double packageWeight, Priority priority) {
        this.orderId = orderId;
        this.latitude = latitude;
        this.longitude = longitude;
        this.address = address;
        this.packageWeight = packageWeight;
        this.priority = priority;
    }

    public void updateFrom(DeliveryOrder source) {
        this.latitude = source.latitude;
        this.longitude = source.longitude;
        this.address = source.address;
        this.packageWeight = source.packageWeight;
        this.priority = source.priority;
    }

    public Long getId() { return id; }
    public String getOrderId() { return orderId; }
    public double getLatitude() { return latitude; }
    public double getLongitude() { return longitude; }
    public String getAddress() { return address; }
    public double getPackageWeight() { return packageWeight; }
    public Priority getPriority() { return priority; }
}
