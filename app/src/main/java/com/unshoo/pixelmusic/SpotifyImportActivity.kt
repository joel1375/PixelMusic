package com.unshoo.pixelmusic

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.unshoo.pixelmusic.data.spotify.SpotifyClient
import com.unshoo.pixelmusic.presentation.viewmodel.SpotifyImportViewModel
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class SpotifyImportActivity : ComponentActivity() {
    private val model: SpotifyImportViewModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) intent.data?.let { model.callback(it) }
        setContent { com.unshoo.pixelmusic.ui.theme.PixelMusicTheme { SpotifyImportPage(model) { finish() } } }
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.data?.let { model.callback(it) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SpotifyImportPage(model: SpotifyImportViewModel, onBack: () -> Unit) {
    val state by model.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var clientId by rememberSaveable { mutableStateOf(model.client.clientId) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    Scaffold(topBar = { TopAppBar(title = { Text("Import from Spotify") }, navigationIcon = { TextButton(onClick = onBack) { Text("Back") } }) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item { Text("Bring your music with you", style = MaterialTheme.typography.headlineSmall) }
            item { Text("Copy playlists, Liked Songs and saved albums into PixelMusic. Tracks are matched by title, artist and duration in your library or YouTube Music. Unmatched songs are reported; Spotify audio files are not transferred.") }
            if (!state.connected) {
                item {
                    Card {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("One-time Spotify setup", style = MaterialTheme.typography.titleMedium)
                            Text("Create an app in Spotify's developer dashboard, add this redirect URI exactly, and add your Spotify account under Users & Access. Development apps require the owner's Spotify Premium subscription.")
                            SelectionContainer { Text(SpotifyClient.REDIRECT) }
                            OutlinedButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://developer.spotify.com/dashboard"))) }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Open Spotify developer dashboard") }
                            OutlinedTextField(value = clientId, onValueChange = { clientId = it.trim() }, label = { Text("Client ID") }, singleLine = true, enabled = !state.busy, modifier = Modifier.fillMaxWidth())
                            Text("No client secret or Spotify password is entered here.", style = MaterialTheme.typography.bodySmall)
                            Button(onClick = {
                                try { context.startActivity(Intent(Intent.ACTION_VIEW, model.client.authorizationUri(clientId))) }
                                catch (e: Exception) { model.connectionError(e.message ?: "Could not open Spotify sign-in.") }
                            }, enabled = !state.busy && clientId.isNotBlank(), modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("Connect Spotify") }
                        }
                    }
                }
            } else {
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { model.load() }, enabled = !state.busy) { Text("Refresh") }
                        OutlinedButton(onClick = { selected = emptySet(); model.disconnect() }, enabled = !state.busy) { Text("Disconnect") }
                    }
                }
                if (state.collections.isNotEmpty()) item {
                    TextButton(onClick = { selected = if (selected.size == state.collections.size) emptySet() else state.collections.map { it.id }.toSet() }, enabled = !state.busy) { Text("Select / clear all") }
                }
                items(state.collections, key = { it.id }) { collection ->
                    Card(Modifier.fillMaxWidth().toggleable(value = collection.id in selected, enabled = !state.busy, role = Role.Checkbox) { checked -> selected = if (checked) selected + collection.id else selected - collection.id }) {
                        Row(Modifier.padding(16.dp).heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Checkbox(checked = collection.id in selected, onCheckedChange = null)
                            Column { Text(collection.name, style = MaterialTheme.typography.titleMedium); Text(collection.kind, style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                }
                item {
                    Button(onClick = { model.import(selected) }, enabled = !state.busy && selected.isNotEmpty(), modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("Import selected (${selected.size})") }
                    Text("Re-importing adds missing tracks without replacing your existing playlists. Spotify may restrict playlists you do not own or collaborate on.", style = MaterialTheme.typography.bodySmall)
                }
            }
            if (state.busy) item {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                TextButton(onClick = { model.cancel() }) { Text("Cancel") }
            }
            if (state.message.isNotBlank()) item { Text(state.message) }
            items(state.results) { result -> Text(result) }
        }
    }
}
