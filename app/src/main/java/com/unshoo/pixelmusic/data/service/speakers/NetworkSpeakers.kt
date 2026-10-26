package com.unshoo.pixelmusic.data.service.speakers

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import com.airsonic.sender.api.AirDevice
import com.airsonic.sender.api.DeviceListener
import com.airsonic.sender.api.DeviceType
import com.airsonic.sender.discovery.AirplayDiscovery
import com.airsonic.sender.discovery.DlnaDiscovery
import com.airsonic.sender.dlna.DlnaController
import com.airsonic.sender.dlna.buildLiveWavDidl
import com.airsonic.sender.dlna.probeSonos
import com.airsonic.sender.pairing.PairingHandshake
import com.airsonic.sender.streaming.AirplayStreamSession
import com.airsonic.sender.streaming.LiveAudioHttpServer
import com.airsonic.sender.streaming.wavStreamHeader
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

object NetworkSpeakers {
    data class Connection(val device: AirDevice, val connected: Boolean = false, val volume: Float = 1f)
    data class PinRequest(val id: String, val name: String)
    private val lock = Any()
    private val found = linkedMapOf<String, AirDevice>()
    private val workers = linkedMapOf<String, Worker>()
    private val _devices = MutableStateFlow<List<AirDevice>>(emptyList())
    val devices = _devices.asStateFlow()
    private val _connections = MutableStateFlow<List<Connection>>(emptyList())
    val connections = _connections.asStateFlow()
    private val _routing = MutableStateFlow(false)
    val routing = _routing.asStateFlow()
    @Volatile var sourcePlaying = false
    val error = MutableStateFlow<String?>(null)
    val pinRequest = MutableStateFlow<PinRequest?>(null)
    private var airplay: AirplayDiscovery? = null
    private var dlna: DlnaDiscovery? = null
    private var app: Context? = null

    fun discover(context: Context) {
        app = context.applicationContext
        if (airplay != null) return
        error.value = null
        val listener = object : DeviceListener {
            override fun onDeviceFound(device: AirDevice) = synchronized(lock) {
                found[device.id] = device
                // Prefer the Sonos/DLNA endpoint where a device advertises both protocols.
                _devices.value = found.values.groupBy { it.host }.values.map { entries ->
                    entries.firstOrNull { it.controlUrl != null } ?: entries.first()
                }.sortedBy { it.name.lowercase() }
            }
            override fun onDeviceLost(device: AirDevice) = synchronized(lock) {
                found.remove(device.id)
                _devices.value = _devices.value.filterNot { it.id == device.id }
            }
            override fun onDiscoveryFailed(reason: String) { error.value = "Speaker discovery failed. Check Wi-Fi and try Scan again." }
        }
        airplay = AirplayDiscovery(context).also { it.start(listener) }
        dlna = DlnaDiscovery(context).also { it.start(listener) }
    }

    fun stopDiscovery() {
        airplay?.stop(); dlna?.stop()
        airplay = null; dlna = null
    }

    fun refresh(context: Context) { stopDiscovery(); synchronized(lock) { found.clear(); _devices.value = emptyList() }; discover(context) }

    fun connect(context: Context, device: AirDevice) {
        val application = context.applicationContext
        app = application
        synchronized(lock) {
            if (workers.containsKey(device.host)) return
            // PIN prompts are serialized to keep pairing identities and requests unambiguous.
            if (workers.values.any { !it.ready }) {
                error.value = "Wait for the current speaker to connect first."
                return
            }
            error.value = null
            val worker = Worker(application, device)
            workers[device.host] = worker
            publish()
            try {
                application.startForegroundService(Intent(application, SpeakerStreamService::class.java))
                thread(name = "pixelmusic-speaker", isDaemon = true) { worker.run() }
            } catch (e: Exception) {
                workers.remove(device.host); publish()
                error.value = "Could not start speaker playback: ${e.message}"
            }
        }
    }

    fun disconnect(host: String) = synchronized(lock) {
        workers.remove(host)?.cancel()
        publish()
    }

    fun disconnectAll() = synchronized(lock) {
        workers.values.forEach { it.cancel() }; workers.clear(); publish()
    }

    fun setVolume(host: String, value: Float) = synchronized(lock) {
        workers[host]?.gain = value.coerceIn(0f, 1f)
        publish()
    }

    fun submitPin(value: String?) = synchronized(lock) {
        pinRequest.value?.let { workers[it.id]?.pin?.offer(value ?: "") }
    }

    fun offer(pcm: ByteArray) = synchronized(lock) {
        workers.values.filter { it.ready }.forEach { it.queue.write(pcm) }
    }

    fun clearAudio() = synchronized(lock) { workers.values.forEach { it.queue.clear() } }

    private fun publish() {
        _connections.value = workers.values.map { Connection(it.device, it.ready, it.gain) }
        _routing.value = workers.values.any { it.ready }
        if (workers.isEmpty()) app?.stopService(Intent(app, SpeakerStreamService::class.java))
    }

    private class Worker(val context: Context, val device: AirDevice) {
        val stopped = AtomicBoolean(false)
        val queue = PcmQueue()
        val pin = LinkedBlockingQueue<String>(1)
        @Volatile var gain = 1f
        @Volatile var ready = false
        @Volatile var handshake: PairingHandshake? = null
        private var nextTick = 0L
        fun cancel() { stopped.set(true); pin.offer(""); thread(isDaemon = true) { handshake?.httpClient?.close() } }
        private fun checkRunning() { check(!stopped.get()) { "Disconnected" } }
        private fun connected() = synchronized(lock) {
            checkRunning()
            ready = true; queue.clear(); publish()
        }
        private fun chunk(): ByteArray? {
            if (stopped.get()) return null
            if (nextTick == 0L) nextTick = SystemClock.elapsedRealtimeNanos()
            nextTick += 10_000_000L
            val wait = nextTick - SystemClock.elapsedRealtimeNanos()
            if (wait > 0) TimeUnit.NANOSECONDS.sleep(wait)
            else if (wait < -100_000_000L) { nextTick = SystemClock.elapsedRealtimeNanos(); queue.clear() }
            if (!sourcePlaying) queue.clear()
            val pcm = queue.read(1764) // 441 frames, stereo, signed 16-bit LE, every 10 ms.
            val volume = gain
            if (volume < 1f) for (i in pcm.indices step 2) {
                val value = ((pcm[i].toInt() and 255) or (pcm[i + 1].toInt() shl 8)).toShort()
                val scaled = (value * volume).toInt()
                pcm[i] = scaled.toByte(); pcm[i + 1] = (scaled shr 8).toByte()
            }
            return pcm
        }
        fun run() {
            try {
                val address = InetAddress.getByName(device.host)
                require(address.isSiteLocalAddress || address.isLinkLocalAddress) { "Speaker must be on your local network" }
                val sonos = if (device.type in listOf(DeviceType.SONOS, DeviceType.UNKNOWN, DeviceType.DLNA)) probeSonos(device.host) else null
                checkRunning()
                val control = sonos ?: device.controlUrl
                if (control != null) streamSonos(control) else streamAirplay()
            } catch (e: Exception) {
                if (!stopped.get()) error.value = "${device.name}: ${e.message ?: "Connection failed"}"
            } finally {
                stopped.set(true)
                handshake?.httpClient?.close()
                synchronized(lock) {
                    if (pinRequest.value?.id == device.host) pinRequest.value = null
                    if (workers[device.host] === this) workers.remove(device.host)
                    publish()
                }
            }
        }
        private fun streamSonos(control: String) {
            val localIp = DatagramSocket().use { socket ->
                socket.connect(InetAddress.getByName(device.host), device.port)
                socket.localAddress.hostAddress ?: kotlin.error("No Wi-Fi address")
            }
            val server = LiveAudioHttpServer("audio/wav", "wav", 0xFFFFFFFFL, wavStreamHeader(44100, 2))
            val controller = DlnaController(control)
            var pump: Thread? = null
            var changedUri = false
            try {
                val port = server.start()
                val uri = "http://$localIp:$port${server.path}"
                check(controller.setUri(uri, buildLiveWavDidl("PixelMusic", uri))) { controller.lastError }
                changedUri = true
                checkRunning()
                pump = thread(name = "pixelmusic-sonos-audio", isDaemon = true) {
                    try { while (!stopped.get()) server.push(chunk() ?: break) }
                    catch (_: InterruptedException) { }
                }
                check(controller.play()) { controller.lastError }
                // A SOAP success alone does not prove that the speaker fetched audio.
                val deadline = SystemClock.elapsedRealtime() + 10000
                while (server.connections == 0 && SystemClock.elapsedRealtime() < deadline) { checkRunning(); Thread.sleep(100) }
                check(server.connections > 0) { "Speaker could not fetch audio. Check that both devices are on the same Wi-Fi." }
                connected()
                while (!stopped.get()) {
                    Thread.sleep(3000)
                    val state = controller.getTransportInfo()
                    check(state != null && state != "STOPPED" && state != "NO_MEDIA_PRESENT") { "Speaker stopped responding" }
                }
            } finally {
                stopped.set(true); pump?.interrupt(); pump?.join(1000); server.stop()
                if (changedUri && synchronized(lock) { workers[device.host] == null || workers[device.host] === this }) runCatching { controller.stop() }
            }
        }
        private fun newHandshake(): PairingHandshake {
            return PairingHandshake(device.host, device.port, PairingStore.pairingId(context), PairingStore.ltSeed(context)).also { handshake = it }
        }
        private fun pair(): PairingHandshake {
            if (PairingStore.isPaired(context, device.host)) {
                val known = newHandshake()
                known.knownAccessoryLtpk = PairingStore.accessoryLtpk(context, device.host)
                if (known.pairVerify {}) return known
                known.httpClient.close(); PairingStore.unpair(context, device.host)
            }
            checkRunning()
            val transient = newHandshake()
            if (transient.pairSetup("3939", onStep = {}, transient = true)) return transient
            transient.httpClient.close()
            checkRunning()
            val setup = newHandshake()
            val split = !setup.pairPinStart()
            if (split) check(setup.pairSetupBegin {}) { "Receiver does not support pairing" }
            pinRequest.value = PinRequest(device.host, device.name)
            val code = try { pin.poll(120, TimeUnit.SECONDS) } finally { pinRequest.value = null }
            check(!code.isNullOrBlank()) { "Pairing cancelled or timed out" }
            checkRunning()
            val success = if (split) setup.pairSetupWithPin(code) {} else setup.pairSetup(code, onStep = {}, transient = false)
            check(success) { "Pairing failed. Check the receiver PIN." }
            PairingStore.markPaired(context, device.host)
            setup.lastAccessoryLtpk?.let { PairingStore.saveAccessoryLtpk(context, device.host, it) }
            setup.httpClient.close()
            val verified = newHandshake()
            verified.knownAccessoryLtpk = PairingStore.accessoryLtpk(context, device.host)
            check(verified.pairVerify {}) { "Could not verify receiver" }
            return verified
        }
        private fun streamAirplay() {
            val paired = pair()
            val session = AirplayStreamSession(device.host, paired)
            try {
                val result = session.setup(useAlac = true) {} ?: kotlin.error("AirPlay setup failed")
                connected()
                val ok = session.streamCapturedPcm(result, channels = 2, isCancelled = { stopped.get() }, nextChunk = { chunk() }) {}
                check(ok || stopped.get()) { "AirPlay stream failed" }
            } finally { session.close(); paired.httpClient.close() }
        }
    }
}
