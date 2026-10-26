# Wi-Fi speaker preview

This personal, noncommercial build adds an in-app speaker picker for AirPlay and
Sonos/DLNA receivers. Sender code is pinned to AirSonic revision
97e98120686c0d96673e7f3138956450ee6c486c; its license and attribution are in
speaker-sender/LICENSE and speaker-sender/NOTICE.

Open the existing Connect/cast button. The Wi-Fi speakers view discovers receivers
on the same LAN. Tap a receiver to connect. A PIN prompt appears when required.
Disconnect from the picker or the persistent Wi-Fi speakers notification.
Cast / Bluetooth opens the original picker and selecting a legacy route disconnects
the network sender.

Audio comes from PixelMusic's playback pipeline, never the microphone or another
app. Local and streaming sources use the same pipeline. The phone output is silent
while network output is active. Playback remains at 1x, with 44.1 kHz stereo PCM,
and crossfades/audio offload are disabled for that session. Original output settings
are restored on disconnect. The speaker slider attenuates the sent signal; the
speaker's own volume remains relevant. Device audio effects applied after decoding
are not included in this stream.

This is an experimental integration, not certified AirPlay support. Multiple
independent receivers are selectable but are NOT clock-synchronized. Creating or
editing Sonos room groups is not implemented. Some receivers/firmware may reject
pairing or WAV live streams. IPv4 LAN discovery is the primary supported path.
Real Move, Play and Era 100 hardware validation is still required. Test play/pause,
next, seek, screen-off playback, lost Wi-Fi, disconnect, and failed PIN pairing.

The debug APK installs alongside the upstream release (application ID ends in
.debug), avoiding the need for the upstream signing key. CI debug signing keys are
not stable between runners; subsequent test builds may need reinstalling the test
app. Do not uninstall the user's original app or claim this is an in-place update.
No original signing key or service API secrets are included. Telegram and other
services requiring developer credentials may need separate build configuration.

## Player controls update

The output button is now centered at the bottom of the expanded player, beneath
a volume slider. For Wi-Fi output, the slider sets the digital level of all
connected receivers (individual levels remain available in the picker). For
Google Cast it controls route volume; for local/Bluetooth output it controls
PixelMusic playback volume. The Sonos tab filters receivers identified by their
manufacturer or advertised Sonos model, not all DLNA devices.
