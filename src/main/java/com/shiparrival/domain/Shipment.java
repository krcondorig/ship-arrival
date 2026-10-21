package com.shiparrival.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "shipments", indexes = {
    @Index(name = "idx_shipment_tracking", columnList = "trackingNumber"),
    @Index(name = "idx_shipment_active", columnList = "active")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Shipment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 64)
    private String trackingNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private Carrier carrier;

    @Column(length = 64)
    private String containerNumber;

    @Column(length = 128)
    private String vesselName;

    @Column(length = 64)
    private String voyageNumber;

    @Column(length = 128)
    private String portOfLoading;

    @Column(length = 128)
    private String portOfDischarge;

    private LocalDateTime departureDate;

    private LocalDateTime estimatedArrival;

    private LocalDateTime actualArrival;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    @Builder.Default
    private ShipmentStatus status = ShipmentStatus.PENDING_FIRST_CHECK;

    private LocalDateTime lastCheckedAt;

    @Column(nullable = false)
    @Builder.Default
    private boolean active = true;

    @Column(nullable = false)
    @Builder.Default
    private boolean alertSent3Days = false;

    @Column(nullable = false)
    @Builder.Default
    private boolean alertSentArrived = false;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (updatedAt == null) {
            updatedAt = LocalDateTime.now();
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
