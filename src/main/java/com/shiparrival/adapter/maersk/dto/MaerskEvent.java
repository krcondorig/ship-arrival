package com.shiparrival.adapter.maersk.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class MaerskEvent {

    private String activity;

    @JsonProperty("event_time")
    private String eventTime;

    @JsonProperty("event_time_type")
    private String eventTimeType;

    @JsonProperty("vessel_name")
    private String vesselName;

    @JsonProperty("voyage_num")
    private String voyageNum;

    @JsonProperty("transport_mode")
    private String transportMode;
}
