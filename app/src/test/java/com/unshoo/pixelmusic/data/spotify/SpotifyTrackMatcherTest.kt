package com.unshoo.pixelmusic.data.spotify

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SpotifyTrackMatcherTest {
    private val track = SpotifyTrack("Déjà Vu", "Beyoncé", 240_000)
    @Test fun acceptsAccentAndPunctuationDifferences() {
        assertTrue(spotifyTrackMatches(track, "Deja-Vu", "BEYONCE", 245_000))
    }
    @Test fun rejectsDifferentArtistOrVersion() {
        assertFalse(spotifyTrackMatches(track, "Deja Vu", "Someone else", 240_000))
        assertFalse(spotifyTrackMatches(track, "Deja Vu Live", "Beyonce", 240_000))
        assertFalse(spotifyTrackMatches(track, "Deja Vu", "Beyonce", 300_000))
    }
    @Test fun supportsUnknownDuration() {
        assertTrue(spotifyTrackMatches(track, "Deja Vu", "Beyonce", 0))
    }
}
