package com.shiparrival.adapter.one;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.List;

/**
 * Respuesta real del endpoint v2 de ONE:
 * POST https://ecomm.one-line.com/api/v2/edh/containers/track-and-trace/search
 *
 * La respuesta trae un arreglo de contenedores (no un objeto único).
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class OneTrackingResponseDto {

    private Integer status;
    private Integer code;
    private String message;
    private Integer total;
    private List<OneContainerData> data;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class OneContainerData {
        private String bookingNo;
        private String bookingNoShow;
        private String containerNo;
        private String containerTypeSize;
        private String weight;
        private OneEvent latestEvent;
        private OnePlace place;
        private OneLocation por;
        private OneLocation pod;
        private List<OneCargoEvent> cargoEvents;
        private OneVesselVoyage vesselVoyage;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class OneEvent {
        private String eventName;
        private String locationName;
        private String date;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class OnePlace {
        private String yardCode;
        private String yardName;
        private String locationName;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class OneLocation {
        private String code;
        private String locationName;
        private String countryName;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class OneCargoEvent {
        private String matrixId;
        private String locationName;
        private String countryCode;
        private String countryName;
        private String date;
        private String localPortDate;
        private String trigger;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class OneVesselVoyage {
        private String vesselCode;
        private String vesselName;
        private String voyageNo;
        private String directionCode;
        private String inboundConsortiumVoyage;
        private String outboundConsortiumVoyage;
    }
}
