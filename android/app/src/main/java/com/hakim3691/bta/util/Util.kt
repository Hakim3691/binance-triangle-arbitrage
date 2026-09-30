package com.hakim3691.bta.util

/**
 * Kotlin port of the original `src/main/Util.js`.
 */
object Util {

    @JvmStatic
    fun sum(values: DoubleArray): Double {
        var total = 0.0
        for (v in values) total += v
        return total
    }

    fun sum(values: List<Double>): Double {
        var total = 0.0
        for (v in values) total += v
        return total
    }

    fun average(values: List<Double>): Double =
        if (values.isEmpty()) 0.0 else sum(values) / values.size

    fun millisecondsSince(ms: Long, now: Long = System.currentTimeMillis()): Long = now - ms

    fun secondsSince(ms: Long, now: Long = System.currentTimeMillis()): Double =
        millisecondsSince(ms, now) / 1000.0

    /**
     * Port of Util.prune: keeps the first [threshold] entries (insertion order) of the map.
     */
    fun <K, V> prune(source: Map<K, V>, threshold: Int): Map<K, V> =
        if (threshold >= source.size) source
        else source.entries.take(threshold).associate { it.key to it.value }
}
