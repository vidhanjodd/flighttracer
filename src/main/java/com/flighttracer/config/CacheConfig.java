package com.flighttracer.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

@Configuration
public class CacheConfig {

    @Bean
    public CacheManager cacheManager() {
        CaffeineCacheManager manager = new CaffeineCacheManager();

        // OpenSky state: short TTL — data changes every 10s
        manager.registerCustomCache("opensky-state",
                Caffeine.newBuilder()
                        .maximumSize(500)
                        .expireAfterWrite(12, TimeUnit.SECONDS)
                        .build());

        // Aviationstack: longer TTL — schedule data rarely changes mid-flight
        manager.registerCustomCache("aviationstack",
                Caffeine.newBuilder()
                        .maximumSize(200)
                        .expireAfterWrite(5, TimeUnit.MINUTES)
                        .build());

        manager.registerCustomCache("aviationstack-icao",
                Caffeine.newBuilder()
                        .maximumSize(200)
                        .expireAfterWrite(5, TimeUnit.MINUTES)
                        .build());

        return manager;
    }
}