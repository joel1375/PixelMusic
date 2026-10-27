package com.unshoo.pixelmusic.spotify
import org.junit.Assert.*
import org.junit.Test
class MixBuilderTest {
    private fun song(id:String)=SpotifyTrack(id,"Artist",1000,"spotify:track:$id")
    @Test fun blendsSavedAndNewWithoutRepeats() {
        val saved=listOf(song("a"),song("b"),song("c"))
        val mix=MixBuilder.build(1,20,"Artist",saved,listOf(song("a"),song("d"),song("d"),song("e")),saved.map{it.uri}.toSet(),setOf("spotify:track:c"))
        assertEquals(4,mix.tracks.size);assertEquals(2,mix.discoveries)
        assertEquals(4,mix.tracks.map{it.uri}.toSet().size)
        assertFalse(mix.tracks.any{it.uri=="spotify:track:c"})
        assertTrue(mix.tracks[0].uri in setOf("spotify:track:a","spotify:track:b"))
        assertTrue(mix.tracks[1].uri in setOf("spotify:track:d","spotify:track:e"))
    }
    @Test fun stableWithinDayAndBounded() {
        val saved=(1..40).map{song("saved$it")};val fresh=(1..40).map{song("new$it")};val known=saved.map{it.uri}.toSet()
        val a=MixBuilder.build(2,123,"Artist",saved,fresh,known,emptySet())
        assertEquals(a,MixBuilder.build(2,123,"Artist",saved,fresh,known,emptySet()))
        assertEquals(30,a.tracks.size);assertEquals(15,a.discoveries)
    }
    @Test fun noInventedDiscoveriesWhenUnavailable() {
        val a=MixBuilder.build(3,1,"Artist",listOf(song("a")),emptyList(),setOf("spotify:track:a"),emptySet())
        assertEquals(1,a.tracks.size);assertEquals(0,a.discoveries)
        assertTrue(MixBuilder.build(1,1,"",emptyList(),emptyList(),emptySet(),emptySet()).tracks.isEmpty())
    }
    @Test fun mixesDoNotRepeatTracks() {
        val songs=(1..40).map{song("s$it")};val known=songs.map{it.uri}.toSet()
        val first=MixBuilder.build(1,1,"",songs,emptyList(),known,emptySet())
        val next=MixBuilder.build(2,1,"",songs,emptyList(),known,first.tracks.map{it.uri}.toSet())
        assertTrue(first.tracks.map{it.uri}.toSet().intersect(next.tracks.map{it.uri}.toSet()).isEmpty())
    }
}
