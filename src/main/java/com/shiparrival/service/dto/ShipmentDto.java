package com.shiparrival.service.dto;

import com.shiparrival.domain.Carrier;
import com.shiparrival.domain.ShipmentStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ShipmentDto {

    private Long id;
    private String trackingNumber;
    private Carrier carrier;
    private String containerNumber;
    private String vesselName;
    private String voyageNumber;
    private String portOfLoading;
    private String portOfDischarge;
    private LocalDateTime departureDate;
    private LocalDateTime estimatedArrival;
    private LocalDateTime actualArrival;
    private ShipmentStatus status;
    private LocalDateTime lastCheckedAt;
    private boolean active;
    private Long daysRemaining;
}
