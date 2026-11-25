<p align="left">
  <img src="assets/logo.svg" width="84" height="84" alt="Synctra logo" />
</p>

# Synctra

Offline Android app that calculates the exact timing offset (in seconds and milliseconds) between a movie file and an external subtitle file, with optional synced `.srt` export.

<p align="left">
  <a href="https://github.com/ridhozahrann/Synctra/releases/latest">
    <img src="assets/download-button.svg" height="44" alt="Download Latest APK" />
  </a>
</p>

Instead of guessing subtitle delay values by trial and error inside VLC, MX Player, or Nova Video Player, Synctra samples the movie's audio track, matches spoken dialogue against the subtitle timeline, and tells you the exact delay to enter.

## How it works

1. **Subtitle cleanup & encoding detection:** Parses `.srt`, `.ass`/`.ssa` (stripping `\p` vector drawings), and `.vtt` (skipping `NOTE` blocks) with automatic multi-charset detection (`UTF-8/BOM`, `UTF-16`, `Shift_JIS`, `EUC-KR`, `GBK`, `Big5`, `windows-1251`, `windows-1256`, `windows-1252`) plus an in-app encoding picker and 3-line live preview. Flags website ads, translator credits, URLs, SDH cues (`[music]`, `♪`), and intro/outro edges before alignment.
2. **Audio-only sampling:** Skips video decoding completely. If a container has multiple audio tracks (e.g. English and Hindi), you can choose which track to analyze and preview. For full-length movies, it seeks to three dialogue-dense 90-second segments in the interior (~20%, ~50%, ~75% of duration), and samples up to two adaptive extra checkpoints if initial confidence or agreement is low.
3. **Speech detection & correlation:** Runs a full-rate streaming voice-band detector (300–3400 Hz Butterworth bandpass + syllabic modulation, 20 ms frames, with Center-channel dialogue weighting on 5.1/7.1 tracks) and a two-stage Radix-2 FFT normalized cross-correlation (±60 s, auto-expanding to ±120 s) with parabolic peak refinement and multi-window joint pooled correlation fallback.
4. **Consistency / drift check:** Compares offsets across the sampled checkpoints to warn if the subtitle has progressive drift or comes from a different release cut.
5. **10-second audio preview:** Plays a short audio-only clip from the selected audio track synced with live subtitle text and `±0.1s` / `±0.5s` nudge buttons.
6. **Save `.srt` with ad review & verification:** Optionally saves a constant-offset shifted `.srt` file via Android SAF. Every flagged ad/credit line is listed with timestamp and reason so you can uncheck any false positive before saving, and Synctra automatically verifies the exported `.srt` against cached audio envelopes and provides offline **Diagnostics** export.

## Offset sign convention

`offset = audio_time - subtitle_time`

- **Positive (`+2.40s`):** Subtitle appears too early → add `+2.40s` delay in VLC / MX Player / Nova.
- **Negative (`-1.35s`):** Subtitle appears too late → set `-1.35s` in your player.

## Supported formats

- **Containers:** `.mp4`, `.mkv`, `.avi`, `.webm`, `.mov`, `.ts` (via AndroidX Media3 extractors)
- **Audio codecs:** AAC, MP3, Opus, Vorbis, FLAC, LPCM (8/16/24/32-bit LE/BE & 32-bit float) via native `MediaCodec`, with automatic fallback to `org.jellyfin.media3:media3-ffmpeg-decoder` for AC-3, E-AC-3, DTS, TrueHD, and ALAC
- **Subtitles:** `.srt`, `.ass`, `.ssa`, `.vtt`
- **Minimum Android version:** Android 7.0 (API 24), no storage permissions required (uses Android's document picker)
- **Languages:** English and Indonesian (defaults to system locale, switchable in-app)

## Download

Get the latest signed APK from [Releases](https://github.com/ridhozahrann/Synctra/releases/latest):
- `Synctra-vX.Y.Z-arm64-v8a.apk` (~3 MB, recommended for modern 64-bit Android devices)
- `Synctra-vX.Y.Z-universal.apk` (~7 MB, all ABIs)

## Building from source

Requires JDK 21 and Android SDK (API 35).

```bash
# Run unit tests
./gradlew :app:testDebugUnitTest

# Build debug APK
./gradlew :app:assembleDebug

# Build minified universal release APK
./gradlew :app:assembleRelease

# Build per-ABI splits (arm64-v8a, armeabi-v7a) + universal
./gradlew :app:assembleRelease -PabiSplits=true
```

### Persistent release signing in GitHub Actions

To allow in-place updates between GitHub Releases without uninstalling first, add these repository secrets (`Settings → Secrets and variables → Actions`):
- `SIGNING_KEYSTORE_BASE64` — base64-encoded `.jks` keystore
- `KEYSTORE_PASSWORD`
- `KEY_ALIAS`
- `KEY_PASSWORD`

## Tech stack

- Kotlin, Jetpack Compose (Material 3), Coroutines
- AndroidX Media3 (`media3-exoplayer`, `media3-extractor`, `media3-datasource`)
- [`org.jellyfin.media3:media3-ffmpeg-decoder`](https://github.com/jellyfin/jellyfin-androidx-media) (GPL-3.0) for software audio decoding

## License

Licensed under the [GNU General Public License v3.0](LICENSE).
