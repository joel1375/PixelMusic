package com.unshoo.pixelmusic.data.service.speakers

/** A bounded PCM16 stereo ring. Never block the player's audio thread. */
internal class PcmQueue(private val capacity: Int = 44100 * 4 * 2) {
    private val bytes = ByteArray(capacity)
    private var head = 0
    private var size = 0

    @Synchronized fun write(input: ByteArray) {
        require(input.size % 4 == 0)
        val start = (input.size - capacity).coerceAtLeast(0)
        val count = input.size - start
        val discard = (size + count - capacity).coerceAtLeast(0)
        head = (head + discard) % capacity
        size -= discard
        for (i in start until input.size) {
            bytes[(head + size) % capacity] = input[i]
            size++
        }
    }

    @Synchronized fun read(count: Int): ByteArray {
        require(count % 4 == 0)
        val output = ByteArray(count)
        val actual = minOf(size, count)
        for (i in 0 until actual) output[i] = bytes[(head + i) % capacity]
        head = (head + actual) % capacity
        size -= actual
        return output // Silence keeps the receiver session alive while paused/buffering.
    }

    @Synchronized fun clear() { head = 0; size = 0 }
}
