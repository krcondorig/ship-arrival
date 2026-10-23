package com.shiparrival.adapter.msc.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class MscGeneralTrackingInfo {

    @JsonProperty("ShippedFrom")
    private String shippedFrom;

    @JsonProperty("ShippedTo")
    private String shippedTo;

    @JsonProperty("PortOfLoad")
    private String portOfLoad;

    @JsonProperty("PortOfDischarge")
    private String portOfDischarge;

    @JsonProperty("FinalPodEtaDate")
    private String finalPodEtaDate;
}
