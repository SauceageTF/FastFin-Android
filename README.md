# FastFin for Android

A native Jellyfin client: Kotlin, Jetpack Compose and Media3/ExoPlayer. It
follows the FastFin iOS layout (the poster hero, shelves, the cinematic player
HUD) with solid dark surfaces in place of the iOS glass, and adds a
mini player. The engineering follows the rules Spotifast uses to stay small
and fast.

## Build

Open the folder in Android Studio (Ladybug or newer) and press Run. From a terminal:

```bash
./gradlew :app:assembleRelease
```

The release APK is at `app/build/outputs/apk/release/app-release.apk`. For easy
sideloading it is signed with the debug key; use a real keystore before
publishing anywhere. Requires JDK 17–21 (Gradle 8.14 does not run on JDK 25).

## What came from where

**From FastFin iOS:** the design tokens (`ui/theme/Theme.kt` is `lib/theme.ts`),
the surface primitives (solid panels on Android), Hero, CarouselRow, PosterCard, ContinueWatchingCard, the
screens and their layout, the Jellyfin client and auth header, the
PlaybackInfo negotiation (`getPlaybackSource` → `Negotiator`), the hand-built
H.264 fallback, the player HUD, and the playback reporting that keeps Continue
Watching in sync and kills transcodes on exit.

**From Spotifast:**

| Spotifast rule | Here |
|---|---|
| Native, no browser engine | Compose + ExoPlayer, no React Native/JS bridge. The UI thread never waits on network or disk. |
| Event-driven, idle when nothing happens | No polling. The player clock is sampled only while the controls are visible and playing (mini player: once a second). Progress reports every 10 s only while playing. The hero stops auto-advancing when off screen, backgrounded, or covered by the player. |
| Optimistic interface | Tapping Play shows the player instantly with known artwork while the server negotiates. Detail pages draw their header from the list's copy of the item. Track switches update the menu immediately. |
| Cached pages under the TTL make no request; older ones stay visible while refreshing | `Repository`: stale-while-revalidate, never blanks the screen. |
| Shared in-flight requests | Two screens asking for the same key share one call (Home and Library share library shelves). |
| Size-bounded caches, not time-based | Artwork memory cache by bytes (Coil, hardware bitmaps, decoded at display size); 256 MB disk; page cache LRU of 96 entries. |
| Pick the closest artwork size | `ImageUrls` rounds widths to buckets so one download serves Home, grid and search; immutable `tag` URLs; WebP via `Accept`. |
| Atomic writes | Home snapshot for instant cold start is written temp-file-then-rename; settings via SharedPreferences `apply()`. |
| One shared HTTP client | API, artwork, direct play and HLS share one OkHttp pool; the token rides in a header, never a URL or cache key. |
| Never log credentials | Tokens sealed with an Android Keystore AES-GCM key, never logged. |
| Small dependency surface | No DI framework, no Retrofit, no navigation library, no reflection JSON; R8 full mode + resource shrinking. |
| Debounced search | 280 ms, cancelling stale requests; repeat queries answer from cache. |

## HDR

- **Capabilities are read from the hardware**, not assumed
  (`playback/Capabilities.kt`): HEVC/AV1/VP9 Main10, HDR10, HDR10+, Dolby
  Vision profiles 5/7/8, maximum decode resolution, audio decoders, and HDMI
  passthrough (AC3/EAC3/DTS/TrueHD) on TV boxes. The display's HDR types come
  from `Display`.
- **The DeviceProfile is built from them** (`playback/DeviceProfile.kt`). A
  `VideoRangeType` is only advertised when the decoder can decode it and, in
  Auto mode, the screen can show it. Otherwise Jellyfin tone-maps on the
  server, so an HDR film on an SDR phone looks correct instead of washed out.
  Dolby Vision 8.1 plays its HDR10 base layer on devices without a DV
  decoder.
- **SurfaceView, not TextureView**, so HDR frames go to the display
  compositor untouched. The window switches to `COLOR_MODE_HDR` only while
  HDR video is full screen or in PiP, and back afterwards to save power.
- Settings → HDR offers **Auto / Always HDR / Tone-map**, and shows what this
  device decodes and displays.

## Playback, compared with iOS

- **MKV direct play.** ExoPlayer demuxes MKV, WebM, MP4, TS and AVI, so HEVC
  HDR MKVs play straight from disk (zero server CPU, instant seeks) instead of
  being remuxed to fMP4 HLS.
- **Subtitles on device.** PGS, VobSub, DVB, ASS and SRT render locally, so
  bitmap subtitles no longer force a burn-in transcode during direct play.
- **Instant track switching.** During direct play, audio and subtitle changes
  are local track selections with no reload. (iOS lists this as a known gap.)
- **Failure ladder.** A network blip re-prepares in place; a direct-play
  decoder failure asks the server to remux or transcode; a failed
  remux/transcode drops to the known-good H.264 HLS URL; only then is an
  error shown, with the server's own answer from a probe.

## Mini player and PiP

One ExoPlayer and one SurfaceView live above the navigation. Swipe down or tap
the chevron to dock the video above the tab bar as a mini player (play/pause,
close, progress); tap or swipe up to expand. The surface's bounds animate in
the layout phase, so the decoder and buffers are never torn down. Leaving the
app while a video plays drops it into Picture in Picture (Android 12+ glides
in automatically) with back/play/forward controls. Closing the PiP window
pauses and returns to the mini player.
