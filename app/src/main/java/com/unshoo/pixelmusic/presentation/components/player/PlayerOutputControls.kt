package com.unshoo.pixelmusic.presentation.components.player

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Airplay
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.mediarouter.media.MediaRouter
import com.unshoo.pixelmusic.R
import com.unshoo.pixelmusic.data.service.speakers.NetworkSpeakers
import com.unshoo.pixelmusic.presentation.components.LocalMaterialTheme
import com.unshoo.pixelmusic.presentation.viewmodel.PlayerViewModel
import kotlin.math.roundToInt

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
internal fun PlayerOutputControls(viewModel: PlayerViewModel, onSelectOutput: () -> Unit) {
    val connections by NetworkSpeakers.connections.collectAsStateWithLifecycle()
    val localVolume by viewModel.trackVolume.collectAsStateWithLifecycle()
    val routeVolume by viewModel.routeVolume.collectAsStateWithLifecycle()
    val route by viewModel.selectedRoute.collectAsStateWithLifecycle()
    val remote by viewModel.isRemotePlaybackActive.collectAsStateWithLifecycle()
    val bluetoothName by viewModel.bluetoothName.collectAsStateWithLifecycle()
    val connected = connections.filter { it.connected }
    val network = connections.isNotEmpty()
    val routeMax = (route?.volumeMax ?: 1).coerceAtLeast(1)
    val value = when {
        network -> connected.firstOrNull { it.syncedGroup }?.groupVolume ?: connected.maxOfOrNull { it.volume } ?: 0f
        remote -> routeVolume.toFloat() / routeMax
        else -> localVolume
    }.coerceIn(0f, 1f)
    val enabled = if (network) connected.isNotEmpty() else !remote ||
        route?.volumeHandling == MediaRouter.RouteInfo.PLAYBACK_VOLUME_VARIABLE
    val outputName = when {
        connections.size > 1 -> "${connections.size} speakers"
        network -> connections.first().device.name
        remote -> route?.name ?: "Cast"
        !bluetoothName.isNullOrBlank() -> bluetoothName!!
        else -> "AirPlay / Speakers"
    }
    MaterialTheme(colorScheme = LocalMaterialTheme.current) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 32.dp, vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(painterResource(R.drawable.rounded_volume_down_24), null, Modifier.size(20.dp))
                Slider(
                    value = value,
                    enabled = enabled,
                    onValueChange = { volume ->
                        when {
                            network -> NetworkSpeakers.setGroupVolume(volume)
                            remote -> viewModel.setRouteVolume((volume * routeMax).roundToInt())
                            else -> viewModel.setTrackVolume(volume)
                        }
                    },
                    modifier = Modifier.weight(1f).height(48.dp).semantics {
                        contentDescription = if (connected.any { it.syncedGroup }) "Sonos group volume" else if (network) "Stream level" else "Playback volume"
                    }
                )
                Icon(painterResource(R.drawable.rounded_volume_up_24), null, Modifier.size(20.dp))
            }
            FilledTonalButton(
                onClick = onSelectOutput,
                shape = RoundedCornerShape(28.dp),
                modifier = Modifier.heightIn(min = 52.dp).widthIn(min = 180.dp, max = 320.dp)
            ) {
                Icon(Icons.Rounded.Airplay, null, Modifier.size(24.dp))
                Spacer(Modifier.width(10.dp))
                Text(outputName, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

