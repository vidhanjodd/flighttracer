package com.flighttracer.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class Airport {
    private String iata;
    private String icao;
    private String name;
    private String city;
    private String country;
    private Double lat;
    private Double lng;
}
