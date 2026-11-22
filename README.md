# Synctra

Offline Android app that calculates the exact timing offset (in seconds and milliseconds) between a movie file and an external subtitle file.

Instead of guessing subtitle delay values by trial and error inside VLC, MX Player, or Nova Video Player, Synctra samples the movie's audio track, matches spoken dialogue against the subtitle timeline, and tells you the exact delay to enter.

## How it works

1. **Subtitle cleanup:** Parses `.srt`, `.ass`/`.ssa`, and `.vtt` files (UTF-8/BOM, UTF-16, Windows-1252) and strips out website ads, translator credits, URLs, SDH cues (`[music]`, `♪`), and intro/outro edges before alignment.
2. **Audio-only sampling:** Skips video decoding completely. If a container has multiple audio tracks (e.g. English and Hindi), you can choose which track to analyze and preview. For full-length movies, it seeks to three dialogue-dense 90-second segments in the interior (~20%, ~50%, ~75% of duration), avoiding intro logos and end credits.
3. **Speech detection & correlation:** Runs a streaming voice-band detector (300–3400 Hz Butterworth bandpass + syllabic modulation, 20 ms frames) and a two-stage Radix-2 FFT normalized cross-correlation (±60 s, auto-expanding to ±120 s) with parabolic peak refinement.
4. **Drift check:** Compares offsets across the sampled checkpoints to flag progressive drift (e.g. 23.976 fps vs 25 fps or mismatched cuts).
5. **10-second audio preview:** Plays a short audio-only clip synced with live subtitle text and `±0.1s` / `±0.5s` nudge buttons so you can verify the result by ear.

## Offset sign convention

`offset = audio_time - subtitle_time`

- **Positive (`+2.40s`):** Subtitle appears too early → add `+2.40s` delay in VLC / MX Player / Nova.
- **Negative (`-1.35s`):** Subtitle appears too late → set `-1.35s` in your player.

## Supported formats

- **Containers:** `.mp4`, `.mkv`, `.avi`, `.webm`, `.mov`, `.ts` (via AndroidX Media3 extractors)
- **Audio codecs:** AAC, MP3, Opus, Vorbis, FLAC, PCM via native `MediaCodec`, with automatic fallback to `org.jellyfin.media3:media3-ffmpeg-decoder` for AC-3, E-AC-3, DTS, TrueHD, and ALAC
- **Subtitles:** `.srt`, `.ass`, `.ssa`, `.vtt`
- **Minimum Android version:** Android 7.0 (API 24), no storage permissions required (uses Android's document picker)

## Download

Grab the latest signed universal APK (~7 MB) from [Releases](https://github.com/ridhozahrann/Synctra/releases).

## Building from source

Requires JDK 21 and Android SDK (API 35).

```bash
# Run unit tests
./gradlew :app:testDebugUnitTest

# Build debug APK
./gradlew :app:assembleDebug

# Build minified release APK
./gradlew :app:assembleRelease
```

## Tech stack

- Kotlin, Jetpack Compose (Material 3), Coroutines
- AndroidX Media3 (`media3-exoplayer`, `media3-extractor`, `media3-datasource`)
- [`org.jellyfin.media3:media3-ffmpeg-decoder`](https://github.com/jellyfin/jellyfin-androidx-media) (GPL-3.0) for software audio decoding
