package com.shiparrival.adapter.msc.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class MscEvent {

    @JsonProperty("Order")
    private Integer order;

    @JsonProperty("Date")
    private String date;

    @JsonProperty("Location")
    private String location;

    @JsonProperty("UnLocationCode")
    private String unLocationCode;

    @JsonProperty("Description")
    private String description;

    @JsonProperty("Detail")
    private List<String> detail;

    @JsonProperty("Vessel")
    private MscVessel vessel;
}
