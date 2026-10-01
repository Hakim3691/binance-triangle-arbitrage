package com.hakim3691.bta.security

/**
 * Phase 3: the app-controlled permission story around the one stored key.
 *
 * Exactly one Binance API key lives in the encrypted store for the whole app.
 * What the app is allowed to DO with it is decided by the trading mode, never
 * by the caller:
 *
 *  - PAPER: read-only usage. The key signs nothing but fee and permission
 *    reads; order placement is refused by the REST client no matter who asks.
 *  - LIVE: read + write. The same key additionally signs real market orders,
 *    and only after the PaperLiveScreen confirmation phrase - and only when
 *    the key itself is capable of spot trading.
 *
 * The server-side capabilities of the key (canRead / canTrade) are queried
 * from /sapi/v1/account/apiRestrictions so the UI can warn when a key cannot
 * do what the selected mode asks of it - a key created without "Enable Spot
 * Trading" must be caught before the first live triangle, not after.
 */
data class KeyStatus(
    val stored: Boolean,
    val maskedKey: String?,
    /** Server-side key capability; null = unknown (no key or not checked yet). */
    val canRead: Boolean?,
    val canSpotTrade: Boolean?,
    /** Effective account taker fee in percent, from /sapi/v1/asset/tradeFee. */
    val feePercent: Double?,
    val checkedAtMs: Long
)

object KeyPolicy {

    /**
     * A display-safe fragment of the stored key: first and last four
     * characters with the middle masked. Enough to recognise which key is
     * configured, never enough to leak it - and it must never return the
     * unmasked key whatever the input length.
     */
    fun mask(apiKey: String): String? = when {
        apiKey.isBlank() -> null
        apiKey.length <= 8 -> BULLET.repeat(4)
        else -> apiKey.take(4) + BULLET.repeat(4) + apiKey.takeLast(4)
    }

    private const val BULLET = "\u2022"
}
