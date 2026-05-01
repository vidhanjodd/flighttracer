package com.flighttracer.controller;

import com.flighttracer.dto.FlightStatusDto;
import com.flighttracer.service.FlightTrackingService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Collection;
import java.util.Map;

@RestController
@RequestMapping("/api/flights")
@RequiredArgsConstructor
public class FlightController {

    private final FlightTrackingService flightTrackingService;

    /**
     * Search & start tracking a flight by IATA flight number.
     * e.g. GET /api/flights/LH400/track
     */
    @PostMapping("/{flightNumber}/track")
    public ResponseEntity<FlightStatusDto> trackFlight(@PathVariable String flightNumber) {
        FlightStatusDto dto = flightTrackingService.trackFlight(flightNumber);
        return ResponseEntity.ok(dto);
    }

    /**
     * Get the latest status of a tracked flight.
     * e.g. GET /api/flights/LH400/status
     */
    @GetMapping("/{flightNumber}/status")
    public ResponseEntity<FlightStatusDto> getStatus(@PathVariable String flightNumber) {
        FlightStatusDto dto = flightTrackingService.getStatus(flightNumber);
        return ResponseEntity.ok(dto);
    }

    /**
     * Stop tracking a flight.
     */
    @DeleteMapping("/{flightNumber}/track")
    public ResponseEntity<Map<String, String>> stopTracking(@PathVariable String flightNumber) {
        flightTrackingService.stopTracking(flightNumber);
        return ResponseEntity.ok(Map.of(
            "message", "Stopped tracking " + flightNumber,
            "flight", flightNumber.toUpperCase()
        ));
    }

    /**
     * Get all currently tracked flights (for a dashboard view).
     */
    @GetMapping("/tracked")
    public ResponseEntity<Collection<FlightStatusDto>> getAllTracked() {
        return ResponseEntity.ok(flightTrackingService.getAllTracked());
    }

    /**
     * Health check endpoint.
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        return ResponseEntity.ok(Map.of(
            "status", "UP",
            "trackedFlights", flightTrackingService.getAllTracked().size()
        ));
    }
}
