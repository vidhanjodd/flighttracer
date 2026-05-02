package com.flighttracer.scheduler;

import com.flighttracer.service.FlightTrackingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class FlightPollingScheduler {

    private final FlightTrackingService flightTrackingService;

    /**
     * Every 15s: refresh OpenSky state for all tracked flights,
     * then broadcast updated DTOs to WebSocket subscribers.
     */
    @Scheduled(fixedRateString = "${app.opensky.poll-interval-ms:15000}")
    public void pollTrackedFlights() {
        flightTrackingService.refreshAll();
    }
}