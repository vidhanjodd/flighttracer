package com.flighttracer.service;

import com.flighttracer.dto.FlightStatusDto;
import com.flighttracer.exception.FlightNotFoundException;
import com.flighttracer.model.StateVector;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
@Slf4j
public class FlightTrackingService {

    private final OpenSkyService openSkyService;
    private final FlightEnrichmentService enrichmentService;
    private final CallsignResolver callsignResolver;

    private final Map<String, FlightStatusDto> trackedFlights = new ConcurrentHashMap<>();

    private final Map<String, String[]> flightRoutes = new ConcurrentHashMap<>();

    public FlightStatusDto trackFlight(String flightNumber) {
        String key = normalize(flightNumber);

        if (trackedFlights.containsKey(key)) {
            return trackedFlights.get(key);
        }

        Optional<StateVector> sv = openSkyService.findByCallsign(key);
        if (sv.isEmpty()) {
            throw new FlightNotFoundException(
                    "Flight '" + flightNumber.toUpperCase() + "' not found in live feed. " +
                            "Possible reasons: (1) flight is on the ground / not yet departed, " +
                            "(2) outside OpenSky sensor coverage, " +
                            "(3) try the ICAO callsign variant (e.g. 'DLH400' instead of 'LH400', 'AIC101' instead of 'AI101'). " +
                            "Check opensky-network.org/network/explorer for live callsigns."
            );
        }

        String[] route = flightRoutes.getOrDefault(key, new String[]{null, null});
        FlightStatusDto dto = enrichmentService.enrich(sv.get(), route[0], route[1]);
        dto.setFlightIata(flightNumber.toUpperCase());

        trackedFlights.put(key, dto);
        log.info("✓ Tracking: {} → icao24={}, phase={}, alt={}ft, speed={}kts",
                flightNumber, sv.get().getIcao24(),
                dto.getFlightPhase(), dto.getBaroAltitudeFt(), dto.getSpeedKnots());
        return dto;
    }

    public FlightStatusDto getStatus(String flightNumber) {
        String key = normalize(flightNumber);
        FlightStatusDto dto = trackedFlights.get(key);
        if (dto == null) {
            return trackFlight(flightNumber);
        }
        return dto;
    }

    public void registerRoute(String callsign, String depIata, String arrIata) {
        flightRoutes.put(normalize(callsign), new String[]{depIata, arrIata});
    }


    public void stopTracking(String flightNumber) {
        String key = normalize(flightNumber);
        trackedFlights.remove(key);
        flightRoutes.remove(key);
        log.info("Stopped tracking flight: {}", flightNumber);
    }


    public Collection<FlightStatusDto> getAllTracked() {
        return trackedFlights.values();
    }


    public void refreshAll() {
        if (trackedFlights.isEmpty()) return;

        log.debug("Refreshing {} tracked flights", trackedFlights.size());
        trackedFlights.forEach((callsign, current) -> {
            try {
                String icao24 = current.getIcao24();
                Optional<StateVector> sv = icao24 != null
                        ? openSkyService.getStateByIcao24(icao24)
                        : openSkyService.findByCallsign(callsign);

                sv.ifPresent(state -> {
                    String[] route = flightRoutes.getOrDefault(callsign, new String[]{null, null});
                    FlightStatusDto updated = enrichmentService.enrich(state, route[0], route[1]);
                    updated.setFlightIata(current.getFlightIata());
                    trackedFlights.put(callsign, updated);
                });
            } catch (Exception e) {
                log.warn("Failed to refresh flight {}: {}", callsign, e.getMessage());
            }
        });
    }


    private String normalize(String callsign) {
        return callsign.toUpperCase().replaceAll("\\s+", "");
    }
}