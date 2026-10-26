package com.unshoo.pixelmusic.presentation.viewmodel

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unshoo.pixelmusic.data.preferences.PlaylistPreferencesRepository
import com.unshoo.pixelmusic.data.repository.MusicRepository
import com.unshoo.pixelmusic.data.spotify.*
import com.unshoo.pixelmusic.data.remote.youtube.toNativeSong
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import unshoo.ianshulyadav.pixelmusic.innertube.YouTube
import unshoo.ianshulyadav.pixelmusic.innertube.models.SongItem
import javax.inject.Inject

data class SpotifyImportState(
    val connected: Boolean = false, val busy: Boolean = false,
    val collections: List<SpotifyCollection> = emptyList(),
    val message: String = "", val results: List<String> = emptyList()
)

@HiltViewModel
class SpotifyImportViewModel @Inject constructor(
    val client: SpotifyClient,
    private val music: MusicRepository,
    private val playlists: PlaylistPreferencesRepository
) : ViewModel() {
    private val _state = MutableStateFlow(SpotifyImportState(connected = client.connected))
    val state = _state.asStateFlow()
    private var work: Job? = null
    init { if (client.connected) load() }
    private fun launchWork(block: suspend () -> Unit) {
        if (work?.isActive == true) return
        _state.update { it.copy(busy = true, message = "", results = emptyList()) }
        work = viewModelScope.launch {
            try { block() }
            catch (e: CancellationException) { _state.update { it.copy(message = "Cancelled. Completed playlists were kept.") }; throw e }
            catch (e: Exception) { _state.update { it.copy(message = e.message ?: "Import failed. Please try again.") } }
            finally { _state.update { it.copy(busy = false, connected = client.connected) } }
        }
    }
    fun load() = launchWork {
        _state.update { it.copy(message = "Loading your Spotify library…") }
        val items = client.collections()
        _state.update { it.copy(collections = items, message = "Choose playlists and saved songs to import.") }
    }
    fun callback(uri: Uri) = launchWork {
        client.complete(uri)
        val items = client.collections()
        _state.update { it.copy(collections = items, connected = true, message = "Choose what to import.") }
    }
    fun connectionError(message: String) { _state.update { it.copy(message = message) } }
    fun disconnect() { client.disconnect(); _state.value = SpotifyImportState() }
    fun cancel() { work?.cancel() }
    fun import(selected: Set<String>) = launchWork {
        val chosen = _state.value.collections.filter { it.id in selected }
        withContext(Dispatchers.IO) {
            val local = music.getAllSongsOnce().toMutableList()
            for (collection in chosen) {
                ensureActive()
                _state.update { it.copy(message = "Reading ${collection.name}…") }
                try {
                    val (tracks, unsupported) = client.tracks(collection)
                    val ids = mutableListOf<String>()
                    var missing = unsupported
                    tracks.forEachIndexed { index, track ->
                        ensureActive()
                        _state.update { it.copy(message = "${collection.name}: ${index + 1}/${tracks.size} · ${track.title}") }
                        val existing = local.firstOrNull { spotifyTrackMatches(track, it.title, it.artist, it.duration) }
                        val match = existing ?: run {
                            val search = YouTube.search("${track.title} ${track.artist}", YouTube.SearchFilter.FILTER_SONG).getOrThrow()
                            search.items.filterIsInstance<SongItem>().map { it.toNativeSong() }
                                .firstOrNull { spotifyTrackMatches(track, it.title, it.artist, it.duration) }
                        }
                        if (match == null) missing++ else {
                            if (existing == null) { music.insertYoutubeSongs(listOf(match)); local.add(match) }
                            ids.add(match.id)
                        }
                    }
                    ensureActive()
                    if (ids.isNotEmpty()) {
                        val id = "spotify_import_${collection.id}"
                        val previous = playlists.userPlaylistsFlow.first().firstOrNull { it.id == id }
                        if (previous == null) playlists.createPlaylist("${collection.name} · Spotify", ids.distinct(), customId = id)
                        else playlists.addSongsToPlaylist(id, ids.distinct())
                    }
                    _state.update { it.copy(results = it.results + "${collection.name}: ${ids.distinct().size} matched, $missing unmatched or unavailable.") }
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    _state.update { it.copy(results = it.results + "${collection.name}: ${e.message ?: "Could not import"}") }
                }
            }
        }
        _state.update { it.copy(message = "Import finished. See the results below.") }
    }
}
