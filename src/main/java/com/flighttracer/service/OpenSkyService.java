package com.flighttracer.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.flighttracer.model.StateVector;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class OpenSkyService {

    @Value("${app.opensky.base-url}")
    private String baseUrl;

    private final WebClient.Builder webClientBuilder;
    private final OpenSkyTokenManager tokenManager;

    /**
     * Fetch a single aircraft by ICAO24 address.
     * Cached for 12 seconds (just under the OpenSky 10s resolution).
     */
    @Cacheable(value = "opensky-state", key = "#icao24.toLowerCase()")
    public Optional<StateVector> getStateByIcao24(String icao24) {
        try {
            String url = baseUrl + "/states/all?icao24=" + icao24.toLowerCase();
            JsonNode response = callOpenSky(url);
            if (response == null) return Optional.empty();

            JsonNode states = response.get("states");
            if (states == null || !states.isArray() || states.isEmpty()) {
                log.debug("No state found for icao24={}", icao24);
                return Optional.empty();
            }

            List<Object> arr = parseStateArray(states.get(0));
            return Optional.of(StateVector.fromArray(arr));
        } catch (Exception e) {
            log.warn("OpenSky getStateByIcao24 failed for {}: {}", icao24, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Search for a flight by callsign.
     *
     * OpenSky stores callsigns as exactly 8 chars, space-padded on the right.
     * e.g. "LH400" is stored as "LH400   "
     *
     * Strategy:
     * 1. Use /states/all with NO filter but with a timeout — OpenSky returns ~5MB JSON.
     *    We stream and match the first callsign hit, then stop.
     * 2. We also try the ICAO24 lookup if we previously saw this flight.
     *
     * NOTE: Anonymous requests to /states/all are limited. If you get empty results,
     * register a free account at opensky-network.org and set OPENSKY_CLIENT_ID/SECRET.
     */
    public Optional<StateVector> findByCallsign(String callsign) {
        // Normalize: uppercase, no spaces
        String normalized = callsign.toUpperCase().replaceAll("[^A-Z0-9]", "");
        log.info("Searching OpenSky for callsign: '{}' (normalized: '{}')", callsign, normalized);

        try {
            // OpenSky pads callsigns to 8 chars — build both variants to match
            String padded = String.format("%-8s", normalized); // "LH400   "

            String url = baseUrl + "/states/all";
            log.debug("Calling OpenSky: {}", url);
            JsonNode response = callOpenSky(url);

            if (response == null) {
                log.warn("OpenSky returned null — anonymous rate limit likely hit. "
                        + "Register at opensky-network.org for 4,000 credits/day free.");
                return Optional.empty();
            }

            JsonNode states = response.get("states");
            if (states == null || !states.isArray()) {
                log.warn("OpenSky returned no states array. Response: {}", response);
                return Optional.empty();
            }

            log.info("OpenSky returned {} aircraft states — searching for '{}'",
                    states.size(), normalized);

            for (JsonNode stateNode : states) {
                List<Object> arr = parseStateArray(stateNode);
                StateVector sv = StateVector.fromArray(arr);
                String cs = sv.getCallsign();
                if (cs == null) continue;

                String csTrimmed = cs.trim().toUpperCase();
                // Match normalized (exact) OR padded (as stored by OpenSky)
                if (csTrimmed.equals(normalized) || cs.toUpperCase().equals(padded.toUpperCase())) {
                    log.info("Found flight '{}' → icao24={}, lat={}, lng={}, alt={}m",
                            cs.trim(), sv.getIcao24(), sv.getLatitude(),
                            sv.getLongitude(), sv.getBaroAltitude());
                    return Optional.of(sv);
                }
            }

            log.warn("Flight '{}' not found in {} states. "
                            + "Flight may be on ground, not yet departed, or using a different callsign. "
                            + "Try with ICAO callsign (e.g. 'DLH400' instead of 'LH400').",
                    normalized, states.size());
            return Optional.empty();

        } catch (Exception e) {
            log.error("OpenSky findByCallsign failed for '{}': {}", callsign, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Get all active states (used by the scheduler to refresh tracked flights).
     */
    public List<StateVector> getAllStates() {
        try {
            JsonNode response = callOpenSky(baseUrl + "/states/all");
            if (response == null) return Collections.emptyList();

            JsonNode states = response.get("states");
            if (states == null || !states.isArray()) return Collections.emptyList();

            List<StateVector> result = new ArrayList<>();
            for (JsonNode stateNode : states) {
                result.add(StateVector.fromArray(parseStateArray(stateNode)));
            }
            return result;
        } catch (Exception e) {
            log.warn("OpenSky getAllStates failed: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    // ── internals ────────────────────────────────────────────────────────────

    private JsonNode callOpenSky(String url) {
        try {
            WebClient client = webClientBuilder
                    .baseUrl("")
                    .codecs(c -> c.defaultCodecs().maxInMemorySize(20 * 1024 * 1024)) // 20MB for all-states response
                    .build();

            WebClient.RequestHeadersSpec<?> request = client.get().uri(url);

            String token = tokenManager.getToken();
            if (token != null) {
                log.debug("Using authenticated OpenSky request");
                request = ((WebClient.RequestHeadersSpec<?>) request)
                        .header("Authorization", "Bearer " + token);
            } else {
                log.debug("Using anonymous OpenSky request (limited to ~400 credits/day)");
            }

            return request
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .timeout(Duration.ofSeconds(30))
                    .block();

        } catch (WebClientResponseException.TooManyRequests e) {
            log.warn("OpenSky rate limit hit (429) — slow down polling or add credentials");
            return null;
        } catch (WebClientResponseException.Unauthorized e) {
            log.warn("OpenSky 401 Unauthorized — credentials invalid or expired");
            return null;
        } catch (WebClientResponseException e) {
            log.warn("OpenSky HTTP error {}: {}", e.getStatusCode(), e.getMessage());
            return null;
        } catch (Exception e) {
            log.warn("OpenSky call failed [{}]: {} — {}", url, e.getClass().getSimpleName(), e.getMessage());
            return null;
        }
    }

    private List<Object> parseStateArray(JsonNode node) {
        List<Object> arr = new ArrayList<>();
        for (JsonNode element : node) {
            if (element.isNull()) {
                arr.add(null);
            } else if (element.isBoolean()) {
                arr.add(element.booleanValue());
            } else if (element.isNumber()) {
                arr.add(element.numberValue());
            } else {
                arr.add(element.asText());
            }
        }
        return arr;
    }
}