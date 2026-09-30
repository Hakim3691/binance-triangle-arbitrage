# Security

## Credential handling

| Aspect | Implementation |
|---|---|
| Storage | `CredentialStore` — EncryptedSharedPreferences (AES-256-GCM values, AES-256-SIV keys) backed by Android Keystore master key |
| Transport | HTTPS/TLS (REST) and WSS (streams) only; HMAC-SHA256 request signing per Binance spec |
| Memory | Secrets are read from Keystore only when a live session is created; never cached in Activity/ViewModel state |
| Logs | **Never logged.** `LogRepository` receives engine/execution messages only; OkHttp interceptors are not used for auth headers; stack traces containing request URLs are sanitized through typed exceptions (`BinanceApiException` carries code+message, not the signed URL) |
| Build artifacts | No keys in `BuildConfig`, `gradle.properties`, `local.properties`, or resources; `local.properties` is gitignored |
| Removal | Settings → *REMOVE* wipes both values; uninstall clears Keystore entries |

## Live-trading safeguards

1. **Paper is the default.** `TradingMode.PAPER` is the initial state; the
   paper engine never sends order requests — order REST endpoints are
   unreachable from paper mode by construction (`PaperTradingEngine` does not
   hold an API client).
2. **Double gate for live:** credentials must already be stored *and* the user
   must type the exact confirmation phrase
   (`I UNDERSTAND REAL ORDERS WILL BE PLACED`).
3. **Unmistakable state:** while live, every screen shows a red
   `● LIVE TRADING — REAL ORDERS` banner; paper shows a green banner.
4. **Balance pre-flight:** before live execution the app verifies the
   configured base balance and a positive BNB balance (fee asset), mirroring
   the original `checkBalances`.
5. **Execution cap:** `EXECUTION.CAP` (default 1) pauses the scanner after the
   configured number of executions until the user explicitly resets it.
6. **No automation exposure:** CI/test builds cannot enable live mode — there
   is no intent/extra/debug flag that bypasses the confirmation flow.

## Network surface

| Endpoint | Purpose |
|---|---|
| `https://api.binance.com/api/v3/{time,exchangeInfo,depth}` | public market data (no auth) |
| `https://api.binance.com/api/v3/{order,account}` | live mode only, HMAC-signed |
| `wss://stream.binance.com:9443/stream` | public depth streams |

No other hosts are contacted. No analytics, crash reporting, or telemetry.

## Reporting

If you find a security issue in this project, open a GitHub issue marked
*security* or contact the repository owner directly. Please do not include
API keys in issue reports.
