package com.flighttracer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flighttracer.service.AirportService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AirportServiceTest {

    private AirportService airportService;

    @BeforeEach
    void setUp() {
        airportService = new AirportService(new ObjectMapper());
        airportService.loadAirports();
    }

    @Test
    void shouldFindAirportByIata() {
        var result = airportService.findByIata("DEL");
        assertThat(result).isPresent();
        assertThat(result.get().getCity()).isEqualTo("New Delhi");
    }

    @Test
    void shouldFindAirportByIcao() {
        var result = airportService.findByIcao("EGLL");
        assertThat(result).isPresent();
        assertThat(result.get().getIata()).isEqualTo("LHR");
    }

    @Test
    void shouldReturnEmptyForUnknownCode() {
        assertThat(airportService.findByIata("ZZZ")).isEmpty();
    }

    @Test
    void distanceLHRtoJFK_shouldBeApprox5500km() {
        // LHR: 51.4706, -0.4619 | JFK: 40.6398, -73.7789
        double dist = airportService.distanceKm(51.4706, -0.4619, 40.6398, -73.7789);
        assertThat(dist).isBetween(5400.0, 5600.0);
    }

    @Test
    void progressShouldBe50PercentAtMidpoint() {
        // Midpoint of LHR→JFK (roughly over the Atlantic)
        double midLat = (51.4706 + 40.6398) / 2;
        double midLng = (-0.4619 + -73.7789) / 2;
        double progress = airportService.calculateProgress(
                51.4706, -0.4619,
                midLat, midLng,
                40.6398, -73.7789);
        assertThat(progress).isBetween(45.0, 55.0);
    }
}
