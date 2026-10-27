package com.unshoo.pixelmusic.spotify
import kotlin.random.Random

data class DailyMix(val number: Int, val subtitle: String, val tracks: List<SpotifyTrack>, val discoveries: Int)
internal object MixBuilder {
    fun build(number: Int, day: Long, artist: String, familiar: List<SpotifyTrack>, discoveries: List<SpotifyTrack>, knownUris: Set<String>, used: Set<String>): DailyMix {
        val random = Random(day xor number.toLong())
        val saved = familiar.filter { it.uri !in used }.distinctBy { it.uri }.shuffled(random).take(15)
        val fresh = discoveries.filter { it.uri !in knownUris && it.uri !in used }.distinctBy { it.uri }.shuffled(random).take(15)
        val tracks = mutableListOf<SpotifyTrack>()
        repeat(maxOf(saved.size, fresh.size)) { i -> saved.getOrNull(i)?.let(tracks::add); fresh.getOrNull(i)?.let(tracks::add) }
        return DailyMix(number, artist, tracks, fresh.size)
    }
}
