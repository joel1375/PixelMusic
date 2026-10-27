package com.unshoo.pixelmusic.data.discovery

internal object HourlyDiscoveryPolicy {
    private const val HOUR = 3_600_000L
    fun isDue(now: Long, cachedAt: Long, empty: Boolean): Boolean =
        empty || cachedAt <= 0 || now < cachedAt || now / HOUR != cachedAt / HOUR
}
