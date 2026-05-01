package com.flighttracer.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Represents a single aircraft state vector from the OpenSky API.
 * OpenSky returns states as a 2D array — this is the deserialized form.
 *
 * Array index mapping (from OpenSky docs):
 * 0  icao24          | 1  callsign     | 2  origin_country
 * 3  time_position   | 4  last_contact | 5  longitude
 * 6  latitude        | 7  baro_alt     | 8  on_ground
 * 9  velocity        | 10 true_track   | 11 vertical_rate
 * 12 sensors         | 13 geo_altitude | 14 squawk
 * 15 spi             | 16 position_src
 */
@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class StateVector {
    private String icao24;
    private String callsign;
    private String originCountry;
    private Long timePosition;
    private Long lastContact;
    private Double longitude;
    private Double latitude;
    private Double baroAltitude;
    private Boolean onGround;
    private Double velocity;       // m/s
    private Double trueTrack;      // degrees
    private Double verticalRate;   // m/s
    private Double geoAltitude;
    private String squawk;
    private Integer positionSource;

    /** Construct from raw OpenSky array element */
    public static StateVector fromArray(java.util.List<Object> arr) {
        StateVector sv = new StateVector();
        sv.setIcao24(getString(arr, 0));
        sv.setCallsign(getString(arr, 1) != null ? getString(arr, 1).trim() : null);
        sv.setOriginCountry(getString(arr, 2));
        sv.setTimePosition(getLong(arr, 3));
        sv.setLastContact(getLong(arr, 4));
        sv.setLongitude(getDouble(arr, 5));
        sv.setLatitude(getDouble(arr, 6));
        sv.setBaroAltitude(getDouble(arr, 7));
        sv.setOnGround(getBoolean(arr, 8));
        sv.setVelocity(getDouble(arr, 9));
        sv.setTrueTrack(getDouble(arr, 10));
        sv.setVerticalRate(getDouble(arr, 11));
        sv.setGeoAltitude(getDouble(arr, 13));
        sv.setSquawk(getString(arr, 14));
        sv.setPositionSource(getInt(arr, 16));
        return sv;
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static String getString(java.util.List<Object> arr, int i) {
        if (i >= arr.size() || arr.get(i) == null) return null;
        return arr.get(i).toString();
    }

    private static Double getDouble(java.util.List<Object> arr, int i) {
        if (i >= arr.size() || arr.get(i) == null) return null;
        Object v = arr.get(i);
        if (v instanceof Number) return ((Number) v).doubleValue();
        try { return Double.parseDouble(v.toString()); } catch (Exception e) { return null; }
    }

    private static Long getLong(java.util.List<Object> arr, int i) {
        if (i >= arr.size() || arr.get(i) == null) return null;
        Object v = arr.get(i);
        if (v instanceof Number) return ((Number) v).longValue();
        try { return Long.parseLong(v.toString()); } catch (Exception e) { return null; }
    }

    private static Integer getInt(java.util.List<Object> arr, int i) {
        if (i >= arr.size() || arr.get(i) == null) return null;
        Object v = arr.get(i);
        if (v instanceof Number) return ((Number) v).intValue();
        try { return Integer.parseInt(v.toString()); } catch (Exception e) { return null; }
    }

    private static Boolean getBoolean(java.util.List<Object> arr, int i) {
        if (i >= arr.size() || arr.get(i) == null) return null;
        Object v = arr.get(i);
        if (v instanceof Boolean) return (Boolean) v;
        return Boolean.parseBoolean(v.toString());
    }
}
