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

/**
 * Central service for managing active flight tracking sessions.
 *
 * Maintains an in-memory registry of flights being tracked.
 * The scheduler calls refreshAll() every 15s to keep them fresh.
 * Phase 2 will add WebSocket broadcast here.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class FlightTrackingService {

    private final OpenSkyService openSkyService;
    private final FlightEnrichmentService enrichmentService;
    private final CallsignResolver callsignResolver;

    /**
     * In-memory store of currently tracked flights.
     * Key: callsign (normalized, uppercase, no spaces)
     * Value: latest FlightStatusDto
     */
    private final Map<String, FlightStatusDto> trackedFlights = new ConcurrentHashMap<>();

    /**
     * Map of callsign → depIata/arrIata (populated when known from Aviationstack).
     * Stored separately so we can re-enrich on each update without losing route info.
     */
    private final Map<String, String[]> flightRoutes = new ConcurrentHashMap<>();

    // ── Public API ───────────────────────────────────────────────────────────

    /**
     * Look up a flight by IATA flight number (e.g. "LH400", "AI101").
     * First tries to find it in currently tracked flights, then queries OpenSky.
     */
    public FlightStatusDto trackFlight(String flightNumber) {
        String key = normalize(flightNumber);

        // Check cache first
        if (trackedFlights.containsKey(key)) {
            return trackedFlights.get(key);
        }

        // Search OpenSky — tries IATA + ICAO variants automatically
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
        // Keep the user's original input (e.g. "LH400") as flightIata
        // even if OpenSky found it as "DLH400"
        dto.setFlightIata(flightNumber.toUpperCase());

        trackedFlights.put(key, dto);
        log.info("✓ Tracking: {} → icao24={}, phase={}, alt={}ft, speed={}kts",
                flightNumber, sv.get().getIcao24(),
                dto.getFlightPhase(), dto.getBaroAltitudeFt(), dto.getSpeedKnots());
        return dto;
    }

    /**
     * Get latest status for a currently tracked flight.
     */
    public FlightStatusDto getStatus(String flightNumber) {
        String key = normalize(flightNumber);
        FlightStatusDto dto = trackedFlights.get(key);
        if (dto == null) {
            // Try to track it on-demand
            return trackFlight(flightNumber);
        }
        return dto;
    }

    /**
     * Register a route for a flight (called by Aviationstack enrichment in Phase 2).
     */
    public void registerRoute(String callsign, String depIata, String arrIata) {
        flightRoutes.put(normalize(callsign), new String[]{depIata, arrIata});
    }

    /**
     * Stop tracking a flight.
     */
    public void stopTracking(String flightNumber) {
        String key = normalize(flightNumber);
        trackedFlights.remove(key);
        flightRoutes.remove(key);
        log.info("Stopped tracking flight: {}", flightNumber);
    }

    /**
     * Get all currently tracked flights.
     */
    public Collection<FlightStatusDto> getAllTracked() {
        return trackedFlights.values();
    }

    /**
     * Called by the scheduler — refreshes all tracked flights from OpenSky.
     */
    public void refreshAll() {
        if (trackedFlights.isEmpty()) return;

        log.debug("Refreshing {} tracked flights", trackedFlights.size());
        trackedFlights.forEach((callsign, current) -> {
            try {
                // Use icao24 for targeted refresh (more efficient than callsign search)
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

    // ── Helpers ──────────────────────────────────────────────────────────────

    private String normalize(String callsign) {
        return callsign.toUpperCase().replaceAll("\\s+", "");
    }
}