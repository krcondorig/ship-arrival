package com.shiparrival.adapter.one;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.List;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class OneTrackingResponseDto {

    private OneShipmentData data;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class OneShipmentData {
        private String blNo;
        private String vesselName;
        private String voyage;
        private String pol;
        private String pod;
        private String departureDate;
        private String estimatedArrival;
        private String actualArrival;
        private String status;
        private List<OneContainerDto> containers;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class OneContainerDto {
        private String containerNo;
        private String status;
    }
}
