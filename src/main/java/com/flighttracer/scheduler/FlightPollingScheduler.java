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
     * Refresh all tracked flights every 15 seconds.
     *
     * Rate limit math (OpenSky free tier):
     *   4,000 credits/day = ~166 credits/hour = ~2.77 credits/minute
     *   At 15s interval → 4 requests/minute per tracked flight
     *   So you can safely track ~1 flight before hitting the daily limit
     *   With auth: 4,000 credits = ~1,000 targeted requests (4 credits each)
     *
     * TODO Phase 2: broadcast updated DTOs to WebSocket subscribers here.
     */
    @Scheduled(fixedRateString = "${app.opensky.poll-interval-ms:15000}")
    public void pollTrackedFlights() {
        flightTrackingService.refreshAll();
    }
}
