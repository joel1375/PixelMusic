package com.unshoo.pixelmusic.data.spotify

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject

data class SpotifyRemoteState(val title: String = "", val artist: String = "", val device: String = "",
    val playing: Boolean = false, val busy: Boolean = false, val error: String? = null)

/** Spotify owns the audio. This service only manages explicitly requested remote playback. */
@AndroidEntryPoint
class SpotifyPlaybackService : Service() {
    @Inject lateinit var spotify: SpotifyClient
    companion object { val state = MutableStateFlow(SpotifyRemoteState()) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val commands = Channel<Intent>(Channel.UNLIMITED)
    private var deviceId = ""
    private var ownedUris = emptySet<String>()
    private var closing = false
    private var mismatchCount = 0
    private var pollAfter = 0L
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("spotify_playback", "Spotify playback", NotificationManager.IMPORTANCE_LOW))
        updateNotification()
        scope.launch {
            for (intent in commands) {
                if (closing) continue
                state.value = state.value.copy(busy = true, error = null)
                try {
                    when (intent.action) {
                        "play" -> {
                            val target = intent.getStringExtra("device") ?: error("Choose a Spotify device")
                            val uris = intent.getStringArrayListExtra("uris").orEmpty()
                            require(uris.isNotEmpty() && uris.all { it.matches(Regex("spotify:track:[A-Za-z0-9]+")) })
                            spotify.command("play", target, JSONObject().put("uris", JSONArray(uris.take(100))))
                            mismatchCount = 0
                            deviceId = target
                            ownedUris = uris.take(100).toSet()
                            state.value = state.value.copy(device = intent.getStringExtra("deviceName").orEmpty(), playing = true)
                        }
                        "pause", "resume", "next", "previous" -> {
                            check(deviceId.isNotBlank()) { "Choose a song first" }
                            spotify.command(if (intent.action == "resume") "play" else intent.action!!, deviceId)
                        }
                        "stop" -> { closePlayback(); continue }
                    }
                    delay(400)
                    refresh()
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { pollAfter = System.currentTimeMillis() + 60_000; state.value = state.value.copy(error = e.message); if (deviceId.isBlank()) { closing = true; stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() } }
                finally { state.value = state.value.copy(busy = false); if (!closing) updateNotification() }
            }
        }
        scope.launch {
            while (isActive) {
                delay(10_000)
                if (deviceId.isNotBlank() && !state.value.busy && !closing && System.currentTimeMillis() >= pollAfter) {
                    commands.trySend(Intent().setAction("refresh"))
                }
            }
        }
    }
    private suspend fun refresh() {
        val info = spotify.playback()
        val track = info.optJSONObject("item")
        if (info.optJSONObject("device")?.optString("id") != deviceId || track?.optString("uri") !in ownedUris) {
            if (++mismatchCount < 3) return
            // Another app/device has taken over; relinquish control without pausing it.
            deviceId = ""; ownedUris = emptySet(); state.value = SpotifyRemoteState()
            closing = true; stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return
        }
        mismatchCount = 0
        state.value = state.value.copy(title = track?.optString("name").orEmpty(),
            artist = track?.optJSONArray("artists")?.optJSONObject(0)?.optString("name").orEmpty(),
            playing = info.optBoolean("is_playing"))
        updateNotification()
    }
    private suspend fun closePlayback() {
        closing = true
        try {
            if (deviceId.isNotBlank()) {
                val info = spotify.playback()
                if (info.optJSONObject("device")?.optString("id") == deviceId &&
                    info.optJSONObject("item")?.optString("uri") in ownedUris) spotify.command("pause", deviceId)
            }
            state.value = SpotifyRemoteState()
        } catch (e: Exception) { state.value = SpotifyRemoteState(error = "Could not stop Spotify over the network. Pause it in Spotify.") }
        stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
    }
    private fun updateNotification() {
        val stop = PendingIntent.getService(this, 0, Intent(this, javaClass).setAction("stop"), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 0, packageManager.getLaunchIntentForPackage(packageName) ?: Intent(), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(this, "spotify_playback")
            .setSmallIcon(com.unshoo.pixelmusic.R.drawable.rounded_cast_24)
            .setContentTitle(state.value.title.ifBlank { "PixelMusic · Spotify" })
            .setContentText(state.value.device.ifBlank { "Connecting to Spotify" })
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .addAction(0, "Stop", stop).build()
        ServiceCompat.startForeground(this, 9073, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent != null) commands.trySend(intent)
        return START_NOT_STICKY
    }
    override fun onTaskRemoved(rootIntent: Intent?) { commands.trySend(Intent().setAction("stop")) }
    override fun onDestroy() { commands.close(); scope.cancel(); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null
}
