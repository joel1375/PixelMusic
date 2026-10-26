package com.unshoo.pixelmusic.data.service.speakers

import android.os.SystemClock
import com.airsonic.sender.api.AirDevice
import com.airsonic.sender.dlna.DlnaController
import com.airsonic.sender.dlna.buildLiveWavDidl
import com.airsonic.sender.streaming.LiveAudioHttpServer
import com.airsonic.sender.streaming.wavStreamHeader
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/** One PCM stream to one coordinator. Sonos owns the clocks of all group members. */
internal class SonosGroupOutput(
    private val onChanged: () -> Unit,
    private val onError: (String) -> Unit,
    private val isPlaying: () -> Boolean
) {
    private val lock = Object()
    private val selected = linkedMapOf<String, AirDevice>()
    private val volumes = mutableMapOf<String, Float>()
    private val pendingVolumes = mutableMapOf<String, Float>()
    private var version = 0L
    private var readyVersion = -1L
    private var failedVersion = -1L
    private var threadStarted = false
    private val queue = PcmQueue()
    fun snapshot(): List<NetworkSpeakers.Connection> = synchronized(lock) {
        selected.values.map { NetworkSpeakers.Connection(it, readyVersion == version, volumes[it.host] ?: 0f, syncedGroup = readyVersion == version, connecting = readyVersion != version && failedVersion != version) }
    }
    fun hasSelection(): Boolean = synchronized(lock) { selected.isNotEmpty() }
    fun contains(host: String): Boolean = synchronized(lock) { selected.containsKey(host) }
    fun connect(device: AirDevice) {
        synchronized(lock) {
            if (selected.putIfAbsent(device.host, device) != null) return
            version++; readyVersion = -1; lock.notifyAll()
            if (!threadStarted) { threadStarted = true; thread(name = "pixelmusic-sonos-group", isDaemon = true) { run() } }
        }
        onChanged()
    }
    fun disconnect(host: String) {
        synchronized(lock) { if (selected.remove(host) == null) return; pendingVolumes.remove(host); version++; readyVersion = -1; lock.notifyAll() }
        onChanged()
    }
    fun disconnectAll() {
        synchronized(lock) { if (selected.isEmpty()) return; selected.clear(); pendingVolumes.clear(); version++; readyVersion = -1; lock.notifyAll() }
        queue.clear(); onChanged()
    }
    fun resync() {
        synchronized(lock) { version++; readyVersion = -1; lock.notifyAll() }
        onChanged()
    }
    fun setVolume(host: String, value: Float) {
        synchronized(lock) {
            if (host !in selected) return
            volumes[host] = value.coerceIn(0f, 1f); pendingVolumes[host] = value.coerceIn(0f, 1f); lock.notifyAll()
        }
        onChanged()
    }
    fun offer(pcm: ByteArray) { queue.write(pcm) }
    fun clearAudio() { queue.clear() }
    private fun current(expected: Long) = synchronized(lock) { version == expected && selected.isNotEmpty() }
    private class Superseded : Exception()
    private fun checkCurrent(expected: Long) { if (!current(expected)) throw Superseded() }

    private fun run() {
        var session: Session? = null
        var processed = -1L
        var lastCheck = 0L
        while (true) {
            val (generation, devices) = synchronized(lock) { version to selected.values.toList() }
            if (generation != processed) {
                session?.close(); session = null
                queue.clear()
                processed = generation
                if (devices.isNotEmpty()) {
                    try {
                        val next = Session(generation, devices)
                        try { next.start() } catch (e: Exception) { next.close(); throw e }
                        session = next
                        synchronized(lock) { if (version == generation) readyVersion = generation }
                        lastCheck = SystemClock.elapsedRealtime()
                    } catch (_: Superseded) { }
                    catch (e: Exception) {
                        synchronized(lock) { if (version == generation) failedVersion = generation }
                        if (current(generation)) onError("Could not synchronize Sonos: ${e.message}. Check Wi-Fi, then tap Re-sync Sonos.")
                    }
                    onChanged()
                }
            }
            val active = session
            if (active != null && current(processed)) {
                val changes = synchronized(lock) { pendingVolumes.toMap().also { pendingVolumes.clear() } }
                changes.forEach { (host, volume) ->
                    try { active.controls[host]?.setVolume(volume) }
                    catch (_: Exception) { onError("Could not change speaker volume. Try again.") }
                }
                if (SystemClock.elapsedRealtime() - lastCheck >= 3000) {
                    lastCheck = SystemClock.elapsedRealtime()
                    try { active.verify() }
                    catch (e: Exception) {
                        synchronized(lock) { readyVersion = -1; if (version == processed) failedVersion = processed }
                        active.close(); session = null
                        if (current(processed)) onError("Sonos group changed or lost connection. Tap Re-sync Sonos to reconnect the selected rooms.")
                        onChanged()
                    }
                }
            }
            synchronized(lock) {
                if (version == processed) lock.wait(if (session == null) 1000 else 100)
            }
        }
    }

    private inner class Session(private val generation: Long, private val devices: List<AirDevice>) {
        val controls = devices.associate { it.host to SonosGroupControl(it.host) }
        private val ids = linkedMapOf<String, String>()
        private val touched = mutableListOf<String>()
        private val stopped = AtomicBoolean(false)
        private val failure = AtomicReference<Throwable?>(null)
        private val leader = devices.first()
        private val controller = DlnaController("http://${leader.host}:1400/MediaRenderer/AVTransport/Control")
        private val server = LiveAudioHttpServer("audio/wav", "wav", 0xFFFFFFFFL, wavStreamHeader(44100, 2))
        private var pump: Thread? = null
        private var streamUri: String? = null
        private var attemptedUri = false
        fun start() {
            devices.forEach { device ->
                checkCurrent(generation)
                ids[device.host] = controls.getValue(device.host).identity()
                val volume = controls.getValue(device.host).volume()
                synchronized(lock) { if (device.host !in pendingVolumes) volumes[device.host] = volume }
            }
            check(ids.values.toSet().size == ids.size) { "Select each Sonos room only once" }
            val before = controls.getValue(leader.host).topology()
            check(ids.values.all { id -> before.any { it.id == id && it.visible } }) {
                "Select visible rooms from the same Sonos system. Stereo pairs and surrounds are one room"
            }
            // Detach selected rooms first, so an existing group cannot accidentally
            // send this stream to rooms the user did not select.
            devices.forEach { device ->
                checkCurrent(generation)
                touched.add(device.host)
                controls.getValue(device.host).standalone()
            }
            devices.drop(1).forEach { device ->
                checkCurrent(generation)
                controls.getValue(device.host).join(ids.getValue(leader.host))
            }
            val deadline = SystemClock.elapsedRealtime() + 8000
            while (true) {
                checkCurrent(generation)
                if (groupVerified()) break
                check(SystemClock.elapsedRealtime() < deadline) { "Sonos did not confirm the selected group" }
                Thread.sleep(250)
            }
            val localIp = DatagramSocket().use { socket ->
                socket.connect(InetAddress.getByName(leader.host), 1400)
                socket.localAddress.hostAddress ?: error("No local Wi-Fi address")
            }
            val port = server.start()
            val uri = "http://$localIp:$port${server.path}"
            streamUri = uri
            queue.clear()
            pump = thread(name = "pixelmusic-sonos-group-audio", isDaemon = true) {
                try {
                    var nextTick = SystemClock.elapsedRealtimeNanos()
                    while (!stopped.get() && current(generation)) {
                        nextTick += 10_000_000L
                        val wait = nextTick - SystemClock.elapsedRealtimeNanos()
                        if (wait > 0) TimeUnit.NANOSECONDS.sleep(wait)
                        else if (wait < -100_000_000L) { queue.clear(); nextTick = SystemClock.elapsedRealtimeNanos() }
                        if (!isPlaying()) queue.clear()
                        // No per-member audio gain/queue: every room receives the
                        // coordinator's same clocked stream. Volume lives on Sonos.
                        server.push(queue.read(1764))
                    }
                } catch (_: InterruptedException) { }
                catch (e: Exception) { failure.set(e) }
            }
            checkCurrent(generation)
            attemptedUri = true
            check(controller.setUri(uri, buildLiveWavDidl("PixelMusic · Sonos group", uri))) { controller.lastError }
            checkCurrent(generation)
            check(controller.play()) { controller.lastError }
            val fetchDeadline = SystemClock.elapsedRealtime() + 10000
            while (server.connections == 0) {
                checkCurrent(generation)
                check(SystemClock.elapsedRealtime() < fetchDeadline) { "Group leader could not fetch audio" }
                Thread.sleep(100)
            }
            checkCurrent(generation)
            verify()
        }
        private fun groupVerified() = SonosGroupXml.verifiedGroup(controls.getValue(leader.host).topology(), ids.getValue(leader.host), ids.values.toSet())
        fun verify() {
            check(failure.get() == null) { "Audio stream stopped" }
            check(groupVerified()) { "Group membership changed" }
            check(controls.getValue(leader.host).currentUri() == streamUri) { "Another app changed playback" }
            check(controller.getTransportInfo() in listOf("PLAYING", "TRANSITIONING")) { "Group playback stopped" }
        }
        fun close() {
            stopped.set(true); pump?.interrupt(); server.stop(); pump?.join(1000)
            // Do not stop or ungroup a different session that another controller
            // has started since we last checked our group.
            val stillOurs = !attemptedUri || runCatching { controls.getValue(leader.host).currentUri() == streamUri }.getOrDefault(false)
            if (stillOurs) {
                if (attemptedUri) runCatching { controller.stop() }
                touched.asReversed().forEach { host -> runCatching { controls.getValue(host).standalone() } }
            }
        }
    }
}
