package com.flighttracer.controller;

import com.flighttracer.dto.FlightStatusDto;
import com.flighttracer.service.FlightTrackingService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/flights")
@RequiredArgsConstructor
public class FlightController {

    private final FlightTrackingService flightTrackingService;

    /**
     * Start tracking a flight. Returns current state + WebSocket topic to subscribe to.
     * POST /api/flights/DLH511/track
     */
    @PostMapping("/{flightNumber}/track")
    public ResponseEntity<Map<String, Object>> trackFlight(@PathVariable String flightNumber) {
        FlightStatusDto dto = flightTrackingService.trackFlight(flightNumber);

        String callsign = dto.getCallsign() != null
                ? dto.getCallsign().trim() : dto.getFlightIata();

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("flight", dto);
        response.put("websocket", Map.of(
                "endpoint",    "/ws",
                "topic",       "/topic/flight/" + callsign,
                "allTopic",    "/topic/flights/all",
                "description", "Connect with SockJS + STOMP, subscribe to 'topic' for live updates every 15s"
        ));

        return ResponseEntity.ok(response);
    }

    /**
     * Get latest status without starting tracking.
     * GET /api/flights/DLH511/status
     */
    @GetMapping("/{flightNumber}/status")
    public ResponseEntity<FlightStatusDto> getStatus(@PathVariable String flightNumber) {
        return ResponseEntity.ok(flightTrackingService.getStatus(flightNumber));
    }

    /**
     * Stop tracking a flight.
     * DELETE /api/flights/DLH511/track
     */
    @DeleteMapping("/{flightNumber}/track")
    public ResponseEntity<Map<String, String>> stopTracking(@PathVariable String flightNumber) {
        flightTrackingService.stopTracking(flightNumber);
        return ResponseEntity.ok(Map.of(
                "message", "Stopped tracking " + flightNumber.toUpperCase(),
                "flight",  flightNumber.toUpperCase()
        ));
    }

    /**
     * All currently tracked flights.
     * GET /api/flights/tracked
     */
    @GetMapping("/tracked")
    public ResponseEntity<Collection<FlightStatusDto>> getAllTracked() {
        return ResponseEntity.ok(flightTrackingService.getAllTracked());
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        return ResponseEntity.ok(Map.of(
                "status", "UP",
                "trackedFlights", flightTrackingService.getAllTracked().size()
        ));
    }
}