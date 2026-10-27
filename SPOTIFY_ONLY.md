# Spotify-only preview 6

Only `:spotify` is included in Gradle. The APK excludes all legacy YouTube/Innertube/NewPipe, local playback, speaker sender, wearable and old library modules. Legacy source remains for reference, not as a selectable provider.

Startup requires Spotify PKCE authentication with the existing Client ID and redirect `pixelmusic-spotify://callback`. Library, playback, profile and top-artist permissions are requested. Tokens are encrypted with Android Keystore. All playback queues contain validated Spotify track URIs, played on the Spotify app or a Spotify Connect device. Quality follows Spotify/device settings; this does not embed a Spotify decoder or promise lossless audio.

Home has three custom Daily Mix cards and refresh; Library and Search use only Spotify. Mixes are explicitly PixelMusic mixes, not Spotify's restricted official Daily Mixes. Seeds are Liked Songs and, when fewer than 90 tracks, up to three other readable Spotify library collections. Artist catalog searches plus available top-artist genre searches supply discovery candidates. Known seed-library tracks and cross-mix repeats are excluded. Each mix interleaves up to 15 familiar and 15 discovery tracks, stable within the local day and cached by account/day. Sparse libraries can produce smaller/empty mixes; discovery failures are disclosed without provider fallback.

Spotify manages playback and compatible speaker groups. Device-supported volume uses Spotify's volume API. Use Spotify's Android Auto app in the Sorento; the former non-Spotify car browse service is excluded. Swipe-away requests a pause only for the app's device/track queue; network loss or force-stop can prevent remote pause.

CI tests mixing and ownership, compiles release, verifies its signature, and checks DEX package names for removed providers. Physical phone/car/Spotify-account testing remains outstanding. CI preview signing can change, requiring reinstall and Spotify reconnection. Spotify's cloud library is unaffected.
