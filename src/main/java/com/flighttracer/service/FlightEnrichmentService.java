package com.flighttracer.service;

import com.flighttracer.dto.FlightStatusDto;
import com.flighttracer.model.Airport;
import com.flighttracer.model.StateVector;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Optional;

/**
 * Builds an enriched FlightStatusDto from a raw OpenSky StateVector.
 * Handles unit conversions, flight phase detection, and progress calculation.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class FlightEnrichmentService {

    private final AirportService airportService;

    /**
     * Build a complete FlightStatusDto.
     * depIata / arrIata can be null if we don't know the route yet
     * (will be populated later by Aviationstack in Phase 2).
     */
    public FlightStatusDto enrich(StateVector sv, String depIata, String arrIata) {
        FlightStatusDto.FlightStatusDtoBuilder builder = FlightStatusDto.builder()
                .icao24(sv.getIcao24())
                .callsign(sv.getCallsign())
                .originCountry(sv.getOriginCountry())
                .latitude(sv.getLatitude())
                .longitude(sv.getLongitude())
                .onGround(sv.getOnGround())
                .heading(sv.getTrueTrack())
                .lastUpdated(Instant.now().toEpochMilli());

        // Unit conversions
        if (sv.getBaroAltitude() != null) {
            builder.baroAltitudeFt(metersToFeet(sv.getBaroAltitude()));
        }
        if (sv.getVelocity() != null) {
            double kmh = sv.getVelocity() * 3.6;
            builder.speedKmh(round(kmh, 1));
            builder.speedKnots(round(kmh / 1.852, 1));
        }
        if (sv.getVerticalRate() != null) {
            // m/s → feet per minute
            builder.verticalRateFpm(round(sv.getVerticalRate() * 196.85, 0));
        }

        // Departure airport
        Optional<Airport> dep = resolveAirport(depIata);
        dep.ifPresent(a -> {
            builder.depIata(a.getIata());
            builder.depName(a.getName());
            builder.depCity(a.getCity());
            builder.depLat(a.getLat());
            builder.depLng(a.getLng());
        });

        // Arrival airport
        Optional<Airport> arr = resolveAirport(arrIata);
        arr.ifPresent(a -> {
            builder.arrIata(a.getIata());
            builder.arrName(a.getName());
            builder.arrCity(a.getCity());
            builder.arrLat(a.getLat());
            builder.arrLng(a.getLng());
        });

        // Progress calculation (requires both airports + current position)
        if (dep.isPresent() && arr.isPresent()
                && sv.getLatitude() != null && sv.getLongitude() != null) {

            Airport d = dep.get();
            Airport a = arr.get();

            double totalKm = airportService.distanceKm(d.getLat(), d.getLng(), a.getLat(), a.getLng());
            double flownKm = airportService.distanceKm(d.getLat(), d.getLng(), sv.getLatitude(), sv.getLongitude());
            double remainKm = Math.max(0, totalKm - flownKm);
            double progress = Math.min(100.0, (flownKm / totalKm) * 100.0);

            builder.distanceTotalKm(round(totalKm, 1));
            builder.distanceFlownKm(round(flownKm, 1));
            builder.distanceRemainingKm(round(remainKm, 1));
            builder.progressPercent(round(progress, 1));
        }

        // Flight phase
        builder.flightPhase(detectPhase(sv));

        return builder.build();
    }

    /**
     * Determine the current phase of flight based on telemetry.
     */
    private String detectPhase(StateVector sv) {
        if (Boolean.TRUE.equals(sv.getOnGround())) {
            return "ON_GROUND";
        }
        if (sv.getBaroAltitude() == null) return "UNKNOWN";

        double altFt = metersToFeet(sv.getBaroAltitude());
        Double vRate = sv.getVerticalRate();

        if (altFt < 1000) {
            return vRate != null && vRate > 0 ? "CLIMBING" : "LANDING";
        }
        if (altFt < 10000) {
            return vRate != null && vRate > 2 ? "CLIMBING" : vRate != null && vRate < -2 ? "DESCENDING" : "LOW_ALTITUDE";
        }
        if (vRate != null && vRate > 3) return "CLIMBING";
        if (vRate != null && vRate < -3) return "DESCENDING";
        return "CRUISING";
    }

    private double metersToFeet(double meters) {
        return meters * 3.28084;
    }

    private double round(double value, int decimals) {
        double scale = Math.pow(10, decimals);
        return Math.round(value * scale) / scale;
    }

    private Optional<Airport> resolveAirport(String code) {
        if (code == null || code.isBlank()) return Optional.empty();
        return airportService.findByIataOrIcao(code);
    }
}
