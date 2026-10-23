package com.shiparrival.adapter.msc.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class MscBillOfLading {

    @JsonProperty("BillOfLadingNumber")
    private String billOfLadingNumber;

    @JsonProperty("GeneralTrackingInfo")
    private MscGeneralTrackingInfo generalTrackingInfo;

    @JsonProperty("ContainersInfo")
    private List<MscContainerInfo> containersInfo;
}
