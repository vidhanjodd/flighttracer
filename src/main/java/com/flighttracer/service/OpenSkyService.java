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
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Slf4j
public class OpenSkyService {

    @Value("${app.opensky.base-url}")
    private String baseUrl;

    private final WebClient.Builder webClientBuilder;
    private final OpenSkyTokenManager tokenManager;
    private final CallsignResolver callsignResolver;

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
     * Search for a flight by callsign — tries IATA and ICAO variants.
     * e.g. "LH400" will also search "DLH400" automatically.
     */
    public Optional<StateVector> findByCallsign(String callsign) {
        List<String> variants = callsignResolver.resolveVariants(callsign);
        log.info("Searching OpenSky for '{}' — trying variants: {}", callsign, variants);

        try {
            JsonNode response = callOpenSky(baseUrl + "/states/all");

            if (response == null) {
                log.warn("OpenSky returned null response — rate limited or auth issue.");
                return Optional.empty();
            }

            JsonNode states = response.get("states");
            if (states == null || !states.isArray()) {
                log.warn("OpenSky states array missing or empty.");
                return Optional.empty();
            }

            log.info("Scanning {} states for variants {}", states.size(), variants);

            // Build a set of uppercase variants for O(1) lookup
            Set<String> variantSet = new HashSet<>();
            for (String v : variants) variantSet.add(v.toUpperCase());

            for (JsonNode stateNode : states) {
                List<Object> arr = parseStateArray(stateNode);
                StateVector sv = StateVector.fromArray(arr);
                String cs = sv.getCallsign();
                if (cs == null) continue;
                String csTrimmed = cs.trim().toUpperCase();
                if (variantSet.contains(csTrimmed)) {
                    log.info("✓ Found '{}' as '{}' → icao24={}, lat={}, lng={}, alt={}m, onGround={}",
                            callsign, csTrimmed, sv.getIcao24(),
                            sv.getLatitude(), sv.getLongitude(),
                            sv.getBaroAltitude(), sv.getOnGround());
                    return Optional.of(sv);
                }
            }

            log.warn("'{}' not found in {} states (tried variants: {}). " +
                            "Flight may be on ground or not yet departed.",
                    callsign, states.size(), variants);
            return Optional.empty();

        } catch (Exception e) {
            log.error("findByCallsign failed for '{}': {}", callsign, e.getMessage());
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