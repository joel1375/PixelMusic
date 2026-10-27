package com.unshoo.pixelmusic.data.spotify

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SpotifyPlaybackOwnershipTest {
    private val queue = setOf("spotify:track:one", "spotify:track:two")
    @Test fun ownsAdvancingTracksInSelectedQueue() {
        assertTrue(ownsSpotifyPlayback("phone", queue, "phone", "spotify:track:one"))
        assertTrue(ownsSpotifyPlayback("phone", queue, "phone", "spotify:track:two"))
    }
    @Test fun doesNotPauseAnotherDeviceOrAnotherAppsTrack() {
        assertFalse(ownsSpotifyPlayback("phone", queue, "speaker", "spotify:track:one"))
        assertFalse(ownsSpotifyPlayback("phone", queue, "phone", "spotify:track:other"))
    }
    @Test fun ignoresMissingOrEmptySessions() {
        assertFalse(ownsSpotifyPlayback("", queue, "", "spotify:track:one"))
        assertFalse(ownsSpotifyPlayback("phone", queue, null, null))
        assertFalse(ownsSpotifyPlayback("phone", emptySet(), "phone", "spotify:track:one"))
    }
}
