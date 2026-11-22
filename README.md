# Synctra

Aplikasi Android ringan & 100% offline untuk mengecek selisih detik (*offset*) antara file subtitle (`.srt`, `.ass`/`.ssa`, `.vtt`) dan file film (`.mp4`, `.mkv`, `.avi`, `.webm`) tanpa perlu tebak-tebakan manual di pemutar video.

## Fitur Utama

- **Audio-Only Multi-Point Sampling:** Hanya mengekstrak & menganalisis 3 potongan pendek di bagian tengah film (~20%, ~50%, ~75%) tanpa decode video — cepat, hemat RAM, dan otomatis melewati intro/kredit akhir.
- **Anti-Iklan & Filter SDH Otomatis:** Menyaring baris iklan situs, kredit penerjemah, URL, serta teks non-dialog (`[musik]`, `♪`, dsb.) sebelum pencocokan.
- **Dukungan Codec Audio Luas (Opsi B):** Menggunakan Media3 + ekstensi `org.jellyfin.media3:media3-ffmpeg-decoder` (audio-only) sehingga file `.mkv` dengan audio AC-3, E-AC-3, DTS, TrueHD, AAC, Opus, MP3, FLAC, dan ALAC tetap terbaca.
- **Deteksi Drift / Beda FPS:** Membandingkan offset antar titik sampel untuk mendeteksi ketidaksinkronan bertahap (misal 23.976 fps vs 25 fps atau beda versi rilis).
- **Test Dengar 10 Detik:** Preview audio-only sinkron dengan teks subtitle real-time + tombol geser halus (`±0.1s` / `±0.5s`) untuk memastikan angka offset benar-benar pas.
- **Tanpa Izin Storage Sensitif:** Menggunakan Android Storage Access Framework (`OpenDocument`).

## Arsitektur Singkat

- `domain/subtitle/`: [`SubtitleParser`](app/src/main/java/com/synctra/app/domain/subtitle/SubtitleParser.kt) (SRT, ASS/SSA, WebVTT + fallback encoding) & [`SubtitleCleaner`](app/src/main/java/com/synctra/app/domain/subtitle/SubtitleCleaner.kt) (filter iklan/kredit/SDH).
- `domain/audio/`: [`SpeechActivityDetector`](app/src/main/java/com/synctra/app/domain/audio/SpeechActivityDetector.kt) (streaming VAD dengan filter Butterworth bandpass 300–3400 Hz + modulasi silabis).
- `domain/sync/`: [`WindowPlanner`](app/src/main/java/com/synctra/app/domain/sync/WindowPlanner.kt), [`FftCrossCorrelator`](app/src/main/java/com/synctra/app/domain/sync/FftCrossCorrelator.kt) (Radix-2 FFT coarse-to-fine + interpolasi parabolik ~10 ms), & [`SyncAnalyzer`](app/src/main/java/com/synctra/app/domain/sync/SyncAnalyzer.kt) (konsensus berbobot + deteksi drift FPS).
- `data/media/`: [`AudioSampleExtractor`](app/src/main/java/com/synctra/app/data/media/AudioSampleExtractor.kt) (Media3 container extractors + `MediaCodec` / [`SynctraFfmpegPcmBridge`](app/src/main/java/androidx/media3/decoder/ffmpeg/SynctraFfmpegPcmBridge.kt) fallback) & [`AudioPreviewPlayer`](app/src/main/java/com/synctra/app/data/media/AudioPreviewPlayer.kt) (audio-only ExoPlayer).
- `ui/`: Jetpack Compose Material 3 dark UI ([`SynctraScreen`](app/src/main/java/com/synctra/app/ui/SynctraScreen.kt), [`SyncViewModel`](app/src/main/java/com/synctra/app/ui/SyncViewModel.kt)).

## Build & Test

```bash
# Jalankan unit test
./gradlew :app:testDebugUnitTest

# Build Debug APK
./gradlew :app:assembleDebug

# Build Release APK (minified + shrunk)
./gradlew :app:assembleRelease
```

## Lisensi Pihak Ketiga

Proyek ini menggunakan `org.jellyfin.media3:media3-ffmpeg-decoder` (GPL-3.0) untuk dekoding audio FFmpeg di AndroidX Media3.
