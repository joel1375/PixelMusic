package com.unshoo.pixelmusic.presentation.components

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.unshoo.pixelmusic.presentation.viewmodel.SpotifyPlaybackViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpotifyPlaybackSection(onBeforePlay: () -> Unit, onSettings: () -> Unit,
    viewModel: SpotifyPlaybackViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val playing by viewModel.playback.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var browse by remember { mutableStateOf(false) }
    var autoHelp by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = .85f))) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Listen with Spotify", style = MaterialTheme.typography.titleLarge)
            Text(if (playing.title.isBlank()) "Original Spotify audio on your phone or Spotify Connect device. Quality follows your Spotify settings."
                else "${playing.title} · ${playing.artist}\n${playing.device}", style = MaterialTheme.typography.bodyMedium)
            playing.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (playing.title.isNotBlank()) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { viewModel.control("previous") }, enabled = !playing.busy) { Text("Previous") }
                    FilledTonalButton(onClick = { viewModel.control(if (playing.playing) "pause" else "resume") }, enabled = !playing.busy) { Text(if (playing.playing) "Pause" else "Play") }
                    TextButton(onClick = { viewModel.control("next") }, enabled = !playing.busy) { Text("Next") }
                }
                TextButton(onClick = { viewModel.control("stop") }) { Text("Stop Spotify playback") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { browse = true; viewModel.load() }) { Text("Spotify library") }
                TextButton(onClick = { autoHelp = true }) { Text("In your car") }
            }
        }
    }
    if (autoHelp) AlertDialog(onDismissRequest = { autoHelp = false },
        title = { Text("PixelMusic in Android Auto") },
        text = { Text("Connect your phone to your Sorento's Android Auto and select PixelMusic for Recently Played, Favorites, Playlists and Library.\n\nFor this downloaded preview APK, enable Android Auto developer settings (tap Version 10 times), then enable Unknown sources and reconnect.\n\nSpotify audio is played by Spotify. Select Spotify on the car screen for its playback controls. The car uses Android Auto's layout; animations stay on the phone.") },
        confirmButton = { TextButton(onClick = { autoHelp = false }) { Text("Got it") } })
    if (browse) ModalBottomSheet(onDismissRequest = { browse = false }) {
        LazyColumn(Modifier.fillMaxWidth().fillMaxHeight(.85f), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { Text("Spotify", style = MaterialTheme.typography.headlineMedium) }
            item { Text("Plays through Spotify, separate from the PixelMusic player and Wi-Fi speaker picker.") }
            if (!state.connected || !state.allowed) {
                item { Text("Reconnect Spotify in Settings → Import from Spotify to grant playback permissions. Your previous connection only allowed library import.") }
                item { Button(onClick = { browse = false; onSettings() }) { Text("Open Settings") } }
            } else {
                item { Text("Play on", style = MaterialTheme.typography.titleMedium) }
                items(state.devices, key = { "device:${it.id}" }) { device ->
                    FilterChip(selected = device.id == state.selectedDevice?.id, onClick = { viewModel.select(device) }, label = { Text(device.name) })
                }
                if (state.devices.isEmpty() && !state.loading) item { Text("No available device. Open Spotify on your phone and play a song once, then refresh.") }
                item {
                    Row {
                        TextButton(onClick = { viewModel.load() }, enabled = !state.loading) { Text("Refresh devices") }
                        TextButton(onClick = {
                            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("spotify:home"))) }
                                .onFailure { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://open.spotify.com"))) }
                        }) { Text("Open Spotify") }
                    }
                }
                if (state.collectionName.isNotBlank()) {
                    item { TextButton(onClick = { viewModel.back() }) { Text("‹ All collections") }; Text(state.collectionName, style = MaterialTheme.typography.titleLarge) }
                    items(state.tracks, key = null) { track ->
                        OutlinedButton(modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp), enabled = state.selectedDevice != null && !playing.busy,
                            onClick = { onBeforePlay(); viewModel.play(track) }) {
                            Column(Modifier.fillMaxWidth()) { Text(track.title); Text(track.artist, style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                    if (state.tracks.isEmpty() && !state.loading) item { Text("No playable Spotify tracks in this collection.") }
                } else {
                    items(state.collections, key = { it.id }) { collection ->
                        OutlinedButton(onClick = { viewModel.open(collection) }, modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp), enabled = !state.loading) { Text(collection.name) }
                    }
                }
            }
            if (state.loading || playing.busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            item { state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }; playing.error?.let { Text(it, color = MaterialTheme.colorScheme.error) } }
            item { Spacer(Modifier.height(32.dp)) }
        }
    }
}
