package com.flighttracer;

import com.flighttracer.dto.FlightStatusDto;
import com.flighttracer.model.StateVector;
import com.flighttracer.service.AviationstackService;
import com.flighttracer.service.CallsignResolver;
import com.flighttracer.service.FlightEnrichmentService;
import com.flighttracer.service.FlightTrackingService;
import com.flighttracer.service.OpenSkyService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FlightTrackingServiceTest {

    @Mock
    private OpenSkyService openSkyService;

    @Mock
    private FlightEnrichmentService enrichmentService;

    @Mock
    private AviationstackService aviationstackService;

    @Mock
    private CallsignResolver callsignResolver;

    @InjectMocks
    private FlightTrackingService flightTrackingService;

    @Test
    void shouldEnrichTrackedFlightWithAviationstackData() {
        StateVector state = new StateVector();
        state.setIcao24("abc123");
        state.setCallsign("AIC101");

        FlightStatusDto baseDto = FlightStatusDto.builder()
                .icao24("abc123")
                .callsign("AIC101")
                .status("Airborne")
                .build();

        AviationstackService.FlightInfo flightInfo = new AviationstackService.FlightInfo(
                "AI101", "AIC101", "Air India", "active",
                "DEL", "Indira Gandhi International Airport", "2026-05-02T02:30:00+05:30", null,
                "JFK", "John F Kennedy International Airport", "2026-05-02T14:00:00-04:00", "2026-05-02T13:45:00-04:00",
                15
        );

        when(callsignResolver.resolveVariants("AI101")).thenReturn(java.util.List.of("AI101", "AIC101"));
        when(openSkyService.findByCallsign("AI101")).thenReturn(Optional.empty());
        when(openSkyService.findByCallsign("AIC101")).thenReturn(Optional.of(state));
        when(enrichmentService.enrich(state, "DEL", "JFK")).thenReturn(baseDto);
        when(aviationstackService.getFlightInfo("AI101")).thenReturn(Optional.of(flightInfo));

        FlightStatusDto result = flightTrackingService.trackFlight("AI101");

        assertThat(result.getFlightIata()).isEqualTo("AI101");
        assertThat(result.getAirline()).isEqualTo("Air India");
        assertThat(result.getScheduledDeparture()).isEqualTo("2026-05-02T02:30:00+05:30");
        assertThat(result.getScheduledArrival()).isEqualTo("2026-05-02T14:00:00-04:00");
        assertThat(result.getEstimatedArrival()).isEqualTo("2026-05-02T13:45:00-04:00");
        assertThat(result.getDelayMinutes()).isEqualTo(15);
        assertThat(result.getStatus()).isEqualTo("active");
        verify(enrichmentService).enrich(state, "DEL", "JFK");
    }

    @Test
    void shouldTryNonPaddedAviationstackVariantForOpenSkyCallsign() {
        StateVector state = new StateVector();
        state.setIcao24("xyz789");
        state.setCallsign("IGO083");

        FlightStatusDto baseDto = FlightStatusDto.builder()
                .icao24("xyz789")
                .callsign("IGO083")
                .build();

        AviationstackService.FlightInfo flightInfo = new AviationstackService.FlightInfo(
                "6E83", "IGO83", "IndiGo", "landed",
                "DEL", "Indira Gandhi International Airport", "2026-05-02T03:00:00+05:30", null,
                "DMM", "King Fahd International Airport", "2026-05-02T09:30:00+03:00", "2026-05-02T08:56:00+03:00",
                null
        );

        when(callsignResolver.resolveVariants("IGO083")).thenReturn(java.util.List.of("IGO083", "6E083"));
        when(openSkyService.findByCallsign("IGO083")).thenReturn(Optional.of(state));
        when(enrichmentService.enrich(state, "DEL", "DMM")).thenReturn(baseDto);
        when(aviationstackService.getFlightInfoByIcao("IGO083")).thenReturn(Optional.empty());
        when(aviationstackService.getFlightInfoByIcao("IGO83")).thenReturn(Optional.of(flightInfo));

        FlightStatusDto result = flightTrackingService.trackFlight("IGO083");

        assertThat(result.getFlightIata()).isEqualTo("6E83");
        assertThat(result.getAirline()).isEqualTo("IndiGo");
        assertThat(result.getScheduledArrival()).isEqualTo("2026-05-02T09:30:00+03:00");
        assertThat(result.getEstimatedArrival()).isEqualTo("2026-05-02T08:56:00+03:00");
        assertThat(result.getStatus()).isEqualTo("landed");
        verify(enrichmentService).enrich(state, "DEL", "DMM");
    }
}
