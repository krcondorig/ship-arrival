package com.shiparrival.adapter;

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
public class TrackingResult {

    private boolean success;
    private String trackingNumber;
    private String containerNumber;
    private String vesselName;
    private String voyageNumber;
    private String portOfLoading;
    private String portOfDischarge;
    private LocalDateTime departureDate;
    private LocalDateTime estimatedArrival;
    private LocalDateTime actualArrival;
    private ShipmentStatus detectedStatus;
    private String rawStatusMessage;
    private String errorMessage;

    public static TrackingResult failure(String trackingNumber, String errorMessage) {
        return TrackingResult.builder()
                .success(false)
                .trackingNumber(trackingNumber)
                .detectedStatus(ShipmentStatus.ERROR_CHECKING)
                .errorMessage(errorMessage)
                .build();
    }
}
