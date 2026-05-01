package com.flighttracer.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flighttracer.model.Airport;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
@Slf4j
public class AirportService {

    private final Map<String, Airport> byIata = new HashMap<>();
    private final Map<String, Airport> byIcao = new HashMap<>();
    private final ObjectMapper objectMapper;

    public AirportService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void loadAirports() {
        try {
            InputStream is = new ClassPathResource("airports.json").getInputStream();
            List<Airport> airports = objectMapper.readValue(is, new TypeReference<>() {});
            for (Airport airport : airports) {
                if (airport.getIata() != null && !airport.getIata().isBlank()) {
                    byIata.put(airport.getIata().toUpperCase(), airport);
                }
                if (airport.getIcao() != null && !airport.getIcao().isBlank()) {
                    byIcao.put(airport.getIcao().toUpperCase(), airport);
                }
            }
            log.info("Loaded {} airports ({} by IATA, {} by ICAO)",
                    airports.size(), byIata.size(), byIcao.size());
        } catch (Exception e) {
            log.error("Failed to load airports.json", e);
        }
    }

    public Optional<Airport> findByIata(String iata) {
        if (iata == null) return Optional.empty();
        return Optional.ofNullable(byIata.get(iata.toUpperCase()));
    }

    public Optional<Airport> findByIcao(String icao) {
        if (icao == null) return Optional.empty();
        return Optional.ofNullable(byIcao.get(icao.toUpperCase()));
    }

    public Optional<Airport> findByIataOrIcao(String code) {
        Optional<Airport> byIataResult = findByIata(code);
        return byIataResult.isPresent() ? byIataResult : findByIcao(code);
    }

    /**
     * Haversine great-circle distance in kilometres.
     */
    public double distanceKm(double lat1, double lng1, double lat2, double lng2) {
        final double R = 6371.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                 + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                 * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return R * c;
    }

    /**
     * Calculate flight progress as a percentage (0–100).
     * Based on haversine distance from departure to current vs total.
     */
    public double calculateProgress(
            double depLat, double depLng,
            double currentLat, double currentLng,
            double arrLat, double arrLng) {
        double totalDist = distanceKm(depLat, depLng, arrLat, arrLng);
        if (totalDist == 0) return 0;
        double flownDist = distanceKm(depLat, depLng, currentLat, currentLng);
        return Math.min(100.0, (flownDist / totalDist) * 100.0);
    }
}
