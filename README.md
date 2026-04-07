# aiSpreadBot

A prediction market trading bot for [Polymarket](https://polymarket.com) that combines weather-driven arbitrage, ensemble machine learning, Kelly-criterion position sizing, and Claude AI trade auditing.

---

## What it does

The bot runs continuously and executes two strategies in parallel:

### 1. Weather Arbitrage (primary)
Every 5 seconds, the bot:
1. Fetches real-world precipitation probability from NOAA's gridpoint forecast API
2. Compares it against the Polymarket implied probability for weather-related markets
3. Runs a 3-model Weka ML ensemble (Linear Regression, SVM, Random Forest) trained on historical NOAA data to generate a consensus probability
4. Calculates expected value after Polymarket's 2% fee, accounting for favourite-longshot bias calibration
5. Sizes positions using empirical half-Kelly, adjusted for signal disagreement (coefficient of variation)
6. For high-confidence trades (≥ 0.85), calls Claude to perform a holistic audit before placing the order

### 2. Market-Making (secondary)
Every 2 seconds, the bot posts bid/ask quotes on liquid non-weather markets with a dynamic spread:
- Base spread: 4%
- Adjusted by Wyckoff market phase (accumulation/distribution)
- Adjusted by solar geomagnetic activity (AP index), which acts as a systemic volatility multiplier

### Trade gates
A trade is placed only when **all** of the following pass:
- Net EV > 0 after fees
- Ensemble confidence > 0.6
- Signal CV < 0.3 (models agree)
- No Wyckoff "spring" false-breakdown detected
- Position cap (25% of bankroll) not exceeded
- Kill-switch inactive (triggered if drawdown exceeds 40%)
- Claude audit approved (if confidence ≥ 0.85)

### Risk management
- Stop-loss: −25% per position
- Take-profit: +30% per position
- Max position size: 25% of bankroll
- Kill-switch: halts all new trades at 40% portfolio drawdown
- Risk checks run every 3 seconds

---

## Tech stack

| Layer | Technology |
|---|---|
| Framework | Spring Boot 3.2.4 (Java 17) |
| Reactive HTTP | Spring WebFlux / WebClient |
| Machine learning | Weka 3.8.6 |
| Technical analysis | ta4j 0.15 |
| Blockchain signing | Web3j 4.10.3 (EIP-712, Polygon) |
| AI auditing | Anthropic Claude API (claude-haiku-4-5) |
| Real-time comms | WebSocket + STOMP |
| API docs | SpringDoc OpenAPI (Swagger UI) |
| Containerisation | Docker (multi-stage Maven + JRE build) |

---

## External services

| Service | Purpose |
|---|---|
| Polymarket CLOB API | Market data, order placement |
| Polymarket GAMMA API | Market scanning and metadata |
| NOAA Gridpoint Forecast | Real-time weather probabilities (NYC Central Park) |
| NOAA Space Weather | 45-day solar AP index forecast |
| Anthropic Claude API | Trade auditing and market classification |

---

## Configuration

All secrets are read from environment variables. No secrets are hardcoded.

```bash
# Polymarket (required for live trading)
POLY_API_KEY=
POLY_API_SECRET=
POLY_API_PASSPHRASE=
POLYGON_PRIVATE_KEY=      # Polygon wallet private key
POLY_PROXY_ADDRESS=       # Polymarket proxy/funder address

# Anthropic (required for AI auditing)
ANTHROPIC_API_KEY=

# Optional
PORT=8080                 # defaults to 8080
```

Key toggles in `application-live.yml`:

```yaml
polymarket:
  live-mode: true    # false = read-only market data only
  dry-run: true      # true = simulate trades, no real capital at risk
```

**Start with `dry-run: true` to simulate without risking capital.**

---

## Running

### Docker (recommended)

```bash
docker build -t aispreadbot .
docker run -p 8080:8080 \
  -e POLY_API_KEY=... \
  -e POLY_API_SECRET=... \
  -e POLY_API_PASSPHRASE=... \
  -e POLYGON_PRIVATE_KEY=... \
  -e POLY_PROXY_ADDRESS=... \
  -e ANTHROPIC_API_KEY=... \
  aispreadbot
```

### Maven

```bash
mvn clean package -DskipTests
java -jar target/spreadengine-*.jar
```

---

## Monitoring

Once running, the following endpoints are available:

| Endpoint | Description |
|---|---|
| `GET /api/status` | Full bot snapshot: bankroll, trades, ML scores, active strategy state |
| `GET /api/markets` | Currently scanned Polymarket markets |
| `GET /api/positions` | Open positions and trade history with P&L |
| `GET /api/debug` | Trade gate diagnostics — why trades did or did not fire |
| `GET /swagger-ui.html` | Interactive API docs |
| `WS /spreadbot-ws` | STOMP WebSocket for live event streaming |
| `/topic/events` | Trade events, audit results, risk alerts |
| `/topic/ai-insights` | Real-time confidence, sentiment, and technical metrics |

---

## Project structure

```
src/main/java/com/bot/spreadengine/
├── engine/
│   ├── LiveTradingEngine.java      # Main trading loop (weather arb + market-making)
│   └── SimulationEngine.java       # Dry-run / backtesting orchestrator
├── service/
│   ├── ClaudeAnalysisService.java  # Claude AI trade auditing
│   ├── WekaAnalysisService.java    # ML ensemble (3 models)
│   ├── MarketScannerService.java   # Hourly Polymarket market scanning
│   ├── PolymarketService.java      # Polymarket REST/CLOB API client
│   ├── RiskManagementService.java  # Stop-loss, take-profit, kill-switch
│   ├── PositionService.java        # Position tracking and P&L
│   ├── WeatherService.java         # NOAA weather forecast client
│   ├── SpaceWeatherService.java    # NOAA solar AP index client
│   ├── TechnicalAnalysisService.java # RSI, MACD, Bollinger Bands via ta4j
│   ├── WyckoffService.java         # Wyckoff phase detection
│   └── OrderSigningService.java    # EIP-712 Polygon order signing
└── controller/
    └── DiagnosticsController.java  # REST + WebSocket endpoints
```

---

## Disclaimer

This software is for educational and research purposes. Prediction market trading involves real financial risk. Use dry-run mode to evaluate performance before committing capital.
