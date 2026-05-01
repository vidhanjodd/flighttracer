package com.flighttracer.service;

import com.flighttracer.dto.FlightStatusDto;
import com.flighttracer.model.Airport;
import com.flighttracer.model.StateVector;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class FlightEnrichmentService {

    private final AirportService airportService;
    private final CallsignResolver callsignResolver;

    public FlightStatusDto enrich(StateVector sv, String depIata, String arrIata) {
        String callsign = sv.getCallsign() != null ? sv.getCallsign().trim() : null;

        FlightStatusDto.FlightStatusDtoBuilder builder = FlightStatusDto.builder()
                .icao24(sv.getIcao24())
                .callsign(callsign)
                .originCountry(sv.getOriginCountry())
                .latitude(roundCoord(sv.getLatitude()))
                .longitude(roundCoord(sv.getLongitude()))
                .onGround(sv.getOnGround())
                .heading(sv.getTrueTrack() != null ? round(sv.getTrueTrack(), 1) : null)
                .lastUpdated(Instant.now().toEpochMilli());

        // Unit conversions — all rounded cleanly
        if (sv.getBaroAltitude() != null) {
            builder.baroAltitudeFt(round(metersToFeet(sv.getBaroAltitude()), 0));
        }
        if (sv.getVelocity() != null) {
            double kmh = sv.getVelocity() * 3.6;
            builder.speedKmh(round(kmh, 0));
            builder.speedKnots(round(kmh / 1.852, 0));
        }
        if (sv.getVerticalRate() != null) {
            builder.verticalRateFpm(round(sv.getVerticalRate() * 196.85, 0));
        }

        // Flight phase
        builder.flightPhase(detectPhase(sv));
        builder.status(deriveStatus(sv));

        // Departure airport
        Optional<Airport> dep = resolveAirport(depIata);
        dep.ifPresent(a -> builder
                .depIata(a.getIata()).depName(a.getName())
                .depCity(a.getCity()).depLat(a.getLat()).depLng(a.getLng()));

        // Arrival airport
        Optional<Airport> arr = resolveAirport(arrIata);
        arr.ifPresent(a -> builder
                .arrIata(a.getIata()).arrName(a.getName())
                .arrCity(a.getCity()).arrLat(a.getLat()).arrLng(a.getLng()));

        // Progress — only when both airports known
        if (dep.isPresent() && arr.isPresent()
                && sv.getLatitude() != null && sv.getLongitude() != null) {
            Airport d = dep.get();
            Airport a = arr.get();
            double totalKm  = airportService.distanceKm(d.getLat(), d.getLng(), a.getLat(), a.getLng());
            double flownKm  = airportService.distanceKm(d.getLat(), d.getLng(), sv.getLatitude(), sv.getLongitude());
            double remainKm = Math.max(0, totalKm - flownKm);
            double progress = Math.min(100.0, (flownKm / totalKm) * 100.0);
            builder.distanceTotalKm(round(totalKm, 0))
                    .distanceFlownKm(round(flownKm, 0))
                    .distanceRemainingKm(round(remainKm, 0))
                    .progressPercent(round(progress, 1));
        }

        return builder.build();
    }

    // ── Flight phase ──────────────────────────────────────────────────────────

    private String detectPhase(StateVector sv) {
        if (Boolean.TRUE.equals(sv.getOnGround())) return "ON_GROUND";
        if (sv.getBaroAltitude() == null) return "UNKNOWN";

        double altFt  = metersToFeet(sv.getBaroAltitude());
        Double vRate  = sv.getVerticalRate();  // m/s

        if (altFt < 500)   return vRate != null && vRate > 0 ? "TAKING_OFF" : "LANDING";
        if (altFt < 10000) return vRate != null && vRate > 2 ? "CLIMBING"
                : vRate != null && vRate < -2 ? "DESCENDING" : "LOW_ALTITUDE";
        if (vRate != null && vRate > 2)  return "CLIMBING";
        if (vRate != null && vRate < -2) return "DESCENDING";
        return "CRUISING";
    }

    private String deriveStatus(StateVector sv) {
        if (Boolean.TRUE.equals(sv.getOnGround())) return "On Ground";
        String phase = detectPhase(sv);
        return switch (phase) {
            case "TAKING_OFF"   -> "Taking Off";
            case "CLIMBING"     -> "Climbing";
            case "CRUISING"     -> "En Route";
            case "DESCENDING"   -> "Descending";
            case "LANDING"      -> "Landing";
            case "LOW_ALTITUDE" -> "Low Altitude";
            default             -> "Airborne";
        };
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private double metersToFeet(double meters) { return meters * 3.28084; }

    private double round(double value, int decimals) {
        double scale = Math.pow(10, decimals);
        return Math.round(value * scale) / scale;
    }

    /** Round coordinates to 4 decimal places (~11m precision — enough for flight tracking) */
    private Double roundCoord(Double value) {
        return value != null ? round(value, 4) : null;
    }

    private Optional<Airport> resolveAirport(String code) {
        if (code == null || code.isBlank()) return Optional.empty();
        return airportService.findByIataOrIcao(code);
    }
}