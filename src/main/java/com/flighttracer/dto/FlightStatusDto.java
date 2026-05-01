package com.flighttracer.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class FlightStatusDto {

    // ── Identity ───────────────────────────────────────────────────────────
    private String icao24;
    private String callsign;       // e.g. "LH400"
    private String flightIata;     // e.g. "LH400"
    private String airline;
    private String aircraftType;
    private String registration;
    private String originCountry;

    // ── Route ──────────────────────────────────────────────────────────────
    private String depIata;
    private String depName;
    private String depCity;
    private Double depLat;
    private Double depLng;

    private String arrIata;
    private String arrName;
    private String arrCity;
    private Double arrLat;
    private Double arrLng;

    // ── Schedule ───────────────────────────────────────────────────────────
    private String scheduledDeparture;   // ISO string
    private String scheduledArrival;     // ISO string
    private String estimatedArrival;
    private Integer delayMinutes;

    // ── Live telemetry ─────────────────────────────────────────────────────
    private Double latitude;
    private Double longitude;
    private Double baroAltitudeFt;       // converted to feet for display
    private Double speedKmh;             // converted from m/s
    private Double speedKnots;
    private Double verticalRateFpm;      // feet per minute
    private Double heading;              // degrees
    private Boolean onGround;

    // ── Derived ────────────────────────────────────────────────────────────
    private Double progressPercent;      // 0–100
    private Double distanceFlownKm;
    private Double distanceTotalKm;
    private Double distanceRemainingKm;
    private String flightPhase;          // SCHEDULED | BOARDING | CLIMBING | CRUISING | DESCENDING | LANDING | ARRIVED

    // ── Meta ───────────────────────────────────────────────────────────────
    private Long lastUpdated;            // epoch ms
    private String status;               // raw status from Aviationstack
}
