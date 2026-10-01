package com.hakim3691.bta.market

import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

/**
 * The signed REST endpoints were called straight from the UI thread.
 *
 * OkHttp refuses to execute on Android's main thread, so "save key" and
 * "verify key" both failed with NetworkOnMainThreadException on every signed
 * request - while the scanner's unsigned calls, which already switched
 * dispatchers, worked fine. The asymmetry is what made it so confusing:
 * market data arrived, and nothing that needed the key ever did.
 *
 * An interceptor observes the thread the call actually executes on and aborts
 * before any network is attempted, so this needs no server and no network.
 */
class SignedCallThreadingTest {

    private val executedOn = AtomicReference<String>()

    /** Captures the executing thread, then fails the call so no I/O happens. */
    private fun recordingClient(): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(Interceptor { _: Interceptor.Chain ->
            executedOn.set(Thread.currentThread().name)
            throw IOException("aborted after thread capture")
        })
        .build()

    private fun client() = BinanceRestClient(recordingClient(), { "key" }, { "secret" })

    /** A dispatcher standing in for Android's main thread. */
    private fun mainLike() =
        Executors.newSingleThreadExecutor { r -> Thread(r, "main-thread-sim") }
            .asCoroutineDispatcher()

    private fun assertNotOnCaller(name: String) {
        val thread = executedOn.get()
        assertTrue("$name never executed", thread != null)
        assertFalse(
            "$name executed on the calling thread ($thread) instead of an IO dispatcher",
            thread == "main-thread-sim"
        )
    }

    @Test
    fun `restrictions check does not run on the calling thread`() = runBlocking {
        val dispatcher = mainLike()
        try {
            runCatching { with(dispatcher) { client().apiKeyRestrictions() } }
            assertNotOnCaller("apiKeyRestrictions")
        } finally {
            dispatcher.close()
        }
    }

    @Test
    fun `fee lookup does not run on the calling thread`() = runBlocking {
        val dispatcher = mainLike()
        try {
            runCatching { with(dispatcher) { client().accountTakerCommission() } }
            assertNotOnCaller("accountTakerCommission")
        } finally {
            dispatcher.close()
        }
    }

    @Test
    fun `balance lookup does not run on the calling thread`() = runBlocking {
        val dispatcher = mainLike()
        try {
            runCatching { with(dispatcher) { client().accountBalances() } }
            assertNotOnCaller("accountBalances")
        } finally {
            dispatcher.close()
        }
    }

    @Test
    fun `order placement does not run on the calling thread`() = runBlocking {
        val dispatcher = mainLike()
        try {
            runCatching { with(dispatcher) { client().marketOrder("BTCUSDT", 0.001, "BUY") } }
            assertNotOnCaller("marketOrder")
        } finally {
            dispatcher.close()
        }
    }

    @Test
    fun `order lookup does not run on the calling thread`() = runBlocking {
        val dispatcher = mainLike()
        try {
            runCatching { with(dispatcher) { client().queryOrder("BTCUSDT", "probe-id") } }
            assertNotOnCaller("queryOrder")
        } finally {
            dispatcher.close()
        }
    }

    @Test
    fun `the captured thread is an io dispatcher, not merely another thread`() {
        // Guards against a fix that only moved the call off Main onto some
        // other equally unsuitable thread.
        runBlocking {
            runCatching { client().apiKeyRestrictions() }
        }
        val thread = executedOn.get()!!
        assertTrue("expected an IO dispatcher thread, got $thread", thread.contains("DefaultDispatcher"))
    }
}
