package com.shiparrival.adapter.msc.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class MscContainerInfo {

    @JsonProperty("ContainerNumber")
    private String containerNumber;

    @JsonProperty("PodEtaDate")
    private String podEtaDate;

    @JsonProperty("LatestMove")
    private String latestMove;

    @JsonProperty("Delivered")
    private boolean delivered;

    @JsonProperty("Events")
    private List<MscEvent> events;
}
