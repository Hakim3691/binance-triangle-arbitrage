package com.hakim3691.bta.market

import com.hakim3691.bta.core.OrderFill
import com.hakim3691.bta.core.OrderResponse
import com.hakim3691.bta.core.SymbolFilter
import com.hakim3691.bta.core.SymbolInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.double
import kotlinx.serialization.json.long
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Binance REST error carrying the API error code/message. */
class BinanceApiException(val code: Int, message: String) : Exception(message)

/**
 * Turns a transport failure into something a user can act on.
 *
 * Every REST failure used to surface as the same opaque
 * "[HTTP 451] Service unavailable from a restricted location..." or bare
 * UnknownHostException, so the dashboard could only say ERROR and the
 * credentials panel could only say "permission check unavailable" - with no
 * way to tell a geo-blocked region from a dead Wi-Fi connection from a
 * revoked API key. Those three have completely different fixes, and the one
 * that bites hardest (HTTP 451, Binance refusing the whole jurisdiction) is
 * invisible unless it is named.
 */
object RestDiagnosis {

    /** Binance's jurisdiction refusal: the account/IP cannot use the API at all. */
    const val GEO_BLOCKED = "GEO BLOCKED"

    fun describe(t: Throwable): String {
        val raw = (t.message ?: "").trim()
        val lower = raw.lowercase()

        // Binance answers a blocked region with HTTP 451 on EVERY endpoint,
        // signed or not, before the key is ever evaluated.
        if (t is BinanceApiException && t.code == 451) {
            return "Binance blocked this network's region (HTTP 451). The API key " +
                "cannot fix this - the device needs a connection from a country " +
                "Binance serves, or a Binance.US account."
        }
        if (t is BinanceApiException && t.code == 401) {
            return "Binance rejected the API key (HTTP 401). The key is invalid, " +
                "expired, or wrong for this environment."
        }
        if (t is BinanceApiException && t.code == 429) {
            return "Binance rate limit hit (HTTP 429). Too many requests; wait and retry."
        }
        if (t is BinanceApiException && (t.code == -1021 || lower.contains("-1021") || lower.contains("recvwindow"))) {
            return "Device clock is out of sync with Binance (-1021). Enable " +
                "automatic date/time on this device."
        }
        if (t is BinanceApiException && (t.code == -2015 || lower.contains("-2015"))) {
            return "Binance rejected the API key (-2015). Check the key and secret, " +
                "and that they were copied in full."
        }
        if (t is BinanceApiException) {
            // The code is Binance's own here, not an HTTP status, so it is
            // labelled as such rather than as a status line.
            return "Binance error ${t.code}: ${raw.take(160)}"
        }

        // No HTTP response was received at all: this is the device, not Binance.
        if (t is java.net.UnknownHostException) {
            return "Cannot resolve api.binance.com - no working internet connection."
        }
        if (t is java.net.SocketTimeoutException) {
            return "Timed out reaching Binance - the connection is too slow or offline."
        }
        if (t is java.net.ConnectException) {
            return "Connection to Binance refused - check the network or VPN."
        }
        if (t is javax.net.ssl.SSLException) {
            return "TLS handshake with Binance failed - a proxy or firewall is " +
                "inspecting the connection, or the system date is wrong."
        }
        if (t is java.io.IOException) {
            return "Network failure reaching Binance: ${raw.take(160)}"
        }
        return "${t.javaClass.simpleName}: ${raw.take(160)}"
    }
}

/** Simple token-bucket style rate limiter for REST weight budgeting. */
class RateLimiter(private val maxWeightPerMinute: Int = 6000) {
    private val windowStart = AtomicLong(System.currentTimeMillis())
    private val used = AtomicLong(0)

    @Synchronized
    fun acquire(weight: Int) {
        val now = System.currentTimeMillis()
        if (now - windowStart.get() > 60_000) {
            windowStart.set(now)
            used.set(0)
        }
        if (used.get() + weight > maxWeightPerMinute) {
            val waitMs = 60_000 - (now - windowStart.get()) + 50
            throw BinanceApiException(429, "Rate limit budget exceeded; retry after ${waitMs}ms")
        }
        used.addAndGet(weight.toLong())
    }

    fun usedThisWindow(): Int = used.get().toInt()
}

/**
 * Binance spot REST client (api.binance.com) using OkHttp.
 *
 * Endpoints used by the port:
 *  - GET /api/v3/time                    (latency checks)
 *  - GET /api/v3/exchangeInfo            (symbol discovery)
 *  - GET /api/v3/depth?symbol&limit      (order-book snapshots)
 *  - POST /api/v3/order (MARKET)         (live execution, HMAC-signed)
 *  - GET /api/v3/account                 (balance checks for live mode)
 */
class BinanceRestClient(
    private val http: OkHttpClient,
    private val apiKeyProvider: () -> String,
    private val apiSecretProvider: () -> String,
    /**
     * Phase 3 write gate. The centrally stored key is used read-only in paper
     * mode (fees, permissions) and read-write in live mode; the gate is checked
     * before any order request is built, so a paper session cannot place a real
     * order even through a code path bug.
     */
    private val writeAccess: () -> Boolean = { true }
) {

    private val json = Json { ignoreUnknownKeys = true }

    companion object {
        const val BASE_URL = "https://api.binance.com"
        private val VALID_DEPTHS = intArrayOf(5, 10, 20, 50, 100, 500, 1000, 5000)

        /** Port of the Main.js valid-depth selection. */
        fun resolveValidDepth(requested: Int): Int =
            VALID_DEPTHS.firstOrNull { it >= requested } ?: 5000

        fun hmacSha256(key: String, data: String): String {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(key.toByteArray(), "HmacSHA256"))
            val raw = mac.doFinal(data.toByteArray())
            return raw.joinToString("") { "%02x".format(it) }
        }
    }

    // ------------------------------------------------------------------
    // GET /api/v3/time
    // ------------------------------------------------------------------

    suspend fun time(): Long = request("GET", "/api/v3/time", null, signed = false, weight = 1) { body ->
        json.parseToJsonElement(body).jsonObject["serverTime"]?.jsonPrimitive?.long ?: 0L
    }

    /** Latency in ms, port of SpeedTest.ping(). */
    suspend fun ping(): Long {
        val before = System.currentTimeMillis()
        time()
        return System.currentTimeMillis() - before
    }

    // ------------------------------------------------------------------
    // GET /api/v3/exchangeInfo
    // ------------------------------------------------------------------

    suspend fun exchangeInfo(): List<SymbolInfo> = request("GET", "/api/v3/exchangeInfo", null, signed = false, weight = 10) { body ->
        val root = json.parseToJsonElement(body).jsonObject
        root["symbols"]!!.jsonArray.map { el ->
            val obj = el.jsonObject
            val filtersJson = obj["filters"]!!.jsonArray
            val commission = obj["standardCommission"]?.jsonObject
                ?: obj["specialCommission"]?.jsonObject
            SymbolInfo(
                symbol = obj["symbol"]!!.jsonPrimitive.content,
                status = obj["status"]!!.jsonPrimitive.content,
                baseAsset = obj["baseAsset"]!!.jsonPrimitive.content,
                quoteAsset = obj["quoteAsset"]!!.jsonPrimitive.content,
                filters = filtersJson.map { f ->
                    val fo = f.jsonObject
                    SymbolFilter(
                        filterType = fo["filterType"]!!.jsonPrimitive.content,
                        minQty = fo["minQty"]?.jsonPrimitive?.content,
                        stepSize = fo["stepSize"]?.jsonPrimitive?.content,
                        tickSize = fo["tickSize"]?.jsonPrimitive?.content,
                        minNotional = fo["minNotional"]?.jsonPrimitive?.content
                            ?: fo["notional"]?.jsonPrimitive?.content
                    )
                },
                dustDecimals = 0,
                takerCommission = commission?.get("taker")
                    ?.jsonPrimitive?.content?.toDoubleOrNull()
            )
        }
    }

    // ------------------------------------------------------------------
    // GET /api/v3/depth
    // ------------------------------------------------------------------

    data class RestDepth(val lastUpdateId: Long, val bids: Map<Double, Double>, val asks: Map<Double, Double>)

    suspend fun depth(symbol: String, limit: Int): RestDepth = request("GET", "/api/v3/depth", mapOf("symbol" to symbol, "limit" to limit.toString()), signed = false, weight = 1) { body ->
        val obj = json.parseToJsonElement(body).jsonObject
        RestDepth(
            lastUpdateId = obj["lastUpdateId"]!!.jsonPrimitive.long,
            bids = parseLevels(obj["bids"]!!.jsonArray.map { it.jsonArray }),
            asks = parseLevels(obj["asks"]!!.jsonArray.map { it.jsonArray })
        )
    }

    private fun parseLevels(rows: List<List<kotlinx.serialization.json.JsonElement>>): Map<Double, Double> {
        val out = LinkedHashMap<Double, Double>()
        for (row in rows) {
            val price = row[0].jsonPrimitive.double
            val qty = row[1].jsonPrimitive.double
            out[price] = qty
        }
        return out
    }

    // ------------------------------------------------------------------
    // POST /api/v3/order  (MARKET, signed) - LIVE TRADING ONLY
    // ------------------------------------------------------------------

    /**
     * Server-time offset (ms) applied to every signed request timestamp.
     *
     * Signed endpoints are validated against Binance's clock with a 5s
     * recvWindow, and phones drift; a device running more than a second slow
     * gets -1021 "Timestamp for this request is outside of the recvWindow"
     * on every order, which in this engine happens mid-triangle. The
     * scanner already measures the skew for age math; the same measurement
     * belongs on the wire. 0 before the first sync, which is correct for a
     * device whose clock agrees with Binance.
     */
    @Volatile var serverTimeOffsetMs: Long = 0L

    fun updateServerTimeOffset(serverTimeMs: Long, localTimeAtReceiveMs: Long) {
        // Half the round trip is network; the true offset at send time sits
        // between (server - localAtReceive) and (server - localAtSend).
        val measured = serverTimeMs - localTimeAtReceiveMs
        serverTimeOffsetMs = if (serverTimeOffsetMs == 0L) measured
        else (serverTimeOffsetMs * 3 + measured) / 4
    }

    /** Binance-clock timestamp for a request being sent right now. */
    fun signedTimestamp(): Long = System.currentTimeMillis() + serverTimeOffsetMs

    suspend fun marketOrder(
        ticker: String,
        quantity: Double,
        side: String,
        quoteOrderQty: Double? = null,
        newClientOrderId: String? = null
    ): OrderResponse {
        // Phase 3: checked before the request is even assembled - the failure
        // must be a local, logged refusal, never a network round trip.
        check(writeAccess()) {
            "Order placement refused: the active trading mode grants read-only " +
                "access to the stored Binance key"
        }
        // The signature must cover the exact query string that is sent,
        // so the URL is assembled from the signed string directly.
        // (All values used here are URL-safe: symbols, decimal numbers, digits.)
        val canonical = buildString {
            append("symbol=").append(ticker)
            append("&side=").append(side)
            if (newClientOrderId != null) {
                append("&newClientOrderId=").append(newClientOrderId)
            }
            if (quoteOrderQty != null) append("&quoteOrderQty=").append(quoteOrderQty.toBigDecimal().toPlainString())
            else append("&quantity=").append(quantity.toBigDecimal().toPlainString())
            append("&type=MARKET")
            append("&timestamp=").append(signedTimestamp())
            append("&recvWindow=5000")
        }
        val signature = hmacSha256(apiSecretProvider(), canonical)
        val url = "$BASE_URL/api/v3/order?$canonical&signature=$signature"

        val request = Request.Builder()
            .url(url)
            .header("X-MBX-APIKEY", apiKeyProvider())
            .post(okhttp3.RequestBody.create(null, ByteArray(0)))
            .build()

        http.newCall(request).execute().use { resp ->
            val bodyText = resp.body?.string() ?: "{}"
            return parseOrderResponse(bodyText, resp.code)
        }
    }

    private fun parseOrderResponse(bodyText: String, httpCode: Int): OrderResponse {
        val obj = runCatching { json.parseToJsonElement(bodyText).jsonObject }
            .getOrNull() ?: throw BinanceApiException(httpCode, bodyText.take(200))
        if (obj.containsKey("code") && obj.containsKey("msg") && !obj.containsKey("orderId")) {
            throw BinanceApiException(
                obj["code"]!!.jsonPrimitive.int_or_zero(),
                obj["msg"]!!.jsonPrimitive.content
            )
        }
        return OrderResponse(
            orderId = obj["orderId"]?.jsonPrimitive?.long,
            executedQty = obj["executedQty"]?.jsonPrimitive?.double ?: 0.0,
            cummulativeQuoteQty = obj["cummulativeQuoteQty"]?.jsonPrimitive?.double ?: 0.0,
            fills = obj["fills"]?.jsonArray?.map { f ->
                val fo = f.jsonObject
                OrderFill(
                    price = fo["price"]!!.jsonPrimitive.double,
                    qty = fo["qty"]!!.jsonPrimitive.double,
                    commission = fo["commission"]!!.jsonPrimitive.double,
                    commissionAsset = fo["commissionAsset"]!!.jsonPrimitive.content
                )
            } ?: emptyList()
        )
    }

    private fun kotlinx.serialization.json.JsonPrimitive.int_or_zero(): Int =
        this.content.toIntOrNull() ?: 0

    // ------------------------------------------------------------------
    // GET /api/v3/account (signed) - used for live balance checks
    // ------------------------------------------------------------------

    data class AssetBalance(val asset: String, val free: Double, val locked: Double)

    /**
     * GET /api/v3/order (signed): the state of an order by clientOrderId.
     *
     * This is the documented recovery path for a lost order response: the
     * request may have been filled even though the answer never arrived, and
     * the only way to tell is to ask by the id we chose before sending.
     * Returns null when Binance has no record of the id, i.e. the order
     * genuinely never landed.
     */
    suspend fun queryOrder(ticker: String, origClientOrderId: String): OrderResponse? {
        val canonical = buildString {
            append("symbol=").append(ticker)
            append("&origClientOrderId=").append(origClientOrderId)
            append("&timestamp=").append(signedTimestamp())
            append("&recvWindow=5000")
        }
        val signature = hmacSha256(apiSecretProvider(), canonical)
        val url = "$BASE_URL/api/v3/order?$canonical&signature=$signature"
        val request = Request.Builder().url(url).header("X-MBX-APIKEY", apiKeyProvider()).build()
        http.newCall(request).execute().use { resp ->
            val bodyText = resp.body?.string() ?: "{}"
            if (resp.code == 400) return null // -2013 "Order does not exist"
            val parsed = parseOrderResponse(bodyText, resp.code)
            return if (parsed.orderId == null) null else parsed
        }
    }

    suspend fun accountBalances(): Map<String, AssetBalance> {
        val canonical = "timestamp=" + signedTimestamp() + "&recvWindow=5000"
        val signature = hmacSha256(apiSecretProvider(), canonical)
        val url = "$BASE_URL/api/v3/account?$canonical&signature=$signature"
        val request = Request.Builder().url(url).header("X-MBX-APIKEY", apiKeyProvider()).build()
        http.newCall(request).execute().use { resp ->
            val bodyText = resp.body?.string() ?: "{}"
            val obj = json.parseToJsonElement(bodyText).jsonObject
            if (!resp.isSuccessful || !obj.containsKey("balances")) {
                throw BinanceApiException(resp.code, obj["msg"]?.jsonPrimitive?.content ?: bodyText.take(200))
            }
            val out = LinkedHashMap<String, AssetBalance>()
            for (b in obj["balances"]!!.jsonArray) {
                val bo = b.jsonObject
                out[bo["asset"]!!.jsonPrimitive.content] = AssetBalance(
                    asset = bo["asset"]!!.jsonPrimitive.content,
                    free = bo["free"]!!.jsonPrimitive.double,
                    locked = bo["locked"]!!.jsonPrimitive.double
                )
            }
            return out
        }
    }

    // ------------------------------------------------------------------
    // GET /sapi/v1/account/apiRestrictions (signed) - what the key may do
    // ------------------------------------------------------------------

    data class ApiKeyRestrictions(val canRead: Boolean, val canTrade: Boolean)

    /**
     * The capabilities Binance itself enforces on this key. The app cannot
     * change them; it can only detect them - and refusing to arm live trading
     * with a key whose spot-trading flag is off is exactly the failure the
     * confirmation phrase cannot catch.
     */
    suspend fun apiKeyRestrictions(): ApiKeyRestrictions {
        val canonical = "timestamp=" + signedTimestamp() + "&recvWindow=5000"
        val signature = hmacSha256(apiSecretProvider(), canonical)
        val url = "$BASE_URL/sapi/v1/account/apiRestrictions?$canonical&signature=$signature"
        val request = Request.Builder().url(url).header("X-MBX-APIKEY", apiKeyProvider()).build()
        http.newCall(request).execute().use { resp ->
            val bodyText = resp.body?.string() ?: "{}"
            if (!resp.isSuccessful) throw BinanceApiException(resp.code, bodyText.take(200))
            return parseApiRestrictions(bodyText)
        }
    }

    internal fun parseApiRestrictions(bodyText: String): ApiKeyRestrictions {
        val obj = runCatching { json.parseToJsonElement(bodyText).jsonObject }.getOrNull()
            ?: throw BinanceApiException(0, bodyText.take(200))
        // Some deployments wrap the object in "data", some return it bare.
        val payload = obj["data"]?.jsonObject ?: obj
        fun flag(name: String): Boolean? =
            payload[name]?.jsonPrimitive?.let { p ->
                p.content.toBooleanStrictOrNull()
            }
        // Absent fields must not block anything: only an EXPLICIT false from
        // Binance refuses live trading - the confirmation phrase remains the
        // primary gate.
        return ApiKeyRestrictions(
            canRead = flag("canRead") ?: true,
            canTrade = flag("canTrade") ?: true
        )
    }

    // ------------------------------------------------------------------
    // GET /sapi/v1/asset/tradeFee (signed) - the account's real commission
    // ------------------------------------------------------------------

    /**
     * The spot account's effective taker commission as a fraction of notional
     * (0.001 == 0.10%), or null when no API key is configured or no row
     * covers the symbol. This is the rate the profit projections deserve:
     * exchangeInfo publishes no commission, so without it the engine can only
     * guess - and the BNB-discount 0.075% vs the flat 0.10% guess is exactly
     * the margin a marginal triangle lives or dies on.
     */
    suspend fun accountTakerCommission(symbol: String = "BTCUSDT"): Double? {
        if (apiKeyProvider().isBlank() || apiSecretProvider().isBlank()) return null
        val canonical = buildString {
            append("symbol=").append(symbol)
            append("&timestamp=").append(signedTimestamp())
            append("&recvWindow=5000")
        }
        val signature = hmacSha256(apiSecretProvider(), canonical)
        val url = "$BASE_URL/sapi/v1/asset/tradeFee?$canonical&signature=$signature"
        val request = Request.Builder().url(url).header("X-MBX-APIKEY", apiKeyProvider()).build()
        http.newCall(request).execute().use { resp ->
            val bodyText = resp.body?.string() ?: return null
            if (!resp.isSuccessful) return null
            val obj = runCatching { json.parseToJsonElement(bodyText).jsonObject }.getOrNull()
                ?: return null
            val rows = obj["tradeFee"]?.jsonArray ?: return null
            for (row in rows) {
                val ro = row.jsonObject
                if (ro["symbol"]?.jsonPrimitive?.content == symbol) {
                    return ro["takerCommission"]?.jsonPrimitive?.content?.toDoubleOrNull()
                }
            }
            return null
        }
    }

    // ------------------------------------------------------------------
    // Internal request helper
    // ------------------------------------------------------------------

    private suspend fun <T> request(
        method: String,
        path: String,
        params: Map<String, String>?,
        signed: Boolean,
        weight: Int,
        parse: (String) -> T
    ): T = withContext(Dispatchers.IO) {
        rateLimiter.acquire(weight)
        val urlBuilder = "$BASE_URL$path".toHttpUrl().newBuilder()
        params?.forEach { (k, v) -> urlBuilder.addQueryParameter(k, v) }
        val req = Request.Builder().url(urlBuilder.build()).get().build()
        http.newCall(req).execute().use { resp ->
            val bodyText = resp.body?.string() ?: ""
            if (!resp.isSuccessful) {
                // Binance puts its own diagnostic code in the body (-1021 for a
                // clock that drifted, -2015 for a rejected key). Passing the
                // HTTP status instead collapsed every signed-request failure
                // onto 400/401 and threw away the one number that identifies it.
                val parsed = runCatching { json.parseToJsonElement(bodyText).jsonObject }.getOrNull()
                val bodyCode = parsed?.get("code")?.jsonPrimitive?.content?.toIntOrNull()
                val msg = parsed?.get("msg")?.jsonPrimitive?.content ?: bodyText.take(200)
                val code = bodyCode ?: resp.code
                throw BinanceApiException(code, "[HTTP ${resp.code}] $msg")
            }
            parse(bodyText)
        }
    }

    private val rateLimiter = RateLimiter()
}
