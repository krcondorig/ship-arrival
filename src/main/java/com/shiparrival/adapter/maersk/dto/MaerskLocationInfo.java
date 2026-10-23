package com.shiparrival.adapter.maersk.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class MaerskLocationInfo {

    private String city;

    private String country;

    @JsonProperty("country_code")
    private String countryCode;

    private String terminal;
}
