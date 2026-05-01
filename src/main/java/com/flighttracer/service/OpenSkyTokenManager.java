package com.flighttracer.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Manages the OpenSky OAuth2 access token.
 * Automatically refreshes before expiry.
 * Falls back to anonymous (unauthenticated) requests if no credentials are configured.
 */
@Component
@Slf4j
public class OpenSkyTokenManager {

    @Value("${app.opensky.client-id:}")
    private String clientId;

    @Value("${app.opensky.client-secret:}")
    private String clientSecret;

    @Value("${app.opensky.auth-url}")
    private String authUrl;

    private final WebClient webClient;
    private final AtomicReference<String> currentToken = new AtomicReference<>();
    private volatile Instant tokenExpiry = Instant.EPOCH;

    public OpenSkyTokenManager(WebClient.Builder builder) {
        this.webClient = builder.build();
    }

    public boolean isConfigured() {
        return clientId != null && !clientId.isBlank()
            && clientSecret != null && !clientSecret.isBlank();
    }

    /**
     * Returns a valid Bearer token, or null if running unauthenticated.
     */
    public String getToken() {
        if (!isConfigured()) {
            return null; // anonymous requests
        }

        // Refresh 60s before expiry
        if (currentToken.get() == null || Instant.now().isAfter(tokenExpiry.minusSeconds(60))) {
            refreshToken();
        }
        return currentToken.get();
    }

    @SuppressWarnings("unchecked")
    private synchronized void refreshToken() {
        try {
            MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
            form.add("grant_type", "client_credentials");
            form.add("client_id", clientId);
            form.add("client_secret", clientSecret);

            Map<String, Object> response = webClient.post()
                    .uri(authUrl)
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .body(BodyInserters.fromFormData(form))
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();

            if (response != null && response.containsKey("access_token")) {
                currentToken.set((String) response.get("access_token"));
                int expiresIn = (Integer) response.getOrDefault("expires_in", 300);
                tokenExpiry = Instant.now().plusSeconds(expiresIn);
                log.debug("OpenSky token refreshed, expires in {}s", expiresIn);
            }
        } catch (Exception e) {
            log.warn("Failed to refresh OpenSky token: {}", e.getMessage());
        }
    }
}
