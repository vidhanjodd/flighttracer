package com.flighttracer.service;

import com.flighttracer.dto.FlightStatusDto;
import com.flighttracer.exception.FlightNotFoundException;
import com.flighttracer.model.StateVector;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
@Slf4j
public class FlightTrackingService {

    private final OpenSkyService openSkyService;
    private final FlightEnrichmentService enrichmentService;
    private final AviationstackService aviationstackService;
    private final CallsignResolver callsignResolver;

    private final Map<String, FlightStatusDto> trackedFlights = new ConcurrentHashMap<>();

    private final Map<String, String[]> flightRoutes = new ConcurrentHashMap<>();

    public FlightStatusDto trackFlight(String flightNumber) {
        String key = normalize(flightNumber);

        if (trackedFlights.containsKey(key)) {
            return trackedFlights.get(key);
        }

        Optional<StateVector> sv = findLiveFlight(key);
        if (sv.isEmpty()) {
            throw new FlightNotFoundException(
                    "Flight '" + flightNumber.toUpperCase() + "' not found in live feed. " +
                            "Possible reasons: (1) flight is on the ground / not yet departed, " +
                            "(2) outside OpenSky sensor coverage, " +
                            "(3) try the ICAO callsign variant (e.g. 'DLH400' instead of 'LH400', 'AIC101' instead of 'AI101'). " +
                            "Check opensky-network.org/network/explorer for live callsigns."
            );
        }

        Optional<AviationstackService.FlightInfo> flightInfo =
                resolveFlightInfo(flightNumber, sv.get().getCallsign());

        String[] route = resolveRoute(key, flightInfo);
        FlightStatusDto dto = enrichmentService.enrich(sv.get(), route[0], route[1]);
        dto.setFlightIata(normalizeFlightIata(flightNumber, flightInfo));
        applyAviationstackInfo(dto, flightInfo);

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
                    Optional<AviationstackService.FlightInfo> flightInfo =
                            resolveFlightInfo(current.getFlightIata(), state.getCallsign());

                    String[] route = resolveRoute(callsign, flightInfo);
                    FlightStatusDto updated = enrichmentService.enrich(state, route[0], route[1]);
                    updated.setFlightIata(normalizeFlightIata(current.getFlightIata(), flightInfo));
                    applyAviationstackInfo(updated, flightInfo);
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

    private Optional<StateVector> findLiveFlight(String flightNumber) {
        for (String variant : callsignResolver.resolveVariants(flightNumber)) {
            Optional<StateVector> sv = openSkyService.findByCallsign(variant);
            if (sv.isPresent()) {
                return sv;
            }
        }
        return Optional.empty();
    }

    private Optional<AviationstackService.FlightInfo> resolveFlightInfo(String flightNumber, String liveCallsign) {
        Set<String> variants = new LinkedHashSet<>();
        addAviationstackVariants(variants, normalize(flightNumber));
        if (liveCallsign != null && !liveCallsign.isBlank()) {
            addAviationstackVariants(variants, normalize(liveCallsign));
        }
        callsignResolver.resolveVariants(flightNumber).forEach(variant -> addAviationstackVariants(variants, variant));
        if (liveCallsign != null && !liveCallsign.isBlank()) {
            callsignResolver.resolveVariants(liveCallsign).forEach(variant -> addAviationstackVariants(variants, variant));
        }

        for (String variant : variants) {
            Optional<AviationstackService.FlightInfo> match = lookupAviationstackVariant(variant);
            if (match.isPresent()) {
                return match;
            }
        }
        return Optional.empty();
    }

    private Optional<AviationstackService.FlightInfo> lookupAviationstackVariant(String variant) {
        if (variant == null || variant.isBlank()) {
            return Optional.empty();
        }
        if (variant.matches("^[A-Z0-9]{2}\\d+$")) {
            return aviationstackService.getFlightInfo(variant);
        }
        if (variant.matches("^[A-Z]{3}\\d+$")) {
            return aviationstackService.getFlightInfoByIcao(variant);
        }
        return Optional.empty();
    }

    private void addAviationstackVariants(Set<String> variants, String rawVariant) {
        String variant = normalize(rawVariant);
        if (variant.isBlank()) {
            return;
        }

        variants.add(variant);

        if (variant.matches("^[A-Z0-9]{2}0+\\d+$")) {
            variants.add(stripLeadingZerosFromSuffix(variant, 2));
        }
        if (variant.matches("^[A-Z]{3}0+\\d+$")) {
            variants.add(stripLeadingZerosFromSuffix(variant, 3));
        }
    }

    private String stripLeadingZerosFromSuffix(String variant, int prefixLength) {
        String prefix = variant.substring(0, prefixLength);
        String numeric = variant.substring(prefixLength).replaceFirst("^0+(\\d+)$", "$1");
        return prefix + numeric;
    }

    private String[] resolveRoute(String key, Optional<AviationstackService.FlightInfo> flightInfo) {
        String[] route = flightRoutes.getOrDefault(key, new String[]{null, null});
        if (flightInfo.isPresent()) {
            String depIata = flightInfo.get().depIata();
            String arrIata = flightInfo.get().arrIata();
            if (depIata != null || arrIata != null) {
                route = new String[]{depIata, arrIata};
                flightRoutes.put(key, route);
            }
        }
        return route;
    }

    private void applyAviationstackInfo(FlightStatusDto dto, Optional<AviationstackService.FlightInfo> flightInfo) {
        if (flightInfo.isEmpty()) {
            return;
        }

        AviationstackService.FlightInfo info = flightInfo.get();
        if (info.airline() != null) {
            dto.setAirline(info.airline());
        }
        if (info.depScheduled() != null) {
            dto.setScheduledDeparture(info.depScheduled());
        }
        if (info.arrScheduled() != null) {
            dto.setScheduledArrival(info.arrScheduled());
        }
        if (info.arrEstimated() != null) {
            dto.setEstimatedArrival(info.arrEstimated());
        }
        if (info.delayMinutes() != null) {
            dto.setDelayMinutes(info.delayMinutes());
        }
        if (info.status() != null) {
            dto.setStatus(info.status());
        }
        if (info.flightIata() != null && !info.flightIata().isBlank()) {
            dto.setFlightIata(info.flightIata().toUpperCase());
        }
    }

    private String normalizeFlightIata(String fallbackFlightNumber, Optional<AviationstackService.FlightInfo> flightInfo) {
        if (flightInfo.isPresent() && flightInfo.get().flightIata() != null && !flightInfo.get().flightIata().isBlank()) {
            return flightInfo.get().flightIata().toUpperCase();
        }
        return fallbackFlightNumber != null ? fallbackFlightNumber.toUpperCase() : null;
    }
}
