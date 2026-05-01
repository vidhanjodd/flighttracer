package com.flighttracer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flighttracer.dto.FlightStatusDto;
import com.flighttracer.model.StateVector;
import com.flighttracer.service.AirportService;
import com.flighttracer.service.FlightEnrichmentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FlightEnrichmentServiceTest {

    private FlightEnrichmentService enrichmentService;

    @BeforeEach
    void setUp() {
        AirportService airportService = new AirportService(new ObjectMapper());
        airportService.loadAirports();
        enrichmentService = new FlightEnrichmentService(airportService);
    }

    private StateVector sampleStateVector() {
        // Roughly mid-Atlantic, LHR→JFK flight
        List<Object> arr = Arrays.asList(
            "a1b2c3",       // icao24
            "BA112   ",     // callsign (with spaces)
            "United Kingdom",
            1700000000L,    // time_position
            1700000000L,    // last_contact
            -37.5,          // longitude (mid Atlantic)
            52.0,           // latitude
            10058.4,        // baro_alt (meters, ~33000ft)
            false,          // on_ground
            245.0,          // velocity m/s (~469 knots)
            270.5,          // true_track
            0.2,            // vertical_rate
            null,           // sensors (unused)
            10150.0,        // geo_altitude
            "2000",         // squawk
            false,          // spi
            0               // position_source
        );
        return StateVector.fromArray(arr);
    }

    @Test
    void shouldConvertMetersToFeet() {
        StateVector sv = sampleStateVector();
        FlightStatusDto dto = enrichmentService.enrich(sv, null, null);
        // 10058.4m ≈ 33000ft
        assertThat(dto.getBaroAltitudeFt()).isBetween(32500.0, 33500.0);
    }

    @Test
    void shouldConvertVelocityToKnots() {
        StateVector sv = sampleStateVector();
        FlightStatusDto dto = enrichmentService.enrich(sv, null, null);
        // 245 m/s = 882 km/h ≈ 476 knots
        assertThat(dto.getSpeedKnots()).isBetween(460.0, 490.0);
    }

    @Test
    void shouldDetectCruisingPhase() {
        StateVector sv = sampleStateVector();
        FlightStatusDto dto = enrichmentService.enrich(sv, null, null);
        assertThat(dto.getFlightPhase()).isEqualTo("CRUISING");
    }

    @Test
    void shouldCalculateProgressWithKnownRoute() {
        StateVector sv = sampleStateVector();
        // DEL → LHR
        FlightStatusDto dto = enrichmentService.enrich(sv, "DEL", "LHR");
        assertThat(dto.getProgressPercent()).isNotNull();
        assertThat(dto.getDistanceTotalKm()).isGreaterThan(5000.0);
        assertThat(dto.getDepIata()).isEqualTo("DEL");
        assertThat(dto.getArrIata()).isEqualTo("LHR");
    }

    @Test
    void shouldTrimCallsign() {
        StateVector sv = sampleStateVector();
        assertThat(sv.getCallsign()).isEqualTo("BA112");
    }
}
