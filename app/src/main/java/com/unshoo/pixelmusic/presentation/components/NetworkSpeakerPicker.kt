package com.unshoo.pixelmusic.presentation.components

import androidx.compose.foundation.layout.*
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
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
        Text("Wi-Fi speakers", style = MaterialTheme.typography.headlineSmall)
        Text("AirPlay · Sonos · DLNA", style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(8.dp))
        Text("Connect on the same Wi-Fi. This preview supports individual receivers. Multiple receivers may play with different delays; synchronized AirPlay groups are not supported yet.", style = MaterialTheme.typography.bodySmall)
        Row {
            TextButton(onClick = { NetworkSpeakers.refresh(context) }) { Text("Scan again") }
            TextButton(onClick = onUseGoogleCast) { Text("Cast / Bluetooth") }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(connections, key = { "connected:${it.device.host}" }) { connection ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(connection.device.name, style = MaterialTheme.typography.titleMedium)
                        Text(if (connection.connected) "Streaming · Wi-Fi" else "Connecting…")
                        if (connection.connected) {
                            Slider(value = connection.volume, onValueChange = { NetworkSpeakers.setVolume(connection.device.host, it) })
                        }
                        TextButton(onClick = { NetworkSpeakers.disconnect(connection.device.host) }) { Text("Disconnect") }
                    }
                }
            }
            items(devices.filter { device -> connections.none { it.device.host == device.host } }, key = { it.id }) { device ->
                OutlinedButton(onClick = { beforeConnect(); NetworkSpeakers.connect(context, device) }, modifier = Modifier.fillMaxWidth()) {
                    Text(device.name + if (device.controlUrl != null) " · Sonos / DLNA" else " · AirPlay")
                }
            }
            if (devices.isEmpty() && connections.isEmpty()) item {
                Text("Looking for speakers… Keep speakers awake and check that your Wi-Fi allows devices to communicate.", modifier = Modifier.padding(vertical = 16.dp))
            }
        }
        if (connections.isNotEmpty()) TextButton(onClick = { NetworkSpeakers.disconnectAll() }) { Text("Play on this phone") }
        Text("Speaker output uses 44.1 kHz stereo. Crossfade and playback-speed changes are disabled while connected.", style = MaterialTheme.typography.bodySmall)
        Text("Sender: AirSonic by Chunguang Wei · PolyForm Noncommercial 1.0.0", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(vertical = 8.dp))
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
