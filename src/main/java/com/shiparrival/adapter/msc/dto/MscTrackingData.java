package com.shiparrival.adapter.msc.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class MscTrackingData {

    @JsonProperty("TrackingType")
    private String trackingType;

    @JsonProperty("TrackingNumber")
    private String trackingNumber;

    @JsonProperty("BillOfLadings")
    private List<MscBillOfLading> billOfLadings;
}
