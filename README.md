# FlightTracer Backend — Phase 1

Spring Boot backend for the flight tracker app. Polls OpenSky Network (free) every 15s and exposes REST endpoints for flight tracking.

---

## Setup

### 1. PostgreSQL

```sql
CREATE DATABASE flighttracer;
```

### 2. OpenSky credentials (free, recommended)

Register at https://opensky-network.org → Account → Create API Client

```bash
export OPENSKY_CLIENT_ID=your_client_id
export OPENSKY_CLIENT_SECRET=your_client_secret
```

Without credentials it works anonymously (400 credits/day — lower limit).
With free account: 4,000 credits/day.

### 3. Aviationstack (optional for Phase 1, needed for Phase 2)

Free tier: 100 requests/month
Register at: https://aviationstack.com → free plan

```bash
export AVIATIONSTACK_KEY=your_key
```

### 4. Optional local override file

Keep secrets out of Git. Create `src/main/resources/application-local.yml` for machine-local values only.

```yaml
spring:
  datasource:
    username: your_db_username
    password: your_db_password

app:
  opensky:
    client-id: your_client_id
    client-secret: your_client_secret
  aviationstack:
    access-key: your_key
```

That file is ignored by Git. If you use it, run the app with:

```bash
mvn spring-boot:run -Dspring-boot.run.profiles=local
```

### 5. Run

```bash
mvn spring-boot:run
```

---

## API Endpoints

### Track a flight
```
POST /api/flights/{flightNumber}/track
POST /api/flights/LH400/track
```

### Get live status
```
GET /api/flights/{flightNumber}/status
GET /api/flights/LH400/status
```

### Stop tracking
```
DELETE /api/flights/{flightNumber}/track
```

### All tracked flights
```
GET /api/flights/tracked
```

### Airport lookup
```
GET /api/airports/DEL
GET /api/airports/EGLL
```

---

## Sample Response

```json
{
  "icao24": "3c6444",
  "callsign": "LH400",
  "flightIata": "LH400",
  "originCountry": "Germany",
  "latitude": 52.0,
  "longitude": -37.5,
  "baroAltitudeFt": 33000.0,
  "speedKnots": 472.0,
  "speedKmh": 874.0,
  "verticalRateFpm": 0.0,
  "heading": 270.5,
  "onGround": false,
  "flightPhase": "CRUISING",
  "depIata": "FRA",
  "depName": "Frankfurt am Main Airport",
  "depCity": "Frankfurt",
  "arrIata": "JFK",
  "arrName": "John F Kennedy International Airport",
  "arrCity": "New York",
  "progressPercent": 45.2,
  "distanceTotalKm": 6200.0,
  "distanceFlownKm": 2802.4,
  "distanceRemainingKm": 3397.6,
  "lastUpdated": 1700000000000
}
```

---

## Project Structure

```
src/main/java/com/flighttracer/
├── FlightTracerApplication.java
├── config/
│   └── WebConfig.java             ← CORS
├── controller/
│   ├── FlightController.java      ← REST endpoints
│   └── AirportController.java
├── dto/
│   └── FlightStatusDto.java       ← enriched response
├── exception/
│   ├── FlightNotFoundException.java
│   └── GlobalExceptionHandler.java
├── model/
│   ├── Airport.java
│   └── StateVector.java           ← OpenSky raw data
├── scheduler/
│   └── FlightPollingScheduler.java ← every 15s
└── service/
    ├── AirportService.java         ← airports.json + distance math
    ├── FlightEnrichmentService.java ← units, phase, progress%
    ├── FlightTrackingService.java   ← central registry
    ├── OpenSkyService.java          ← API calls
    └── OpenSkyTokenManager.java     ← OAuth2 token refresh
```

---

## What's next — Phase 2

- WebSocket (STOMP) — push live updates to the browser every 15s
- Aviationstack integration — scheduled departure/arrival times, delay info
- Caffeine cache — cache Aviationstack responses (they don't change often)
- Proper flight lookup by IATA number (not just callsign)
