package com.unshoo.pixelmusic.spotify
import org.junit.Assert.*
import org.junit.Test
class SpotifyPlaybackOwnershipTest {
    @Test fun onlyOwnedQueueCanBeStopped() {
        val queue=setOf("spotify:track:a","spotify:track:b")
        assertTrue(ownsSpotifyPlayback("phone",queue,"phone","spotify:track:b"))
        assertFalse(ownsSpotifyPlayback("phone",queue,"other","spotify:track:a"))
        assertFalse(ownsSpotifyPlayback("phone",queue,"phone","spotify:track:unrelated"))
        assertFalse(ownsSpotifyPlayback("",queue,"","spotify:track:a"))
        assertFalse(ownsSpotifyPlayback("phone",queue,null,null))
    }
}
