package com.flighttracer.service;

import com.flighttracer.dto.FlightStatusDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

/**
 * Broadcasts flight status updates over WebSocket.
 *
 * Topic naming:
 *   /topic/flight/{callsign}  — updates for a specific flight
 *   /topic/flights/all        — all tracked flights (for dashboard)
 *
 * Frontend subscribes like:
 *   stompClient.subscribe('/topic/flight/DLH511', (msg) => { ... });
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class FlightWebSocketService {

    private final SimpMessagingTemplate messagingTemplate;

    /**
     * Push a single flight update to its topic.
     * Called by the scheduler every 15s for each tracked flight.
     */
    public void broadcastFlightUpdate(FlightStatusDto dto) {
        String callsign = dto.getCallsign() != null
                ? dto.getCallsign().trim()
                : dto.getFlightIata();

        // Broadcast to flight-specific topic
        String topic = "/topic/flight/" + callsign;
        messagingTemplate.convertAndSend(topic, dto);

        // Also broadcast to the "all flights" topic for dashboard
        messagingTemplate.convertAndSend("/topic/flights/all", dto);

        log.debug("WS broadcast → {} | phase={} | alt={}ft | speed={}kts | progress={}%",
                topic, dto.getFlightPhase(), dto.getBaroAltitudeFt(),
                dto.getSpeedKnots(), dto.getProgressPercent());
    }
}