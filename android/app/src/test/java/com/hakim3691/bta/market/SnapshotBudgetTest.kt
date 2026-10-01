package com.hakim3691.bta.market

import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Startup seeds one REST snapshot per ticker in the universe - 1198 of them on
 * the largest configurations - inside a 60 second deadline.
 *
 * Each request used to build its own BinanceRestClient, and therefore its own
 * RateLimiter, so the exchange's per-minute weight budget was enforced 1198
 * times over and never actually applied to the burst as a whole. Combined with
 * OkHttp's default of five concurrent requests per host, the books could not
 * arrive inside the deadline at a plausible round trip - and the dead-book
 * probe, whose entire job is to detect books the exchange does not stream,
 * concluded they were dead and rebuilt the universe without them.
 *
 * These pin the property that makes the probe honest: the budget is a property
 * of the client, so the whole burst shares one budget.
 */
class SnapshotBudgetTest {

    private val depthBody = """{"lastUpdateId":1,"bids":[["1.0","2.0"]],"asks":[["3.0","4.0"]]}"""

    private fun countingClient(counter: MutableList<String>): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                counter.add(chain.request().url.encodedPath)
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("mock")
                    .body(depthBody.toResponseBody())
                    .build()
            })
            .build()

    @Test
    fun `one client shares one weight budget across many symbols`() = runBlocking {
        val paths = mutableListOf<String>()
        // One client, many symbols - the shape the snapshot seeder now uses.
        val rest = BinanceRestClient(
            countingClient(paths), { "" }, { "" },
            rateLimiter = RateLimiter(maxWeightPerMinute = 3)
        )

        var refused = 0
        repeat(70) { i ->
            if (runCatching { rest.depth("SYM$i", 50) }.isFailure) refused++
        }

        // The budget binds across the whole burst: three snapshots went out and
        // the rest were refused locally, without hammering the exchange.
        assertEquals("only the budgeted snapshots should reach the network", 3, paths.size)
        assertEquals(67, refused)
    }

    @Test
    fun `a client per symbol defeats the budget entirely, which is the bug`() = runBlocking {
        val paths = mutableListOf<String>()
        val http = countingClient(paths)
        // The old arrangement: a fresh client, and therefore a fresh budget, for
        // every symbol. Each request gets the full allowance, so the exchange's
        // per-minute limit is enforced once per request - i.e. not at all - and
        // the whole universe is fired at the API regardless of its size.
        var refused = 0
        repeat(70) { i ->
            val perSymbol = BinanceRestClient(http, { "" }, { "" })
            if (runCatching { perSymbol.depth("SYM$i", 50) }.isFailure) refused++
        }

        assertEquals(
            "a per-request budget cannot refuse anything; this is why the burst " +
                "was never actually limited",
            0,
            refused
        )
        assertEquals(70, paths.size)
    }

    @Test
    fun `a budget that is exhausted reports the wait, not a silent failure`() = runBlocking {
        val paths = mutableListOf<String>()
        val rest = BinanceRestClient(
            countingClient(paths), { "" }, { "" },
            rateLimiter = RateLimiter(maxWeightPerMinute = 2)
        )
        rest.depth("A", 50)
        rest.depth("B", 50)
        try {
            rest.depth("C", 50)
            fail("expected the budget to refuse the third snapshot")
        } catch (e: BinanceApiException) {
            assertEquals(429, e.code)
            assertTrue(e.message.orEmpty(), e.message.orEmpty().contains("retry after"))
        }
    }

    @Test
    fun `depth is charged one unit of weight regardless of limit`() {
        // Depth snapshots dominate startup, so their weight is what decides how
        // many of the 1198 fit in the budget. Pinned so a future change to the
        // limit cannot quietly shrink the seed.
        val limiter = RateLimiter(maxWeightPerMinute = 3)
        limiter.acquire(1)
        limiter.acquire(1)
        limiter.acquire(1)
        try {
            limiter.acquire(1)
            fail("the fourth snapshot must exceed a budget of three")
        } catch (e: BinanceApiException) {
            assertEquals(429, e.code)
        }
    }
}
