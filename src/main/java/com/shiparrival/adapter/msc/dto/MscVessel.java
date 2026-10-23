package com.shiparrival.adapter.msc.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class MscVessel {

    @JsonProperty("IMO")
    private String imo;

    @JsonProperty("Built")
    private String built;

    @JsonProperty("Flag")
    private String flag;

    @JsonProperty("FlagName")
    private String flagName;
}
