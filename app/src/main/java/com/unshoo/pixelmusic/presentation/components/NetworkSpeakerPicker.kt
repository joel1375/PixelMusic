package com.unshoo.pixelmusic.presentation.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Speaker
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.runtime.saveable.rememberSaveable
import com.airsonic.sender.api.DeviceType
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.unshoo.pixelmusic.data.service.speakers.NetworkSpeakers

@Composable
fun NetworkSpeakerPicker(onUseGoogleCast: () -> Unit, beforeConnect: () -> Unit) {
    val context = LocalContext.current
    val permission = if (android.os.Build.VERSION.SDK_INT >= 37) "android.permission.ACCESS_LOCAL_NETWORK"
        else if (android.os.Build.VERSION.SDK_INT >= 33) android.Manifest.permission.NEARBY_WIFI_DEVICES else null
    var granted by remember { mutableStateOf(permission == null || androidx.core.content.ContextCompat.checkSelfPermission(context, permission) == android.content.pm.PackageManager.PERMISSION_GRANTED) }
    val launcher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted = it }
    if (granted) NetworkSpeakerPickerContent(onUseGoogleCast, beforeConnect)
    else Column(Modifier.padding(20.dp)) {
        Text("Find your speakers", style = MaterialTheme.typography.headlineSmall)
        Text("Allow nearby-device access to discover and play to speakers on your Wi-Fi.")
        Button(onClick = { permission?.let { launcher.launch(it) } }) { Text("Allow access") }
        TextButton(onClick = { context.startActivity(android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:${context.packageName}"))) }) { Text("App settings") }
        TextButton(onClick = onUseGoogleCast) { Text("Cast / Bluetooth") }
    }
}

@Composable
private fun NetworkSpeakerPickerContent(onUseGoogleCast: () -> Unit, beforeConnect: () -> Unit) {
    val context = LocalContext.current
    val devices by NetworkSpeakers.devices.collectAsStateWithLifecycle()
    val connections by NetworkSpeakers.connections.collectAsStateWithLifecycle()
    val error by NetworkSpeakers.error.collectAsStateWithLifecycle()
    val pairing by NetworkSpeakers.pinRequest.collectAsStateWithLifecycle()
    DisposableEffect(Unit) {
        NetworkSpeakers.discover(context)
        onDispose { NetworkSpeakers.stopDiscovery() }
    }
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    val allDevices = (connections.map { it.device } + devices).distinctBy { it.host }
    val visibleDevices = allDevices.filter { selectedTab == 0 || it.type == DeviceType.SONOS }
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Choose a speaker", style = MaterialTheme.typography.headlineSmall)
        Text("Play over Wi-Fi", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("All speakers", "Sonos").forEachIndexed { index, title ->
                FilterChip(
                    selected = selectedTab == index,
                    onClick = { selectedTab = index },
                    label = { Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { Text(title) } },
                    modifier = Modifier.weight(1f).heightIn(min = 56.dp)
                )
            }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(visibleDevices, key = { it.host }) { device ->
                val connection = connections.firstOrNull { it.device.host == device.host }
                Card(
                    onClick = { if (connection == null) { beforeConnect(); NetworkSpeakers.connect(context, device) } },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = if (connection != null) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh)
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            Icon(Icons.Rounded.Speaker, null, Modifier.size(32.dp))
                            Column(Modifier.weight(1f)) {
                                Text(device.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(when {
                                    connection?.connected == true -> "Connected"
                                    connection != null -> "Connecting…"
                                    device.type == DeviceType.SONOS -> "Sonos · Tap to connect"
                                    device.controlUrl != null -> "DLNA · Tap to connect"
                                    else -> "AirPlay · Tap to connect"
                                }, style = MaterialTheme.typography.bodyMedium)
                            }
                            if (connection?.connected == true) Icon(Icons.Rounded.CheckCircle, "Connected", Modifier.size(24.dp))
                            else if (connection != null) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                        }
                        if (connection?.connected == true) {
                            Slider(value = connection.volume, onValueChange = { NetworkSpeakers.setVolume(device.host, it) }, modifier = Modifier.semantics { contentDescription = "Volume for ${device.name}" })
                        }
                        if (connection != null) OutlinedButton(
                            onClick = { NetworkSpeakers.disconnect(device.host) },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)
                        ) { Text(if (connection.connected) "Disconnect" else "Cancel connection") }
                    }
                }
            }
            if (visibleDevices.isEmpty()) item {
                Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Rounded.Speaker, null, Modifier.size(40.dp))
                    Text(if (selectedTab == 1) "Looking for Sonos speakers…" else "Looking for speakers…", style = MaterialTheme.typography.titleMedium)
                    Text("Keep your speakers awake and on the same Wi-Fi.", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { NetworkSpeakers.refresh(context) }, modifier = Modifier.weight(1f).heightIn(min = 56.dp)) { Text("Scan again") }
            OutlinedButton(onClick = onUseGoogleCast, modifier = Modifier.weight(1f).heightIn(min = 56.dp)) { Text("Cast / Bluetooth") }
        }
        if (connections.isNotEmpty()) FilledTonalButton(onClick = { NetworkSpeakers.disconnectAll() }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("Play on this phone") }
        Text("Multiple speakers may play with different delays.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Sender: AirSonic · PolyForm Noncommercial 1.0.0", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(bottom = 12.dp))
    }
    pairing?.let { request ->
        var pin by remember(request.id) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { NetworkSpeakers.submitPin(null) },
            title = { Text("Pair with ${request.name}") },
            text = { OutlinedTextField(value = pin, onValueChange = { pin = it }, label = { Text("Receiver PIN or password") }, singleLine = true) },
            confirmButton = { TextButton(onClick = { NetworkSpeakers.submitPin(pin) }, enabled = pin.isNotBlank()) { Text("Pair") } },
            dismissButton = { TextButton(onClick = { NetworkSpeakers.submitPin(null) }) { Text("Cancel") } }
        )
    }
}
