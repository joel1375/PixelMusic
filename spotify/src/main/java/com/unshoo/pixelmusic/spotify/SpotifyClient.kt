package com.unshoo.pixelmusic.spotify

import android.content.Context
import android.net.Uri
import android.util.AtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

data class SpotifyCollection(val id: String, val name: String, val path: String, val kind: String)
data class SpotifyTrack(val title: String, val artist: String, val duration: Long, val uri: String = "", val artistId: String = "", val image: String = "")

class SpotifyClient(context: Context) {
    companion object { const val REDIRECT = "pixelmusic-spotify://callback" }
    private val file = AtomicFile(File(context.noBackupFilesDir, "spotify-session.enc"))
    private val http = OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).followRedirects(false).build()
    private var session: JSONObject = try {
        JSONObject(String(KeystoreBox.decrypt(String(file.readFully())), Charsets.UTF_8))
    } catch (_: Exception) { JSONObject() }
    val clientId: String get() = session.optString("client_id")
    val connected: Boolean get() = session.optString("refresh_token").isNotBlank() || session.optString("access_token").isNotBlank()
    private fun save() {
        val bytes = KeystoreBox.encrypt(session.toString().toByteArray()).toByteArray()
        val out = file.startWrite()
        try { out.write(bytes); file.finishWrite(out) } catch (e: Exception) { file.failWrite(out); throw e }
    }
    private fun random() = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) })
    fun authorizationUri(id: String): Uri {
        require(id.matches(Regex("[a-fA-F0-9]{32}"))) { "Enter the 32-character Client ID from Spotify's developer dashboard." }
        session = JSONObject().put("client_id", id)
        val verifier = random()
        val state = random()
        session.put("verifier", verifier).put("state", state).put("started", System.currentTimeMillis())
        save()
        val challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
        return Uri.parse("https://accounts.spotify.com/authorize").buildUpon()
            .appendQueryParameter("client_id", id).appendQueryParameter("response_type", "code")
            .appendQueryParameter("redirect_uri", REDIRECT).appendQueryParameter("state", state)
            .appendQueryParameter("code_challenge_method", "S256").appendQueryParameter("code_challenge", challenge)
            .appendQueryParameter("scope", "playlist-read-private playlist-read-collaborative user-library-read user-read-playback-state user-modify-playback-state user-top-read user-read-private").build()
    }
    suspend fun complete(uri: Uri) = withContext(Dispatchers.IO) {
        require(uri.scheme == "pixelmusic-spotify" && uri.host == "callback") { "Invalid Spotify callback." }
        val expected = session.optString("state")
        require(expected.isNotEmpty() && uri.getQueryParameter("state") == expected && System.currentTimeMillis() - session.optLong("started") < 600_000) { "Sign-in expired. Please connect again." }
        val verifier = session.optString("verifier")
        session.remove("state"); session.remove("verifier"); session.remove("started"); save()
        require(uri.getQueryParameter("error") == null) { "Spotify sign-in was cancelled or denied." }
        val code = requireNotNull(uri.getQueryParameter("code")) { "Spotify did not return a sign-in code." }
        exchange(FormBody.Builder().add("grant_type", "authorization_code").add("code", code)
            .add("redirect_uri", REDIRECT).add("client_id", clientId).add("code_verifier", verifier).build())
    }
    private fun exchange(body: FormBody) {
        val request = Request.Builder().url("https://accounts.spotify.com/api/token").post(body).build()
        http.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "Spotify sign-in failed (${response.code}). Check your Client ID and redirect URI, then reconnect." }
            val json = JSONObject(response.body!!.string())
            session.put("access_token", json.getString("access_token"))
                .put("expires", System.currentTimeMillis() + json.optLong("expires_in", 3600) * 1000)
            if (json.has("refresh_token")) session.put("refresh_token", json.getString("refresh_token"))
            if (json.has("scope")) session.put("scope", json.getString("scope"))
            save()
        }
    }
    private fun refresh() {
        check(session.optString("refresh_token").isNotBlank()) { "Please reconnect Spotify." }
        exchange(FormBody.Builder().add("grant_type", "refresh_token").add("refresh_token", session.getString("refresh_token")).add("client_id", clientId).build())
    }
    private val tokenLock = Mutex()
    val canControlPlayback: Boolean get() = session.optString("scope").split(" ").contains("user-modify-playback-state")
    private suspend fun accessToken(force: Boolean = false): String = tokenLock.withLock {
        if (force || System.currentTimeMillis() + 60_000 >= session.optLong("expires")) refresh()
        session.getString("access_token")
    }
    internal suspend fun get(url: String, retry: Boolean = true): JSONObject {
        coroutineContext.ensureActive()
        require(url.startsWith("https://api.spotify.com/v1/")) { "Unexpected Spotify page URL." }
        val request = Request.Builder().url(url).header("Authorization", "Bearer ${accessToken()}").build()
        http.newCall(request).execute().use { response ->
            if (response.code == 401 && retry) { accessToken(true); return get(url, false) }
            check(response.isSuccessful) {
                when (response.code) {
                    403 -> "Spotify denied access. Add your account in the developer app's Users & Access. Only owned or collaborative playlists may be readable."
                    429 -> "Spotify's rate limit was reached. Wait ${response.header("Retry-After") ?: "a few"} seconds and try again."
                    else -> "Spotify request failed (${response.code}). Try again."
                }
            }
            return if (response.code == 204) JSONObject() else JSONObject(response.body!!.string())
        }
    }
    private suspend fun pages(path: String): List<JSONObject> {
        var next: String? = "https://api.spotify.com/v1/$path"
        val seen = mutableSetOf<String>()
        val result = mutableListOf<JSONObject>()
        while (next != null) {
            check(seen.add(next) && seen.size <= 2000) { "Spotify returned too many pages. Import a smaller collection." }
            val page = get(next)
            val items = page.getJSONArray("items")
            for (i in 0 until items.length()) items.optJSONObject(i)?.let { result.add(it) }
            next = page.optString("next").takeIf { it.isNotBlank() && it != "null" }
        }
        return result
    }
    suspend fun collections(): List<SpotifyCollection> = withContext(Dispatchers.IO) {
        val accountId = get("https://api.spotify.com/v1/me").getString("id")
        val result = mutableListOf(SpotifyCollection("liked:$accountId", "Liked Songs", "me/tracks?limit=50", "Saved songs"))
        pages("me/playlists?limit=50").forEach { p ->
            val id = p.getString("id")
            result.add(SpotifyCollection("playlist:$id", p.getString("name"), "playlists/$id/items?limit=50", "Playlist"))
        }
        pages("me/albums?limit=50").forEach { entry ->
            val album = entry.optJSONObject("album") ?: return@forEach
            val id = album.getString("id")
            result.add(SpotifyCollection("album:$id", album.getString("name"), "albums/$id/tracks?limit=50", "Saved album"))
        }
        result
    }
    suspend fun tracks(collection: SpotifyCollection): Pair<List<SpotifyTrack>, Int> = withContext(Dispatchers.IO) {
        var skipped = 0
        val tracks = pages(collection.path).mapNotNull { entry ->
            val track = if (collection.id.startsWith("album:")) entry else entry.optJSONObject("item") ?: entry.optJSONObject("track")
            if (track == null || track.optString("type", "track") != "track" || entry.optBoolean("is_local") || track.optBoolean("is_local")) {
                skipped++; null
            } else {
                val artist = track.optJSONArray("artists")?.optJSONObject(0)?.optString("name").orEmpty()
                val title = track.optString("name")
                if (artist.isBlank() || title.isBlank()) { skipped++; null }
                else parseTrack(track)
            }
        }
        tracks to skipped
    }
    suspend fun playback(): JSONObject = withContext(Dispatchers.IO) { get("https://api.spotify.com/v1/me/player") }
    suspend fun devices(): List<SpotifyDevice> = withContext(Dispatchers.IO) {
        val array = get("https://api.spotify.com/v1/me/player/devices").optJSONArray("devices")
        (0 until (array?.length() ?: 0)).mapNotNull { i ->
            val item = array!!.getJSONObject(i)
            val id = item.optString("id")
            if (id.isBlank() || id == "null" || item.optBoolean("is_restricted")) null
            else SpotifyDevice(id, item.optString("name"), item.optBoolean("is_active"))
        }
    }
    suspend fun command(action: String, device: String, body: JSONObject? = null, retry: Boolean = true): Unit = withContext(Dispatchers.IO) {
        require(action in setOf("play", "pause", "next", "previous", "volume"))
        check(canControlPlayback) { "Reconnect Spotify in Settings to allow playback control." }
        val url = "https://api.spotify.com/v1/me/player/$action?device_id=${Uri.encode(device)}" + if (action == "volume") "&volume_percent=${requireNotNull(body).getInt("volume").coerceIn(0,100)}" else ""
        val requestBody = (if (action == "volume") "" else body?.toString() ?: "").toRequestBody("application/json".toMediaType())
        val request = Request.Builder().url(url).header("Authorization", "Bearer ${accessToken()}")
            .method(if (action == "next" || action == "previous") "POST" else "PUT", requestBody).build()
        http.newCall(request).execute().use { response ->
            if (response.code == 401 && retry) { accessToken(true); return@withContext command(action, device, body, false) }
            check(response.isSuccessful) {
                when(response.code) {
                    403 -> "Spotify denied playback. Check Premium, reconnect permissions, and your developer app's account access."
                    404 -> "Open Spotify on your phone, play a song once, then refresh devices here."
                    429 -> "Spotify rate limit: try again in ${response.header("Retry-After") ?: "a few"} seconds."
                    else -> "Spotify playback failed (${response.code})."
                }
            }
        }
    }
    val fullyConnected: Boolean get() = connected && canControlPlayback && session.optString("scope").split(" ").contains("user-top-read")
    suspend fun profile(): JSONObject = withContext(Dispatchers.IO) { get("https://api.spotify.com/v1/me") }
    suspend fun search(query: String): List<SpotifyTrack> = withContext(Dispatchers.IO) {
        val items = get("https://api.spotify.com/v1/search?type=track&limit=10&q=${Uri.encode(query)}").optJSONObject("tracks")?.optJSONArray("items")
        (0 until (items?.length() ?: 0)).mapNotNull { parseTrack(items!!.getJSONObject(it)) }
    }
    suspend fun topArtists(): List<SpotifyArtist> = withContext(Dispatchers.IO) {
        val items = get("https://api.spotify.com/v1/me/top/artists?limit=15&time_range=medium_term").optJSONArray("items")
        (0 until (items?.length() ?: 0)).map { i ->
            val a = items!!.getJSONObject(i); val genres = a.optJSONArray("genres")
            SpotifyArtist(a.optString("id"), a.optString("name"), (0 until (genres?.length() ?: 0)).map { genres!!.getString(it) })
        }
    }
    fun disconnect() { session = JSONObject().put("client_id", clientId); save() }
}


data class SpotifyDevice(val id: String, val name: String, val active: Boolean)


data class SpotifyArtist(val id: String, val name: String, val genres: List<String>)
internal fun parseTrack(t: JSONObject): SpotifyTrack? {
    val uri = t.optString("uri")
    if (!uri.matches(Regex("spotify:track:[A-Za-z0-9]+")) || t.optBoolean("is_local") || (t.has("is_playable") && !t.optBoolean("is_playable"))) return null
    val a = t.optJSONArray("artists")?.optJSONObject(0)
    return SpotifyTrack(t.optString("name"), a?.optString("name").orEmpty(), t.optLong("duration_ms"), uri,
        a?.optString("id").orEmpty(), t.optJSONObject("album")?.optJSONArray("images")?.optJSONObject(0)?.optString("url").orEmpty())
}
object SpotifyGraph {
    @Volatile private var instance: SpotifyClient? = null
    fun client(context: Context): SpotifyClient = instance ?: synchronized(this) {
        instance ?: SpotifyClient(context.applicationContext).also { instance = it }
    }
}
