package com.shiparrival.adapter.maersk.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

/**
 * Respuesta de la API interna de rastreo de Maersk
 * ({@code /synergy/tracking/{TRACK_ID}?operator=MAEU}).
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class MaerskTrackingResponse {

    @JsonProperty("tpdoc_num")
    private String tpdocNum;

    @JsonProperty("is_cancelled_shipment")
    private boolean cancelledShipment;

    private MaerskLocationInfo origin;

    private MaerskLocationInfo destination;

    private List<MaerskContainer> containers;
}
