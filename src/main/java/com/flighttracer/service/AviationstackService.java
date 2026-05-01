package com.flighttracer.service;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.util.Optional;

/**
 * Fetches flight schedule data from Aviationstack (free tier: 100 req/month).
 *
 * Free tier limitations:
 * - HTTP only (no HTTPS) on free plan
 * - 100 requests/month
 * - No historical data
 *
 * Strategy: call once per flight, cache for 5 minutes.
 * At 15s polling with WebSocket, this costs 1 API call per tracked flight startup
 * + 1 every 5 min per tracked flight = well within 100/month for dev usage.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AviationstackService {

    @Value("${app.aviationstack.base-url}")
    private String baseUrl;

    @Value("${app.aviationstack.access-key:}")
    private String accessKey;

    private final WebClient.Builder webClientBuilder;

    public boolean isConfigured() {
        return accessKey != null && !accessKey.isBlank();
    }

    /**
     * Fetch flight info by IATA flight number (e.g. "LH400", "IX1385").
     * Cached for 5 minutes — Aviationstack schedule data doesn't change often.
     *
     * Returns a FlightInfo record with dep/arr IATA codes, scheduled times, delay.
     */
    @Cacheable(value = "aviationstack", key = "#flightIata.toUpperCase()")
    public Optional<FlightInfo> getFlightInfo(String flightIata) {
        if (!isConfigured()) {
            log.debug("Aviationstack not configured — skipping enrichment for {}", flightIata);
            return Optional.empty();
        }

        try {
            String url = baseUrl + "/flights"
                    + "?access_key=" + accessKey
                    + "&flight_iata=" + flightIata.toUpperCase()
                    + "&limit=1";

            log.debug("Aviationstack call: flights?flight_iata={}", flightIata);

            JsonNode response = webClientBuilder.build()
                    .get().uri(url)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .timeout(Duration.ofSeconds(10))
                    .block();

            if (response == null) {
                log.warn("Aviationstack null response for {}", flightIata);
                return Optional.empty();
            }

            // Check for API errors
            if (response.has("error")) {
                log.warn("Aviationstack error for {}: {}", flightIata, response.get("error"));
                return Optional.empty();
            }

            JsonNode data = response.get("data");
            if (data == null || !data.isArray() || data.isEmpty()) {
                log.debug("Aviationstack: no data for flight {}", flightIata);
                return Optional.empty();
            }

            JsonNode flight = data.get(0);
            FlightInfo info = parseFlightInfo(flight);
            log.info("Aviationstack enriched {}: {} → {} | delay={}min | status={}",
                    flightIata, info.depIata(), info.arrIata(),
                    info.delayMinutes(), info.status());
            return Optional.of(info);

        } catch (WebClientResponseException.TooManyRequests e) {
            log.warn("Aviationstack rate limit hit for {}", flightIata);
            return Optional.empty();
        } catch (Exception e) {
            log.warn("Aviationstack failed for {}: {}", flightIata, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Also try with ICAO callsign if IATA lookup returned nothing.
     * e.g. "DLH511" → try flight_icao=DLH511
     */
    @Cacheable(value = "aviationstack-icao", key = "#icaoCallsign.toUpperCase()")
    public Optional<FlightInfo> getFlightInfoByIcao(String icaoCallsign) {
        if (!isConfigured()) return Optional.empty();

        try {
            String url = baseUrl + "/flights"
                    + "?access_key=" + accessKey
                    + "&flight_icao=" + icaoCallsign.toUpperCase()
                    + "&limit=1";

            log.debug("Aviationstack call: flights?flight_icao={}", icaoCallsign);

            JsonNode response = webClientBuilder.build()
                    .get().uri(url)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .timeout(Duration.ofSeconds(10))
                    .block();

            if (response == null || response.has("error")) return Optional.empty();

            JsonNode data = response.get("data");
            if (data == null || !data.isArray() || data.isEmpty()) return Optional.empty();

            return Optional.of(parseFlightInfo(data.get(0)));

        } catch (Exception e) {
            log.warn("Aviationstack ICAO lookup failed for {}: {}", icaoCallsign, e.getMessage());
            return Optional.empty();
        }
    }

    // ── Parse ──────────────────────────────────────────────────────────────────

    private FlightInfo parseFlightInfo(JsonNode f) {
        String airline    = safeText(f, "airline", "name");
        String flightIata = safeText(f, "flight", "iata");
        String flightIcao = safeText(f, "flight", "icao");
        String status     = safeText(f, "flight_status");

        String depIata    = safeText(f, "departure", "iata");
        String depAirport = safeText(f, "departure", "airport");
        String depSched   = safeText(f, "departure", "scheduled");
        String depActual  = safeText(f, "departure", "actual");
        Integer depDelay  = safeInt(f, "departure", "delay");

        String arrIata    = safeText(f, "arrival", "iata");
        String arrAirport = safeText(f, "arrival", "airport");
        String arrSched   = safeText(f, "arrival", "scheduled");
        String arrEst     = safeText(f, "arrival", "estimated");
        Integer arrDelay  = safeInt(f, "arrival", "delay");

        // Use the larger of dep/arr delay as the overall delay
        Integer delay = arrDelay != null ? arrDelay : depDelay;

        return new FlightInfo(
                flightIata, flightIcao, airline, status,
                depIata, depAirport, depSched, depActual,
                arrIata, arrAirport, arrSched, arrEst,
                delay
        );
    }

    private String safeText(JsonNode node, String... path) {
        JsonNode current = node;
        for (String key : path) {
            if (current == null || !current.has(key) || current.get(key).isNull()) return null;
            current = current.get(key);
        }
        String val = current != null ? current.asText(null) : null;
        return (val == null || val.isBlank()) ? null : val;
    }

    private Integer safeInt(JsonNode node, String... path) {
        JsonNode current = node;
        for (String key : path) {
            if (current == null || !current.has(key) || current.get(key).isNull()) return null;
            current = current.get(key);
        }
        if (current == null || current.isNull()) return null;
        try { return current.asInt(); } catch (Exception e) { return null; }
    }

    // ── Record ────────────────────────────────────────────────────────────────

    public record FlightInfo(
            String flightIata,
            String flightIcao,
            String airline,
            String status,
            String depIata,
            String depAirport,
            String depScheduled,
            String depActual,
            String arrIata,
            String arrAirport,
            String arrScheduled,
            String arrEstimated,
            Integer delayMinutes
    ) {}
}