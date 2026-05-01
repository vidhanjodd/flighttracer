package com.flighttracer.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Resolves IATA flight numbers to OpenSky ICAO callsigns.
 *
 * OpenSky stores callsigns in ICAO format (3-letter airline code + flight number).
 * IATA uses 2-letter codes. e.g. "LH400" (IATA) → "DLH400" (ICAO/OpenSky).
 *
 * This component generates all likely callsign variants to search.
 */
@Component
@Slf4j
public class CallsignResolver {

    // IATA 2-letter → ICAO 3-letter airline code mappings
    // Covers major Indian, European, Middle Eastern, Asian, US carriers
    private static final Map<String, String> IATA_TO_ICAO = new LinkedHashMap<>();

    static {
        // Indian carriers
        IATA_TO_ICAO.put("AI", "AIC");   // Air India
        IATA_TO_ICAO.put("6E", "IGO");   // IndiGo
        IATA_TO_ICAO.put("SG", "SEJ");   // SpiceJet
        IATA_TO_ICAO.put("UK", "VTI");   // Vistara (now Air India)
        IATA_TO_ICAO.put("I5", "DAI");   // Air Asia India
        IATA_TO_ICAO.put("IX", "AXB");   // Air India Express
        IATA_TO_ICAO.put("G8", "GOW");   // Go First
        IATA_TO_ICAO.put("QP", "ABD");   // Akasa Air

        // European carriers
        IATA_TO_ICAO.put("LH", "DLH");   // Lufthansa
        IATA_TO_ICAO.put("BA", "BAW");   // British Airways
        IATA_TO_ICAO.put("AF", "AFR");   // Air France
        IATA_TO_ICAO.put("KL", "KLM");   // KLM
        IATA_TO_ICAO.put("IB", "IBE");   // Iberia
        IATA_TO_ICAO.put("AZ", "AZA");   // ITA Airways
        IATA_TO_ICAO.put("SK", "SAS");   // Scandinavian
        IATA_TO_ICAO.put("OS", "AUA");   // Austrian
        IATA_TO_ICAO.put("LX", "SWR");   // Swiss
        IATA_TO_ICAO.put("TK", "THY");   // Turkish Airlines
        IATA_TO_ICAO.put("FR", "RYR");   // Ryanair
        IATA_TO_ICAO.put("U2", "EZY");   // EasyJet
        IATA_TO_ICAO.put("VY", "VLG");   // Vueling

        // Middle Eastern carriers
        IATA_TO_ICAO.put("EK", "UAE");   // Emirates
        IATA_TO_ICAO.put("EY", "ETD");   // Etihad
        IATA_TO_ICAO.put("QR", "QTR");   // Qatar Airways
        IATA_TO_ICAO.put("FZ", "FDB");   // flydubai
        IATA_TO_ICAO.put("G9", "ABY");   // Air Arabia
        IATA_TO_ICAO.put("WY", "OMA");   // Oman Air

        // Asian carriers
        IATA_TO_ICAO.put("SQ", "SIA");   // Singapore Airlines
        IATA_TO_ICAO.put("CX", "CPA");   // Cathay Pacific
        IATA_TO_ICAO.put("MH", "MAS");   // Malaysia Airlines
        IATA_TO_ICAO.put("TG", "THA");   // Thai Airways
        IATA_TO_ICAO.put("GA", "GIA");   // Garuda Indonesia
        IATA_TO_ICAO.put("NH", "ANA");   // ANA
        IATA_TO_ICAO.put("JL", "JAL");   // Japan Airlines
        IATA_TO_ICAO.put("OZ", "AAR");   // Asiana
        IATA_TO_ICAO.put("KE", "KAL");   // Korean Air

        // US carriers
        IATA_TO_ICAO.put("AA", "AAL");   // American Airlines
        IATA_TO_ICAO.put("UA", "UAL");   // United Airlines
        IATA_TO_ICAO.put("DL", "DAL");   // Delta
        IATA_TO_ICAO.put("WN", "SWA");   // Southwest
        IATA_TO_ICAO.put("B6", "JBU");   // JetBlue
        IATA_TO_ICAO.put("AS", "ASA");   // Alaska Airlines
        IATA_TO_ICAO.put("F9", "FFT");   // Frontier

        // Others
        IATA_TO_ICAO.put("ET", "ETH");   // Ethiopian Airlines
        IATA_TO_ICAO.put("SA", "SAA");   // South African Airways
        IATA_TO_ICAO.put("AC", "ACA");   // Air Canada
        IATA_TO_ICAO.put("LA", "LAN");   // LATAM
        IATA_TO_ICAO.put("JJ", "TAM");   // LATAM Brasil
        IATA_TO_ICAO.put("QF", "QFA");   // Qantas
        IATA_TO_ICAO.put("NZ", "ANZ");   // Air New Zealand
        IATA_TO_ICAO.put("5X", "UPS");   // UPS Airlines
        IATA_TO_ICAO.put("FX", "FDX");   // FedEx
    }

    /**
     * Given a user-entered flight number (IATA or ICAO), returns all
     * variants that OpenSky might store it as — in priority order.
     *
     * e.g. "LH400"  → ["LH400",  "DLH400"]
     *      "DLH400" → ["DLH400", "LH400"]
     *      "AI101"  → ["AI101",  "AIC101"]
     *      "6E    " → ["6E308",  "IGO308"]
     */
    public List<String> resolveVariants(String input) {
        String normalized = input.toUpperCase().replaceAll("[^A-Z0-9]", "");
        Set<String> variants = new LinkedHashSet<>();

        variants.add(normalized);   // always try as-is first

        // Try IATA → ICAO
        for (Map.Entry<String, String> entry : IATA_TO_ICAO.entrySet()) {
            String iata = entry.getKey();
            String icao = entry.getValue();

            if (normalized.startsWith(iata)) {
                String flightNum = normalized.substring(iata.length());
                variants.add(icao + flightNum);        // LH400 → DLH400
            }
            // Try reverse: ICAO → IATA
            if (normalized.startsWith(icao)) {
                String flightNum = normalized.substring(icao.length());
                variants.add(iata + flightNum);        // DLH400 → LH400
            }
        }

        log.debug("Resolved '{}' → variants: {}", normalized, variants);
        return new ArrayList<>(variants);
    }

    /**
     * Get the ICAO prefix for an airline's IATA code.
     * Used for "show me other flights from this airline" partial matching.
     */
    public Optional<String> getIcaoPrefix(String iataPrefix) {
        return Optional.ofNullable(IATA_TO_ICAO.get(iataPrefix.toUpperCase()));
    }

    /**
     * Return the full IATA→ICAO map (for debug endpoints).
     */
    public Map<String, String> getIataToIcaoMap() {
        return Collections.unmodifiableMap(IATA_TO_ICAO);
    }
}