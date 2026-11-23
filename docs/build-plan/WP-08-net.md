# WP-08 net (HTTP, firewall, network log)

Lane F. Milestone M3 (first internet use: food data, Open Food Facts), M5 (watch-app web, GPS assistance). Android library. Depends on: core-model, core-db (net_log, settings), coroutines.

## Goal

Every byte that leaves the phone goes through here, is allowed by a per-feature switch, and is written to a log the user can read. No extra HTTP library.

## API (implements `Http` from WP-02)

```kotlin
class NetClient(ctx: Context, private val firewall: Firewall, private val log: NetworkLog) : Http {
    override suspend fun send(req: HttpRequest): Outcome<HttpResponse>
    override suspend fun download(req: HttpRequest, to: File, onProgress: (Long, Long?) -> Unit): Outcome<Long>
}
class Firewall(private val settings: SettingsStore) {
    fun allowed(f: NetFeature): Boolean                 // setting "net.<feature>", default NetFeature.defaultOn
    fun observe(): Flow<Map<NetFeature, Boolean>>
}
class NetworkLog(private val dao: NetLogDao) {
    suspend fun record(f: NetFeature, host: String, method: String, up: Long, down: Long, result: Int)
    fun today(): Flow<List<NetLogEntry>>
}
```

## How a request runs

1. `firewall.allowed(feature)`? If not, log result −1 and return `SwError.Blocked`.
2. Connected? (`ConnectivityManager.getNetworkCapabilities(activeNetwork)` has `INTERNET`.) If not, log −2 and return `SwError.Offline`. Not needed for `LOCAL_PHONE_APP`.
3. **Wi-Fi-only features** (food data and recipe book downloads): check `NOT_METERED` or `TRANSPORT_WIFI`, else return `Offline` with reason "waiting for Wi-Fi".
4. `URL.openConnection() as HttpURLConnection`:
   - HTTPS only, except `http://127.0.0.1` for `LOCAL_PHONE_APP`.
   - `connectTimeout` = `readTimeout` = `req.timeoutMs`.
   - `instanceFollowRedirects = false`; redirects are followed manually up to 3, and only to HTTPS on the same feature.
   - `User-Agent: SelfWatch/<version>` (no device details, no user details).
   - No cookies (no `CookieHandler`).
5. Stream the body with a byte limit (`req.maxBytes`). Count bytes up and down.
6. Log host, method, bytes and status (never the URL path or query, which could hold search words).
7. Return the response.

**Downloads:**
- Write to `to.tmp`, check the size against `Content-Length` when given, then rename.
- Resumable with `Range` if the server supports it.
- Callers check the SHA-256 themselves.

**Cancellation:** coroutine cancellation calls `disconnect()` on the connection.

**Security:**
- System trust store only, no user-added certificates. `network_security_config.xml`: `cleartextTrafficPermitted="false"`, with a domain exception for `127.0.0.1` only.
- No certificate pinning in v1 (the food file has its own checksum).

## Network activity screen data

`net_log` rows grouped by day; totals per feature; "requests today" for the Privacy center card. Pruned after 90 days (daily, run by the service's day-change tick).

## Later (with the first device that needs them)

Wi-Fi join (`WifiNetworkSpecifier`, API 29+), FTP and UDP engines, in the same module, behind the same firewall and log.

## Compatibility checklist

- [ ] Works with the internet permission removed (GrapheneOS): every call returns `Offline` or `Blocked` cleanly; nothing crashes.
- [ ] No other module imports `java.net.URL`, `HttpURLConnection` or `Socket` (a lint check or Gradle-wide grep in CI: T-G09).

## Tests and definition of done

- [ ] Unit tests with a local `ServerSocket`-based tiny HTTP server in the test (no library): redirects, size limit, timeout, cancellation, blocked and offline paths, logging values.
- [ ] Log never stores paths or queries (test).
- [ ] Wi-Fi-only rule tested with a fake `ConnectivityManager` wrapper.
