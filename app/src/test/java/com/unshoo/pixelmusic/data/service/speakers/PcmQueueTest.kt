package com.unshoo.pixelmusic.data.service.speakers

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Test

class PcmQueueTest {
    @Test fun overflowDropsOldestCompleteFrames() {
        val queue = PcmQueue(8)
        queue.write(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8))
        queue.write(byteArrayOf(9, 10, 11, 12))
        assertArrayEquals(byteArrayOf(5, 6, 7, 8, 9, 10, 11, 12), queue.read(8))
    }
    @Test fun oversizedWriteRetainsNewestFrames() {
        val queue = PcmQueue(8)
        queue.write(ByteArray(12) { it.toByte() })
        assertArrayEquals(ByteArray(8) { (it + 4).toByte() }, queue.read(8))
    }
    @Test fun underrunAndClearProduceSilence() {
        val queue = PcmQueue(8)
        queue.write(byteArrayOf(1, 2, 3, 4))
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 0, 0, 0, 0), queue.read(8))
        queue.write(byteArrayOf(1, 2, 3, 4)); queue.clear()
        assertArrayEquals(ByteArray(8), queue.read(8))
    }
}
