package com.unshoo.pixelmusic.data.service.speakers

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer

/** Runs after Media3's 44.1 kHz resampler, using only this app's decoded music. */
@UnstableApi
class SpeakerAudioProcessor : BaseAudioProcessor() {
    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT ||
            inputAudioFormat.sampleRate != 44100 || inputAudioFormat.channelCount !in 1..8) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val count = inputBuffer.remaining()
        val output = replaceOutputBuffer(count)
        val source = ByteArray(count)
        inputBuffer.get(source)
        if (NetworkSpeakers.routing.value) {
            val channels = inputAudioFormat.channelCount
            val stereo = if (channels == 2) source else {
                val frames = count / (channels * 2)
                ByteArray(frames * 4).also { result ->
                    for (i in 0 until frames) {
                        val left = i * channels * 2
                        val right = left + if (channels == 1) 0 else 2
                        result[i * 4] = source[left]
                        result[i * 4 + 1] = source[left + 1]
                        result[i * 4 + 2] = source[right]
                        result[i * 4 + 3] = source[right + 1]
                    }
                }
            }
            NetworkSpeakers.offer(stereo)
            // Keep AudioTrack's clock running, without a duplicate output on the phone.
            output.put(ByteArray(count))
        } else output.put(source)
        output.flip()
    }

    override fun onFlush() { NetworkSpeakers.clearAudio() }
}
