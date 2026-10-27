package com.unshoo.pixelmusic.spotify

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import kotlin.random.Random

data class MusicState(val connected: Boolean = false, val busy: Boolean = false, val error: String? = null,
    val notice: String? = null, val mixes: List<DailyMix> = emptyList(), val collections: List<SpotifyCollection> = emptyList(),
    val tracks: List<SpotifyTrack> = emptyList(), val listTitle: String = "", val listBusy: Boolean = false,
    val devices: List<SpotifyDevice> = emptyList(), val device: SpotifyDevice? = null,
    val results: List<SpotifyTrack> = emptyList(), val searchBusy: Boolean = false)
class MusicModel(application: Application) : AndroidViewModel(application) {
    val client = SpotifyGraph.client(application)
    val state = MutableStateFlow(MusicState(connected = client.fullyConnected))
    val playback = SpotifyPlaybackService.state
    private val cache = File(application.cacheDir, "spotify-daily-mixes.json")
    private var loadedDay = Long.MIN_VALUE
    private var job: Job? = null
    private var searchJob: Job? = null
    private var libraryJob: Job? = null
    private var listGeneration = 0
    init { if (client.fullyConnected) load() }
    fun error(message: String) { state.value = state.value.copy(error = message) }
    fun callback(uri: Uri) {
        job?.cancel()
        job = viewModelScope.launch {
            state.value = state.value.copy(busy = true, error = null)
            try {
                client.complete(uri)
                check(client.fullyConnected) { "Please grant Spotify library and playback permissions." }
                state.value = state.value.copy(connected = true, busy = false)
                job = null; load(force = true)
            } catch (e: CancellationException) { throw e }
            catch(e: Exception) { state.value = state.value.copy(busy = false, error = e.message) }
        }
    }
    fun disconnect() {
        job?.cancel(); searchJob?.cancel(); libraryJob?.cancel()
        viewModelScope.launch {
            if (playback.value.uri.isNotBlank()) {
                control("stop")
                withTimeoutOrNull(10_000) { playback.first { it.uri.isBlank() } }
            }
            getApplication<Application>().stopService(Intent(getApplication(), SpotifyPlaybackService::class.java))
            client.disconnect(); cache.delete(); loadedDay = Long.MIN_VALUE
            SpotifyPlaybackService.state.value = SpotifyRemoteState()
            state.value = MusicState()
        }
    }
    fun load(force: Boolean = false) {
        if (!client.fullyConnected || job?.isActive == true) return
        val day = LocalDate.now().toEpochDay()
        if (!force && loadedDay == day) return
        job = viewModelScope.launch {
            state.value = state.value.copy(busy = true, error = null, notice = null)
            try {
                val profile = client.profile()
                if (profile.has("product")) check(profile.optString("product") == "premium") { "Spotify Premium is required for playback." }
                val account = profile.optString("id")
                val collections = client.collections()
                state.value = state.value.copy(collections = collections)
                refreshDevices()
                val cached = if (force) null else readCache(account, day)
                if (cached != null) { state.value = state.value.copy(mixes = cached); loadedDay = day }
                else {
                    val library = mutableListOf<SpotifyTrack>()
                    collections.firstOrNull { it.id.startsWith("liked:") }?.let { library += client.tracks(it).first }
                    if (library.size < 90) for (c in collections.filterNot { it.id.startsWith("liked:") }.take(3)) {
                        try { library += client.tracks(c).first.take(100) }
                        catch(e: CancellationException) { throw e }
                        catch(_: Exception) { state.value = state.value.copy(notice = "Some collections are restricted by Spotify. Mixes use the readable library.") }
                    }
                    val known = library.distinctBy { it.uri }
                    if (known.isEmpty()) state.value = state.value.copy(mixes = emptyList(), notice = "Save songs or playlists in Spotify, then refresh to create your mixes.")
                    else {
                        var artists = emptyList<SpotifyArtist>()
                        try { artists = client.topArtists() } catch(e: CancellationException) { throw e } catch(_: Exception) { }
                        val groups = known.groupBy { it.artist }.entries.sortedByDescending { it.value.size }.take(12).shuffled(Random(day)).take(3)
                        val used = mutableSetOf<String>()
                        val mixes = mutableListOf<DailyMix>()
                        repeat(3) { index ->
                            val group = groups.getOrNull(index) ?: groups.first()
                            val familiar = if (groups.size >= 3) group.value else known.filterIndexed { i, _ -> i % 3 == index }
                            val candidates = mutableListOf<SpotifyTrack>()
                            try {
                                candidates += client.search("artist:\"${group.key.replace("\"", "")}\"")
                                val genre = artists.firstOrNull { it.id == group.value.first().artistId }?.genres?.firstOrNull()
                                if (genre != null) candidates += client.search("genre:\"${genre.replace("\"", "")}\"")
                            } catch(e: CancellationException) { throw e }
                            catch(_: Exception) { state.value = state.value.copy(notice = "Some discoveries are unavailable right now. Saved Spotify tracks remain playable.") }
                            val mix = MixBuilder.build(index+1, day, group.key, familiar, candidates, known.map { it.uri }.toSet(), used)
                            mixes += mix; used += mix.tracks.map { it.uri }
                        }
                        state.value = state.value.copy(mixes = mixes); loadedDay = day
                        saveCache(account, day, mixes)
                    }
                }
            } catch(e: CancellationException) { throw e }
            catch(e: Exception) { error(e.message ?: "Could not load Spotify") }
            finally { state.value = state.value.copy(busy = false) }
        }
    }
    private suspend fun refreshDevices() {
        try {
            val devices = client.devices()
            state.value = state.value.copy(devices = devices, device = devices.find { it.id == state.value.device?.id } ?: devices.find { it.active } ?: devices.firstOrNull())
        } catch(e: CancellationException) { throw e } catch(e: Exception) { error(e.message ?: "Device lookup failed") }
    }
    fun devices() { viewModelScope.launch { refreshDevices() } }
    fun select(device: SpotifyDevice) { state.value = state.value.copy(device = device) }
    fun closeList() { listGeneration++; libraryJob?.cancel(); state.value = state.value.copy(listTitle = "", tracks = emptyList(), listBusy = false) }
    fun openMix(mix: DailyMix) { closeList(); state.value = state.value.copy(listTitle = "Daily Mix ${mix.number}", tracks = mix.tracks, error = null) }
    fun openCollection(collection: SpotifyCollection) {
        val generation = ++listGeneration
        libraryJob?.cancel()
        state.value = state.value.copy(listTitle = collection.name, tracks = emptyList(), listBusy = true, error = null)
        libraryJob = viewModelScope.launch {
            try { state.value = state.value.copy(tracks = client.tracks(collection).first) }
            catch(e: CancellationException) { throw e }
            catch(e: Exception) { error(e.message ?: "Could not load collection") }
            finally { if (generation == listGeneration) state.value = state.value.copy(listBusy = false) }
        }
    }
    fun search(query: String) {
        searchJob?.cancel(); state.value = state.value.copy(results = emptyList(), searchBusy = query.isNotBlank(), error = null)
        if (query.isBlank()) return
        searchJob = viewModelScope.launch {
            delay(450)
            try { state.value = state.value.copy(results = client.search(query)) }
            catch(e: CancellationException) { throw e }
            catch(e: Exception) { error(e.message ?: "Search failed") }
            finally { state.value = state.value.copy(searchBusy = false) }
        }
    }
    fun play(tracks: List<SpotifyTrack>, start: SpotifyTrack? = tracks.firstOrNull()) {
        if (playback.value.busy) return
        val device = state.value.device
        if (device == null) { error("Open Spotify and play once, then refresh Devices to select your phone or speaker."); return }
        val uris = tracks.dropWhile { it.uri != start?.uri }.take(100).map { it.uri }
        if (uris.isEmpty()) return
        send(Intent().setAction("play").putExtra("device",device.id).putExtra("deviceName",device.name).putStringArrayListExtra("uris",ArrayList(uris)))
    }
    private fun send(intent: Intent) {
        try { ContextCompat.startForegroundService(getApplication(), intent.setClass(getApplication(), SpotifyPlaybackService::class.java)) }
        catch(e: Exception) { error(e.message ?: "Could not start Spotify controls") }
    }
    fun control(action: String) = send(Intent().setAction(action))
    fun volume(value: Int) = send(Intent().setAction("volume").putExtra("volume",value.coerceIn(0,100)))
    private suspend fun readCache(account: String, day: Long): List<DailyMix>? = withContext(Dispatchers.IO) {
        runCatching {
            val root = JSONObject(cache.readText())
            if (root.getString("account") != account || root.getLong("day") != day) return@runCatching null
            val array = root.getJSONArray("mixes")
            (0 until array.length()).map { i ->
                val m = array.getJSONObject(i); val tracks = m.getJSONArray("tracks")
                DailyMix(i+1,m.getString("subtitle"),(0 until tracks.length()).map { n ->
                    val t = tracks.getJSONObject(n)
                    SpotifyTrack(t.getString("title"),t.getString("artist"),t.getLong("duration"),t.getString("uri"),t.optString("artistId"),t.optString("image"))
                },m.getInt("discoveries"))
            }
        }.getOrNull()
    }
    private suspend fun saveCache(account: String, day: Long, mixes: List<DailyMix>) = withContext(Dispatchers.IO) {
        runCatching {
            val array = JSONArray()
            mixes.forEach { m ->
                val tracks = JSONArray()
                m.tracks.forEach { t -> tracks.put(JSONObject().put("title",t.title).put("artist",t.artist).put("duration",t.duration).put("uri",t.uri).put("artistId",t.artistId).put("image",t.image)) }
                array.put(JSONObject().put("subtitle",m.subtitle).put("discoveries",m.discoveries).put("tracks",tracks))
            }
            cache.writeText(JSONObject().put("account",account).put("day",day).put("mixes",array).toString())
        }
    }
}
