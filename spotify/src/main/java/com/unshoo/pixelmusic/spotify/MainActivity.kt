@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.unshoo.pixelmusic.spotify

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import coil.compose.AsyncImage
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    private val model: MusicModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); enableEdgeToEdge()
        if (savedInstanceState == null) intent.data?.let(model::callback)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFFC6D8C0), onPrimary = Color(0xFF172019),
                secondary = Color(0xFFB7C9DE), background = Color(0xFF111512), surface = Color(0xFF111512),
                surfaceContainer = Color(0xFF1E2520), surfaceVariant = Color(0xFF29312B),
                onSurface = Color(0xFFF0F1EA), onSurfaceVariant = Color(0xFFB4BDB4))) { App(model) }
        }
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); intent.data?.let(model::callback) }
}
private fun android.content.Context.openSpotify(uri: String = "spotify:home") {
    runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri))) }
        .onFailure { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://open.spotify.com"))) }
}
@Composable
private fun App(model: MusicModel) {
    val state by model.state.collectAsStateWithLifecycle()
    val player by model.playback.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var devices by rememberSaveable { mutableStateOf(false) }
    var settings by rememberSaveable { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(state.connected) { if (state.connected && android.os.Build.VERSION.SDK_INT >= 33) permission.launch(Manifest.permission.POST_NOTIFICATIONS) }
    LaunchedEffect(lifecycle, state.connected) {
        if (state.connected) lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            model.devices()
            while (true) { model.load(); delay(60_000) }
        }
    }
    if (!state.connected) { Connect(model,state); return }
    Scaffold(topBar = {
        TopAppBar(title = { Text("PixelMusic", fontWeight = FontWeight.Medium) }, actions = {
            IconButton(onClick = { devices = true; model.devices() }) { Icon(Icons.Rounded.Speaker,"Playback devices") }
            IconButton(onClick = { settings = true }) { Icon(Icons.Rounded.Settings,"Settings") }
        })
    }, bottomBar = {
        Column {
            if (player.title.isNotBlank()) Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(20.dp), modifier = Modifier.padding(horizontal=12.dp,vertical=4.dp)) {
                Row(Modifier.fillMaxWidth().padding(8.dp),verticalAlignment=Alignment.CenterVertically) {
                    Artwork(player.image,Modifier.size(48.dp))
                    Column(Modifier.weight(1f).padding(horizontal=12.dp)) {
                        Text(player.title,maxLines=1,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.titleSmall)
                        Text(player.artist,maxLines=1,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick={ model.control(if(player.playing) "pause" else "resume") },enabled=!player.busy) { Icon(if(player.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,if(player.playing) "Pause" else "Play") }
                    IconButton(onClick={ model.control("next") },enabled=!player.busy) { Icon(Icons.Rounded.SkipNext,"Next track") }
                }
            }
            NavigationBar(containerColor=MaterialTheme.colorScheme.background) {
                listOf("Home" to Icons.Rounded.Home,"Library" to Icons.Rounded.LibraryMusic,"Search" to Icons.Rounded.Search).forEachIndexed { index,pair ->
                    NavigationBarItem(selected=tab==index,onClick={tab=index},icon={Icon(pair.second,pair.first)},label={Text(pair.first)})
                }
            }
        }
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if(state.busy || player.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            (state.error ?: player.error)?.let { ErrorNotice(it) }
            when(tab) { 0 -> Home(state,model); 1 -> Library(state,model); else -> Search(state,model) }
        }
    }
    if(state.listTitle.isNotBlank()) ModalBottomSheet(onDismissRequest=model::closeList) {
        LazyColumn(Modifier.fillMaxWidth().fillMaxHeight(.85f),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            item {
                Text(state.listTitle,style=MaterialTheme.typography.headlineMedium)
                Text("${state.tracks.size} tracks · Spotify",color=MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(16.dp))
                Button(onClick={model.play(state.tracks)},enabled=state.tracks.isNotEmpty() && !player.busy,modifier=Modifier.fillMaxWidth().height(52.dp)) { Icon(Icons.Rounded.PlayArrow,null); Spacer(Modifier.width(8.dp)); Text("Play") }
            }
            if(state.listBusy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            (state.error ?: player.error)?.let { message -> item { ErrorNotice(message) } }
            if(state.tracks.isEmpty() && !state.listBusy) item { Text("No playable Spotify tracks available in this collection.") }
            items(state.tracks) { track -> TrackRow(track) { model.play(state.tracks,track) } }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
    if(devices) ModalBottomSheet(onDismissRequest={devices=false}) {
        LazyColumn(Modifier.fillMaxWidth().heightIn(max=600.dp),contentPadding=PaddingValues(24.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            item { Text("Play on",style=MaterialTheme.typography.headlineSmall); Text("Choose where the next track starts.",color=MaterialTheme.colorScheme.onSurfaceVariant) }
            items(state.devices,key={it.id}) { device ->
                OutlinedCard(onClick={model.select(device);devices=false},modifier=Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(16.dp).heightIn(min=32.dp),verticalAlignment=Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Speaker,null); Text(device.name,Modifier.weight(1f).padding(horizontal=12.dp))
                        if(state.device?.id==device.id) Icon(Icons.Rounded.Check,"Selected")
                    }
                }
            }
            if(player.title.isNotBlank() && player.supportsVolume) item {
                var level by remember(player.volume) { mutableFloatStateOf(player.volume.toFloat()) }
                Text("${player.device} · ${level.toInt()}%")
                Slider(value=level,onValueChange={level=it},onValueChangeFinished={model.volume(level.toInt())},valueRange=0f..100f)
            }
            if(state.devices.isEmpty()) item { Text("Open Spotify and play a song once, then refresh to find your phone or speakers.") }
            item { Row { TextButton(onClick={context.openSpotify()}) {Text("Open Spotify")}; TextButton(onClick=model::devices) {Text("Refresh")} } }
            item { Text("Use Spotify or Sonos to group speakers. This list shows Spotify Connect devices.",style=MaterialTheme.typography.bodySmall) }
        }
    }
    if(settings) ModalBottomSheet(onDismissRequest={settings=false}) {
        Column(Modifier.fillMaxWidth().padding(24.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            Text("Only Spotify",style=MaterialTheme.typography.headlineSmall)
            Text("All audio comes from Spotify. Choose audio quality in Spotify; availability depends on your account and playback device.")
            OutlinedButton(onClick={context.openSpotify()},modifier=Modifier.fillMaxWidth()) { Text("Open Spotify") }
            Text("Daily Mix 1–3 are PixelMusic mixes made from your saved tracks and Spotify artist/genre catalog searches. They are not Spotify's official Daily Mix playlists.",style=MaterialTheme.typography.bodyMedium)
            Text("In your Sorento, select Spotify in Android Auto for playback controls. PixelMusic starts the Spotify queue.",style=MaterialTheme.typography.bodyMedium)
            Text("Swiping PixelMusic away requests a pause. Remote playback needs a network connection to stop.",style=MaterialTheme.typography.bodySmall)
            if(player.title.isNotBlank()) TextButton(onClick={model.control("stop")}) {Text("Stop playback")}
            TextButton(onClick={settings=false;model.disconnect()},enabled=!player.busy) {Text("Disconnect Spotify")}
        }
    }
}
@Composable private fun ErrorNotice(message: String) {
    Surface(color=MaterialTheme.colorScheme.errorContainer,shape=RoundedCornerShape(14.dp),modifier=Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=4.dp)) {
        Text(message,Modifier.padding(12.dp),style=MaterialTheme.typography.bodySmall)
    }
}
@Composable private fun Connect(model: MusicModel,state: MusicState) {
    val context=LocalContext.current
    var id by rememberSaveable { mutableStateOf(model.client.clientId) }
    var details by rememberSaveable { mutableStateOf(id.isBlank()) }
    LazyColumn(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding(),contentPadding=PaddingValues(28.dp),verticalArrangement=Arrangement.spacedBy(20.dp)) {
        item { Spacer(Modifier.height(40.dp)); Icon(Icons.Rounded.GraphicEq,null,Modifier.size(48.dp),tint=MaterialTheme.colorScheme.primary) }
        item { Text("Your music.\nOnly Spotify.",style=MaterialTheme.typography.displaySmall,fontWeight=FontWeight.Medium) }
        item { Text("Connect Spotify Premium to bring in your library and create three daily mixes.",color=MaterialTheme.colorScheme.onSurfaceVariant) }
        item { OutlinedTextField(value=id,onValueChange={id=it.trim()},modifier=Modifier.fillMaxWidth(),label={Text("Spotify Client ID")},singleLine=true,enabled=!state.busy); TextButton(onClick={details=!details}) {Text(if(details) "Hide setup details" else "One-time setup")} }
        if(details) item {
            Surface(color=MaterialTheme.colorScheme.surfaceContainer,shape=RoundedCornerShape(20.dp)) {
                Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                    Text("Use your existing developer app",style=MaterialTheme.typography.titleMedium)
                    Text("Copy its Client ID, add this redirect URI, and allow your Spotify account under Users & Access. You sign in on Spotify's own page.")
                    SelectionContainer {Text(SpotifyClient.REDIRECT,color=MaterialTheme.colorScheme.primary)}
                    TextButton(onClick={context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse("https://developer.spotify.com/dashboard")))}) {Text("Developer dashboard ↗")}
                }
            }
        }
        item { Button(onClick={try {context.startActivity(Intent(Intent.ACTION_VIEW,model.client.authorizationUri(id)))} catch(e:Exception){model.error(e.message ?: "Could not open Spotify")}},enabled=!state.busy && id.isNotBlank(),modifier=Modifier.fillMaxWidth().height(56.dp)) {Text("Connect Spotify")} }
        item { Text("Spotify's app or a Spotify Connect device handles playback. Audio quality follows your Spotify settings.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant) }
        if(state.busy) item {LinearProgressIndicator(Modifier.fillMaxWidth())}
        state.error?.let { message -> item {ErrorNotice(message)} }
    }
}
@Composable private fun Home(state: MusicState,model: MusicModel) {
    val colors=listOf(Color(0xFFD5BDB0),Color(0xFFB7C9DE),Color(0xFFC6D8C0))
    Box(Modifier.fillMaxSize()) {
        LivingHomeBackground()
        LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(24.dp)) {
            item {
                Text("MADE FOR TODAY",style=MaterialTheme.typography.labelMedium,letterSpacing=2.sp,color=MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(10.dp)); Text("Find your next\nfavorite.",style=MaterialTheme.typography.displaySmall,fontWeight=FontWeight.Medium)
                Spacer(Modifier.height(10.dp)); Text("Saved favorites. Fresh finds.",color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item {
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                    repeat(3) { index ->
                        val mix=state.mixes.getOrNull(index)
                        Column(Modifier.weight(1f)) {
                            Card(onClick={mix?.let(model::openMix)},enabled=mix?.tracks?.isNotEmpty()==true,modifier=Modifier.fillMaxWidth().aspectRatio(.82f).alpha(if(mix?.tracks?.isNotEmpty()==true) 1f else .45f),shape=RoundedCornerShape(22.dp)) {
                                Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(colors[index],colors[index].copy(alpha=.7f)))).padding(14.dp)) {
                                    Icon(listOf(Icons.Rounded.Waves,Icons.Rounded.BlurOn,Icons.Rounded.GraphicEq)[index],null,Modifier.size(26.dp).align(Alignment.TopStart),tint=Color(0xFF263229))
                                    Text("0${index+1}",Modifier.align(Alignment.BottomStart),fontSize=36.sp,fontWeight=FontWeight.Light,color=Color(0xFF172019))
                                }
                            }
                            Spacer(Modifier.height(10.dp)); Text("Daily Mix ${index+1}",style=MaterialTheme.typography.titleSmall)
                            Text(if(mix?.tracks?.isNotEmpty()==true) mix.subtitle else if(state.busy) "Building…" else "Save more favorites",style=MaterialTheme.typography.bodySmall,maxLines=2,overflow=TextOverflow.Ellipsis,color=MaterialTheme.colorScheme.onSurfaceVariant)
                            if(mix!=null) Text("${mix.tracks.size-mix.discoveries} saved · ${mix.discoveries} finds",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            state.notice?.let { notice -> item {Text(notice,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)} }
            item {
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {Text("A fresh set, every day",style=MaterialTheme.typography.titleSmall); Text("PixelMusic mixes · Spotify tracks",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
                    IconButton(onClick={model.load(force=true)},enabled=!state.busy) {Icon(Icons.Rounded.Refresh,"Refresh daily mixes")}
                }
            }
        }
    }
}
@Composable private fun Library(state: MusicState,model: MusicModel) {
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
        item {Text("Your library",style=MaterialTheme.typography.headlineLarge);Text("Saved in Spotify",color=MaterialTheme.colorScheme.onSurfaceVariant)}
        items(state.collections,key={it.id}) { c ->
            OutlinedCard(onClick={model.openCollection(c)},modifier=Modifier.fillMaxWidth()) {
                Row(Modifier.padding(18.dp),verticalAlignment=Alignment.CenterVertically) {
                    Icon(if(c.id.startsWith("liked:")) Icons.Rounded.FavoriteBorder else Icons.Rounded.Album,null,Modifier.size(30.dp))
                    Column(Modifier.weight(1f).padding(start=16.dp)) {Text(c.name,style=MaterialTheme.typography.titleMedium);Text(c.kind,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
                    Icon(Icons.Rounded.ChevronRight,null)
                }
            }
        }
        if(state.collections.isEmpty()) item {Text("Your Spotify collections appear here after connecting.")}
    }
}
@Composable private fun Search(state: MusicState,model: MusicModel) {
    var query by rememberSaveable {mutableStateOf("")}
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        item {Text("Search Spotify",style=MaterialTheme.typography.headlineLarge)}
        item {OutlinedTextField(value=query,onValueChange={query=it;model.search(it)},modifier=Modifier.fillMaxWidth(),placeholder={Text("Songs or artists")},singleLine=true,leadingIcon={Icon(Icons.Rounded.Search,null)})}
        if(state.searchBusy) item {LinearProgressIndicator(Modifier.fillMaxWidth())}
        items(state.results) {track -> TrackRow(track) {model.play(state.results,track)}}
        if(query.isNotBlank() && !state.searchBusy && state.results.isEmpty()) item {Text("No results. Try another song or artist.")}
    }
}
@Composable private fun TrackRow(track: SpotifyTrack,onPlay:()->Unit) {
    val context=LocalContext.current
    Surface(onClick=onPlay,modifier=Modifier.fillMaxWidth(),shape=RoundedCornerShape(14.dp),color=MaterialTheme.colorScheme.surfaceContainer) {
        Row(Modifier.padding(10.dp),verticalAlignment=Alignment.CenterVertically) {
            Artwork(track.image,Modifier.size(48.dp))
            Column(Modifier.weight(1f).padding(horizontal=12.dp)) {
                Text(track.title,maxLines=1,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.titleSmall)
                Text(track.artist,maxLines=1,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick={context.openSpotify(track.uri)}) {Icon(Icons.Rounded.OpenInNew,"View track on Spotify",Modifier.size(18.dp))}
        }
    }
}
@Composable private fun Artwork(url:String,modifier:Modifier) {
    Box(modifier.clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant),contentAlignment=Alignment.Center) {
        if(url.isBlank()) Icon(Icons.Rounded.MusicNote,null) else AsyncImage(model=url,contentDescription=null,modifier=Modifier.fillMaxSize(),contentScale=ContentScale.Crop)
    }
}
