package com.flighttracer.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.flighttracer.service.OpenSkyTokenManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.*;

@RestController
@RequestMapping("/api/debug")
@RequiredArgsConstructor
@Slf4j
public class DebugController {

    private final OpenSkyTokenManager tokenManager;
    private final WebClient.Builder webClientBuilder;

    @Value("${app.opensky.base-url}")
    private String baseUrl;

    /** Step 1: token check */
    @GetMapping("/opensky/token")
    public ResponseEntity<Map<String, Object>> checkToken() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("credentialsConfigured", tokenManager.isConfigured());
        try {
            String token = tokenManager.getToken();
            result.put("tokenObtained", token != null);
            result.put("tokenPreview", token != null
                    ? token.substring(0, Math.min(20, token.length())) + "..." : null);
        } catch (Exception e) {
            result.put("tokenObtained", false);
            result.put("error", e.getMessage());
        }
        return ResponseEntity.ok(result);
    }

    /** Step 2: ping + sample callsigns */
    @GetMapping("/opensky/ping")
    public ResponseEntity<Map<String, Object>> ping() {
        Map<String, Object> result = new LinkedHashMap<>();
        long start = System.currentTimeMillis();
        try {
            JsonNode response = call(baseUrl + "/states/all");
            result.put("elapsedMs", System.currentTimeMillis() - start);

            if (response == null) {
                result.put("success", false);
                result.put("note", "null response — rate limited or bad token");
                return ResponseEntity.ok(result);
            }

            JsonNode states = response.get("states");
            int count = (states != null && states.isArray()) ? states.size() : 0;
            result.put("success", true);
            result.put("statesCount", count);

            // Sample 20 non-empty callsigns to see what's in the feed
            List<String> samples = new ArrayList<>();
            if (states != null) {
                for (JsonNode s : states) {
                    if (samples.size() >= 20) break;
                    if (s.size() > 1 && !s.get(1).isNull()) {
                        String cs = s.get(1).asText().trim();
                        if (!cs.isBlank()) samples.add(cs);
                    }
                }
            }
            result.put("sample20Callsigns", samples);

        } catch (Exception e) {
            result.put("success", false);
            result.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        return ResponseEntity.ok(result);
    }

    /**
     * Try both anonymous AND authenticated — compare state counts.
     * If authenticated gives fewer results, your account is sensor-restricted.
     * GET /api/debug/opensky/compare
     */
    @GetMapping("/opensky/compare")
    public ResponseEntity<Map<String, Object>> compare() {
        Map<String, Object> result = new LinkedHashMap<>();

        // Anonymous call
        long t1 = System.currentTimeMillis();
        try {
            WebClient client = webClientBuilder.baseUrl("")
                    .codecs(c -> c.defaultCodecs().maxInMemorySize(20 * 1024 * 1024))
                    .build();
            JsonNode resp = client.get().uri(baseUrl + "/states/all")
                    .retrieve().bodyToMono(JsonNode.class)
                    .timeout(Duration.ofSeconds(30)).block();
            JsonNode states = resp != null ? resp.get("states") : null;
            int count = (states != null && states.isArray()) ? states.size() : 0;
            result.put("anonymous_statesCount", count);
            result.put("anonymous_elapsedMs", System.currentTimeMillis() - t1);

            // Sample callsigns from anonymous
            List<String> samples = new ArrayList<>();
            if (states != null) {
                for (JsonNode s : states) {
                    if (samples.size() >= 10) break;
                    if (s.size() > 1 && !s.get(1).isNull()) {
                        String cs = s.get(1).asText().trim();
                        if (!cs.isBlank()) samples.add(cs);
                    }
                }
            }
            result.put("anonymous_sampleCallsigns", samples);
        } catch (Exception e) {
            result.put("anonymous_error", e.getMessage());
        }

        // Authenticated call
        long t2 = System.currentTimeMillis();
        try {
            String token = tokenManager.getToken();
            WebClient client = webClientBuilder.baseUrl("")
                    .codecs(c -> c.defaultCodecs().maxInMemorySize(20 * 1024 * 1024))
                    .build();
            JsonNode resp = client.get().uri(baseUrl + "/states/all")
                    .header("Authorization", "Bearer " + token)
                    .retrieve().bodyToMono(JsonNode.class)
                    .timeout(Duration.ofSeconds(30)).block();
            JsonNode states = resp != null ? resp.get("states") : null;
            int count = (states != null && states.isArray()) ? states.size() : 0;
            result.put("authenticated_statesCount", count);
            result.put("authenticated_elapsedMs", System.currentTimeMillis() - t2);

            List<String> samples = new ArrayList<>();
            if (states != null) {
                for (JsonNode s : states) {
                    if (samples.size() >= 10) break;
                    if (s.size() > 1 && !s.get(1).isNull()) {
                        String cs = s.get(1).asText().trim();
                        if (!cs.isBlank()) samples.add(cs);
                    }
                }
            }
            result.put("authenticated_sampleCallsigns", samples);
        } catch (Exception e) {
            result.put("authenticated_error", e.getMessage());
        }

        return ResponseEntity.ok(result);
    }

    /**
     * Search with multiple callsign variants.
     * LH400 in OpenSky might be stored as: LH400, DLH400, LH400 (padded), etc.
     * GET /api/debug/opensky/search/LH400
     */
    @GetMapping("/opensky/search/{callsign}")
    public ResponseEntity<Map<String, Object>> search(@PathVariable String callsign) {
        Map<String, Object> result = new LinkedHashMap<>();
        String upper = callsign.toUpperCase().replaceAll("[^A-Z0-9]", "");
        result.put("input", callsign);
        result.put("normalized", upper);

        // Common IATA→ICAO airline prefix mappings
        Map<String, String> iataToIcao = new LinkedHashMap<>();
        iataToIcao.put("LH", "DLH");
        iataToIcao.put("AI", "AIC");
        iataToIcao.put("6E", "IGO");
        iataToIcao.put("UK", "VTI");
        iataToIcao.put("SG", "SEJ");
        iataToIcao.put("BA", "BAW");
        iataToIcao.put("EK", "UAE");
        iataToIcao.put("QR", "QTR");
        iataToIcao.put("SQ", "SIA");
        iataToIcao.put("AF", "AFR");

        // Build variants to search
        Set<String> variants = new LinkedHashSet<>();
        variants.add(upper);                         // LH400
        // Try IATA→ICAO swap
        for (Map.Entry<String, String> e : iataToIcao.entrySet()) {
            if (upper.startsWith(e.getKey())) {
                String icaoVariant = e.getValue() + upper.substring(e.getKey().length());
                variants.add(icaoVariant);           // DLH400
            }
        }
        result.put("variantsSearched", new ArrayList<>(variants));

        try {
            JsonNode response = call(baseUrl + "/states/all");
            if (response == null) {
                result.put("error", "null response from OpenSky");
                return ResponseEntity.ok(result);
            }

            JsonNode states = response.get("states");
            int total = (states != null && states.isArray()) ? states.size() : 0;
            result.put("totalStates", total);

            List<Map<String, Object>> exactMatches = new ArrayList<>();
            List<String> airlinePartials = new ArrayList<>();   // same ICAO prefix
            String prefix3 = upper.length() >= 3 ? upper.substring(0, 3) : upper;
            // Also collect DLH prefix if applicable
            String icaoPrefix = iataToIcao.entrySet().stream()
                    .filter(e -> upper.startsWith(e.getKey()))
                    .map(Map.Entry::getValue)
                    .findFirst().orElse(null);

            if (states != null) {
                for (JsonNode s : states) {
                    if (s.size() < 2 || s.get(1).isNull()) continue;
                    String cs = s.get(1).asText().trim().toUpperCase();
                    if (cs.isBlank()) continue;

                    boolean isExact = variants.contains(cs);
                    if (isExact) {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("callsign", cs);
                        m.put("icao24", s.get(0).asText());
                        m.put("country", s.size() > 2 ? s.get(2).asText() : null);
                        m.put("lon", s.size() > 5 && !s.get(5).isNull() ? s.get(5).asDouble() : null);
                        m.put("lat", s.size() > 6 && !s.get(6).isNull() ? s.get(6).asDouble() : null);
                        m.put("alt_m", s.size() > 7 && !s.get(7).isNull() ? s.get(7).asDouble() : null);
                        m.put("on_ground", s.size() > 8 && !s.get(8).isNull() ? s.get(8).asBoolean() : null);
                        m.put("speed_ms", s.size() > 9 && !s.get(9).isNull() ? s.get(9).asDouble() : null);
                        exactMatches.add(m);
                    } else if (airlinePartials.size() < 15 &&
                            (cs.startsWith(prefix3) || (icaoPrefix != null && cs.startsWith(icaoPrefix)))) {
                        airlinePartials.add(cs);
                    }
                }
            }

            result.put("found", !exactMatches.isEmpty());
            result.put("exactMatches", exactMatches);
            result.put("otherFlightsSameAirline", airlinePartials);

            if (exactMatches.isEmpty() && airlinePartials.isEmpty()) {
                result.put("diagnosis", "No flights for this airline found at all. "
                        + "Your OpenSky account may be returning a limited regional feed. "
                        + "Try /api/debug/opensky/compare to check anonymous vs authenticated counts.");
            } else if (exactMatches.isEmpty()) {
                result.put("diagnosis", "Airline is in the feed but not this flight number. "
                        + "Try one of the otherFlightsSameAirline values instead.");
            }

        } catch (Exception e) {
            result.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        return ResponseEntity.ok(result);
    }


    private JsonNode call(String url) {
        String token = tokenManager.getToken();
        WebClient client = webClientBuilder.baseUrl("")
                .codecs(c -> c.defaultCodecs().maxInMemorySize(20 * 1024 * 1024))
                .build();
        var req = client.get().uri(url);
        if (token != null) {
            req = req.header("Authorization", "Bearer " + token);
        }
        return req.retrieve().bodyToMono(JsonNode.class)
                .timeout(Duration.ofSeconds(30)).block();
    }
}