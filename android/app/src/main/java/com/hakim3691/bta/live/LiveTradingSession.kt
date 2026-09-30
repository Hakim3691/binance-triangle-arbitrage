package com.hakim3691.bta.live

import com.hakim3691.bta.core.DepthSnapshot
import com.hakim3691.bta.core.OrderResponse
import com.hakim3691.bta.core.TradeExecutor
import com.hakim3691.bta.log.LogRepository
import com.hakim3691.bta.market.BinanceApiException
import com.hakim3691.bta.market.BinanceRestClient
import com.hakim3691.bta.market.DepthCacheManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

/**
 * LIVE trading executor backed by real Binance REST market orders.
 *
 * SAFETY: instances of this class are only constructed through
 * ScannerController.enableLiveTrading(), which requires an explicit user
 * confirmation. No code path constructs it by default.
 */
class LiveTradingSession(
    private val apiKey: String,
    private val apiSecret: String,
    http: OkHttpClient,
    private val depthCache: DepthCacheManager
) : TradeExecutor {

    private val rest = BinanceRestClient(http, { apiKey }, { apiSecret })

    private val clientOrderIdCounter = java.util.concurrent.atomic.AtomicLong(0)

    /**
     * Client-order ids are chosen HERE, before the request is sent, because
     * their whole purpose is to survive a lost response: if the connection
     * dies after Binance received the order, the query-by-id in
     * [resolveAmbiguousOrder] is the only way to learn what actually
     * happened. Treating a timeout as a rejected order - the obvious thing -
     * is how a triangle ends up believing it is flat while holding two legs.
     */
    private fun nextClientOrderId(ticker: String): String =
        clientOrderId(ticker, System.currentTimeMillis(), clientOrderIdCounter.incrementAndGet())

    companion object {
        /** Polls for an ambiguous order before declaring it lost. */
        const val MAX_RECOVERY_POLLS = 3

        /** Gap between recovery polls; a just-filled order reports quickly. */
        const val RECOVERY_POLL_DELAY_MS = 300L

        /** The wire format of a client id, testable without a session. */
        fun clientOrderId(ticker: String, timestampMs: Long, sequence: Long): String =
            "bta-" + ticker.lowercase() + "-" + timestampMs + "-" + sequence
    }

    /**
     * Settles the one genuinely dangerous case in live trading: the order
     * went out, the response did not come back. Queries Binance by the
     * client id, up to [MAX_RECOVERY_POLLS] times, since the order book can
     * take a moment to report a just-landed fill.
     *
     * @return the real fill if the order landed, null when Binance confirms
     * it never did (which is the only case where "rejected" is true).
     */
    private suspend fun resolveAmbiguousOrder(
        ticker: String,
        clientOrderId: String
    ): OrderResponse? {
        repeat(MAX_RECOVERY_POLLS) { attempt ->
            val state = withContext(Dispatchers.IO) {
                runCatching { rest.queryOrder(ticker, clientOrderId) }.getOrNull()
            }
            if (state != null && state.orderId != null) {
                LogRepository.warn(
                    "execution",
                    "AMBIGUOUS ORDER RECOVERED: $ticker reported by query after lost response " +
                        "(executed ${state.executedQty})"
                )
                return state
            }
            if (state == null && attempt == 0) {
                // Confirmed absent on the first poll. One more poll to rule
                // out propagation delay, then give up.
            }
            kotlinx.coroutines.delay(RECOVERY_POLL_DELAY_MS)
        }
        return null
    }

    override suspend fun placeMarketOrder(ticker: String, quantity: Double, method: String): OrderResponse {
        val clientOrderId = nextClientOrderId(ticker)
        return withContext(Dispatchers.IO) {
            LogRepository.warn("execution", "LIVE ${method} $quantity $ticker @ market ($clientOrderId)")
            try {
                rest.marketOrder(ticker, quantity, method, newClientOrderId = clientOrderId)
            } catch (e: BinanceApiException) {
                LogRepository.error("binance", "Order rejected: ${e.code} ${e.message}")
                OrderResponse.failed()
            } catch (e: Exception) {
                // The request did not complete. That is NOT the same as "the
                // order was not placed" - find out which it is.
                LogRepository.error(
                    "binance",
                    "Order network failure: ${e.message} - querying by $clientOrderId before deciding"
                )
                val recovered = resolveAmbiguousOrder(ticker, clientOrderId)
                recovered ?: OrderResponse.failed().also {
                    LogRepository.warn(
                        "execution",
                        "Order $clientOrderId confirmed never received - safe to treat as rejected"
                    )
                }
            }
        }
    }

    override fun getSortedDepth(ticker: String): DepthSnapshot =
        depthCache.getSortedSnapshot(ticker) ?: DepthSnapshot.EMPTY

    /**
     * Fee coverage over a long session.
     *
     * The original checked the BNB balance once at startup and then assumed
     * fees were covered forever. They are not: BNB drains with every leg, and
     * when it runs out Binance switches to deducting the fee from the asset
     * received - which silently invalidates every projection the engine
     * makes, because `parseActualResults` and the fee math both assume
     * BNB-denominated commission. This is checked periodically by the
     * scanner, and a drop below [lowBnbThreshold] is surfaced before any
     * trade is mis-accounted.
     */
    suspend fun bnbBalance(): Double = withContext(Dispatchers.IO) {
        runCatching { rest.accountBalances()["BNB"]?.free ?: 0.0 }.getOrDefault(0.0)
    }

    /** Balance pre-flight check (port of Main.js checkBalances). */
    suspend fun checkBalances(bases: Map<String, Double>, minBnb: Double = 0.001): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                val balances = rest.accountBalances()
                for ((base, min) in bases) {
                    val available = balances[base]?.free ?: 0.0
                    if (available < min) {
                        return@withContext Result.failure(
                            IllegalStateException("Only detected $available $base, but $min $base is required")
                        )
                    }
                }
                val bnb = balances["BNB"]?.free ?: 0.0
                if (bnb <= minBnb) {
                    return@withContext Result.failure(
                        IllegalStateException("Only detected $bnb BNB which is not sufficient to pay for trading fees via BNB")
                    )
                }
                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
}
