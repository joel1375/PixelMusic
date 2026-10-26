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
are restored on disconnect. For individual AirPlay/DLNA receivers the slider attenuates the sent signal;
Sonos group sliders now control the rooms' native volume levels. Device audio effects applied after decoding
are not included in this stream.

This is an experimental integration, not certified AirPlay support. Sonos selections use native room grouping and a single coordinator stream.
Other AirPlay/DLNA receivers support one-at-a-time output; mixed-brand synchronized
groups are not implemented. Some receivers/firmware may reject
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
a volume slider. For Sonos output, the slider sets all connected room volumes; individual levels
remain available in the picker. For a single AirPlay receiver it sets digital gain. For
Google Cast it controls route volume; for local/Bluetooth output it controls
PixelMusic playback volume. The Sonos tab filters receivers identified by their
manufacturer or advertised Sonos model, not all DLNA devices.

## Spotify import and animation update

Settings now opens a Spotify importer with browser-based PKCE authorization.
Register a Spotify developer app and enter its Client ID on the setup screen;
register `pixelmusic-spotify://callback` as its redirect URI. No client secret is
embedded. The developer app owner needs Spotify Premium and the importing account
must be allowed in Users & Access. Tokens and temporary PKCE state are encrypted
with Android Keystore in no-backup storage. Disconnect removes local credentials.

Choose playlists, Liked Songs or saved albums (imported as playlists). The importer
paginates Spotify results and matches title, primary artist and duration to local
tracks or YouTube Music. It never transfers Spotify audio. Unmatched/unavailable
tracks are counted; denied playlists are reported separately. Re-import merges
tracks into the same imported playlist without overwriting unrelated playlists.
Spotify authorization and account-specific import still need live testing.

The player now retains one prepared content tree instead of discarding a separate
prewarm tree, keeps content width stable during expansion, and prepares delayed
controls while hidden. The delivered speakerPreview variant is non-debuggable
and release-optimized, retaining the test app's package ID. Actual frame-time
improvements have not been measured on a physical device.


## Native Sonos synchronization (preview 4)

Selecting Sonos rooms now creates a native Sonos group and streams PCM only to
one coordinator. Sonos distributes clock-synchronized audio to its members.
Adding/removing a selected room serially stops the previous stream, detaches its
selected rooms, verifies the new group and starts one new stream. This can briefly
interrupt playback; it is not a seamless AirPlay 2 group handoff. Late changes
supersede in-flight setup. Removing the coordinator chooses the next selected room.

Existing Sonos room grouping is changed by this operation. Selected rooms are
left standalone on disconnect rather than restored to their previous groups.
Stereo pairs and surround satellites are treated as bonded rooms, not independently
selectable speakers. Unselected visible rooms cannot pass group verification.
The current stream URI is checked before stopping/ungrouping to avoid taking over
playback started by another controller. If the group changes externally or the
network fails, the app reports the failure and offers Re-sync Sonos. It does not
silently fall back to unsynchronized streams. Sonos volume is read before playback
and is not raised automatically when grouping.

Validation still requires real Move/Play/Era hardware: add a room while playing,
remove a follower, remove the coordinator, rapidly change selections, disconnect
all, change groups in the Sonos app, lose Wi-Fi, and check individual/group volume.
XML tests cover escaped SOAP topology, missing/extra rooms, wrong coordinators,
bonded satellites and rejected external entities.

## Native Sonos volume controls

The player's master slider now uses Sonos GroupRenderingControl (snapshot +
SetGroupVolume), preserving Sonos's relative room levels. Every selected speaker
card has its own labeled slider and percentage, enabled once connected, using
RenderingControl SetVolume on that room. Actual room and group volumes are read
back periodically, so physical-button or Sonos-app changes appear in PixelMusic.
New slider gestures take precedence over stale polling results. Other, non-Sonos
AirPlay/DLNA outputs retain a clearly labeled stream-level control.
