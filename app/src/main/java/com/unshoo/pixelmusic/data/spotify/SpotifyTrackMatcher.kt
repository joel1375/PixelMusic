package com.unshoo.pixelmusic.data.spotify

import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs

internal fun spotifyTrackMatches(source: SpotifyTrack, title: String, artist: String, duration: Long): Boolean {
    fun normalize(text: String) = Normalizer.normalize(text, Normalizer.Form.NFKD)
        .replace(Regex("\\p{M}"), "").lowercase(Locale.ROOT)
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
    return normalize(source.title) == normalize(title) && normalize(source.artist) == normalize(artist) &&
        (source.duration <= 0 || duration <= 0 || abs(source.duration - duration) <= 15_000)
}
