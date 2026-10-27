package com.unshoo.pixelmusic.data.spotify

/** Only stop the explicitly controlled queue, never another device or unrelated track. */
internal fun ownsSpotifyPlayback(device: String, uris: Set<String>, actualDevice: String?, actualUri: String?): Boolean =
    device.isNotBlank() && device == actualDevice && actualUri != null && actualUri in uris
