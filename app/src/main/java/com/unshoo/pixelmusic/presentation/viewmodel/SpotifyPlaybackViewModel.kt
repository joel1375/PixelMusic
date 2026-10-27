package com.unshoo.pixelmusic.presentation.viewmodel

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unshoo.pixelmusic.data.spotify.*
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import javax.inject.Inject

data class SpotifyBrowseState(val connected: Boolean = false, val allowed: Boolean = false,
    val loading: Boolean = false, val error: String? = null, val devices: List<SpotifyDevice> = emptyList(),
    val collections: List<SpotifyCollection> = emptyList(), val tracks: List<SpotifyTrack> = emptyList(),
    val collectionName: String = "", val selectedDevice: SpotifyDevice? = null)
@HiltViewModel
class SpotifyPlaybackViewModel @Inject constructor(
    private val spotify: SpotifyClient, @ApplicationContext private val context: Context
) : ViewModel() {
    val state = MutableStateFlow(SpotifyBrowseState())
    val playback = SpotifyPlaybackService.state
    private var job: Job? = null
    private fun request(block: suspend () -> Unit) {
        job?.cancel()
        job = viewModelScope.launch {
            state.value = state.value.copy(loading = true, error = null)
            try { block() } catch(e: CancellationException) { throw e }
            catch(e: Exception) { state.value = state.value.copy(error = e.message) }
            finally { state.value = state.value.copy(loading = false) }
        }
    }
    fun load() = request {
        state.value = state.value.copy(connected = spotify.connected, allowed = spotify.canControlPlayback)
        if (spotify.connected && spotify.canControlPlayback) {
            val devices = spotify.devices()
            state.value = state.value.copy(devices = devices, selectedDevice = devices.find { it.id == state.value.selectedDevice?.id } ?: devices.find { it.active } ?: devices.firstOrNull())
            state.value = state.value.copy(collections = spotify.collections())
        }
    }
    fun select(device: SpotifyDevice) { state.value = state.value.copy(selectedDevice = device) }
    fun open(collection: SpotifyCollection) = request {
        state.value = state.value.copy(collectionName = collection.name, tracks = emptyList())
        state.value = state.value.copy(tracks = spotify.tracks(collection).first.filter { it.uri.matches(Regex("spotify:track:[A-Za-z0-9]+")) })
    }
    fun back() { job?.cancel(); state.value = state.value.copy(collectionName = "", tracks = emptyList(), loading = false) }
    fun play(track: SpotifyTrack) {
        val device = state.value.selectedDevice ?: return
        val queue = state.value.tracks.dropWhile { it.uri != track.uri }.take(100).map { it.uri }
        val intent = Intent(context, SpotifyPlaybackService::class.java).setAction("play")
            .putExtra("device", device.id).putExtra("deviceName", device.name)
            .putStringArrayListExtra("uris", ArrayList(queue))
        ContextCompat.startForegroundService(context, intent)
    }
    fun control(action: String) { ContextCompat.startForegroundService(context, Intent(context, SpotifyPlaybackService::class.java).setAction(action)) }
}
