package com.shiparrival.adapter.maersk.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class MaerskContainer {

    @JsonProperty("container_num")
    private String containerNum;

    @JsonProperty("container_size")
    private String containerSize;

    @JsonProperty("container_type")
    private String containerType;

    @JsonProperty("iso_code")
    private String isoCode;

    private List<MaerskContainerLocation> locations;

    @JsonProperty("eta_final_delivery")
    private String etaFinalDelivery;

    private String status;
}
