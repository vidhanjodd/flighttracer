package com.flighttracer.controller;

import com.flighttracer.model.Airport;
import com.flighttracer.service.AirportService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/airports")
@RequiredArgsConstructor
public class AirportController {

    private final AirportService airportService;

    @GetMapping("/{code}")
    public ResponseEntity<Airport> getAirport(@PathVariable String code) {
        return airportService.findByIataOrIcao(code)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
}
