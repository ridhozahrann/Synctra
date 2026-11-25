# Changelog

## v1.4.2
- Prevented potential OOM crashes in `DocumentMetaReader` by streaming subtitle reads with an early byte-limit check.
- Fixed multi-byte CJK charset auto-detection on >64 KB subtitle files by newline-aligning the 64 KB detection prefix, narrowed HTML/VTT tag stripping to preserve `<`/`>` in dialogue, added HTML entity decoding, and made ASS `\p` drawing tag detection case-insensitive.
- Fixed Media3 `Extractor` resource leaks on probe/decoder failure, added full LPCM decoding (8/16/24/32-bit LE/BE and 32-bit float) for `AUDIO_RAW` tracks, and forwarded `KEY_MAX_INPUT_SIZE` to `MediaCodec`.
- Fixed async selection/analysis race conditions in `SyncViewModel`, backward-seek premature stop in `AudioPreviewPlayer`, duplicate window index handling in `FftCrossCorrelator`, and drift/nudge consistency in `SyncAnalyzer`.
- Improved UI accuracy: prevented "In sync" label when drift is present, added a dedicated drift-remaining verification badge, wrapped >3 Audio Check checkpoint chips in `FlowRow`, fixed buffering/idle subtitle styling, and preserved `ExportSrtSheet` across rotation.

## v1.4.1
- Fixed the bottom "Choose location & save" button in the Save `.srt` review sheet being hidden behind the system navigation bar by rendering `ExportSrtSheet` as an in-window overlay with `BackHandler` and direct Activity `statusBarsPadding` / `navigationBarsPadding`.
- Configured `enableEdgeToEdge` with dark transparent system bar styles so status and navigation bars match the dark UI even when the device is in Light Mode.
- Made the export confirmation button use `heightIn(min = 48.dp)` to prevent label clipping under large accessibility font scales.

## v1.4.0
- Added automatic multi-charset detection for legacy subtitle encodings (`Shift_JIS`, `EUC-KR`, `GBK`, `Big5`, `windows-1251`, `windows-1256`, `windows-1252`) and a collapsible encoding picker with a 3-line live preview.
- Stripped ASS/SSA vector drawing segments (`\p1`..`\pN` until `\p0`) so typesetting shapes no longer pollute dialogue cues, and skipped WebVTT `NOTE` comment blocks cleanly.
- Tightened `SubtitleCleaner` URL patterns, Indonesian ad keywords (avoiding false matches on `"iklan"` / `"menonton film"`), and intro/outro scoping for `LONG_BANNER` cues.
- Added adaptive extra checkpoint sampling (up to 2 additional non-overlapping windows on movies >= 15 min) when initial windows have low confidence or poor cluster agreement.
- Added automatic post-export `.srt` verification against cached audio envelopes (zero extra decoding) with a one-tap Re-verify button and an offline Diagnostics copy button.
- Calibrated joint-pooled correlation confidence (capped at 75% with an `Estimated (pooled)` badge and no artificial agreement boost).
- Improved `SrtExporter` clamping (dropping negative-shifted stubs shorter than 200 ms) and reported exact written/removed cue counts.
- Optimized `FftCrossCorrelator` and `FpsStretchMatcher` by caching per-window coarse audio FFTs across FPS candidates, using O(1) prefix-sum Pearson refinement, and replacing boxed `HashMap` buffers with flat `DoubleArray`s.
- Kept the screen awake (`keepScreenOn`) during active analysis, unified subtitle cleaning across preview and analysis, keyed recent history by audio track (up to 20 entries), replaced the preview stop icon with a rounded square, and hardened `AndroidManifest.xml` (`allowBackup="false"`, predictive back).
- Automated `CHANGELOG.md` extraction and dynamic APK sizes in GitHub Release workflows, added `:app:lintRelease` to CI, and expanded unit test coverage to 39 tests.

## v1.3.2
- Fixed false auto-enabled FPS drift stretch in `SyncAnalyzer.assessDrift` when only 2 checkpoints passed or when the shift was a non-FPS scene cut (`DIFFERENT_CUT` / `GRADUAL_SHIFT`), preventing exported `.srt` timestamps from being warped out of sync.
- Made `FpsStretchMatcher` outlier-resistant using cluster-based scoring so a single noisy checkpoint cannot strip 1.0x agreement and trigger a false cinema FPS match.
- Upgraded `SyncAnalyzer.computeConsensusOffset` to use squared-confidence cluster voting with strongest-peak fallback instead of letting weak noise peaks win via median.
- Added lightweight joint multi-window pooled correlation (`FftCrossCorrelator.correlateJointly`, ~2–5 ms CPU, zero extra decoding) to lock onto the true shared offset when individual windows have heavy BGM/SFX noise.
- Anchored extracted `SampleWindow.durationMs` to the exact decoded envelope length in `AudioSampleExtractor`.

## v1.3.1
- Fixed `AudioPreviewPlayer` getting stuck showing "Loading audio..." when replaying a buffered clip, and added automatic recovery from `STATE_IDLE` after playback errors.
- Added cooperative cancellation inside `AudioSampleExtractor` so tapping Cancel stops background audio decoding immediately.
- Moved pending `.srt` export options into `SyncViewModel` so saving survives configuration changes / activity recreation while Android's document picker is open.
- Auto-pause 10-second audio preview when the app moves to the background (`ON_STOP`).
- Hardened SRT/VTT parsing against malformed files missing blank lines between cues, and fixed `DefaultLocale` Lint warnings in `DocumentMetaReader`.

## v1.3.0
- Added `FpsStretchMatcher` to automatically detect and lock standard cinema FPS mismatches (`23.976 ↔ 25`, `24 ↔ 25`, `23.976 ↔ 24`) directly on extracted audio envelopes (< 30 ms, zero extra decoding), eliminating the ~3.8s intra-window smear and ±120s range limit on PAL/NTSC mismatches.
- Fixed subtitle-time axis bug in drift regression (`SyncAnalyzer` now fits weighted least-squares directly on `t_sub = centerMs - offsetMs` so exported `.srt` timestamps match to within ~20–45 ms from start to finish).
- Hardened drift detection against noisy low-confidence outlier windows and non-linear scene cuts (only pre-selects linear stretch when checkpoints genuinely follow a straight FPS line).
- Enabled drift-aware live subtitle positioning in 10-second Audio Check preview when linear FPS drift is detected.

## v1.2.2
- Fixed scrolling inside the Save `.srt` review sheet by replacing Material 3's draggable `ModalBottomSheet` with a bottom-anchored sheet dialog containing a single unified `LazyColumn` (eliminates gesture conflicts with drag-to-dismiss and gives the list full available height).

## v1.2.1
- Fixed VAD accuracy regression from v1.2.0 by restoring full native sample-rate filtering (eliminates high-frequency music aliasing on 48 kHz tracks) and 90-second sampling windows.
- Anchored extracted audio envelopes to the actual first decoded PCM presentation timestamp (PTS) to eliminate seek-boundary offset drift.
- Prioritized the Center (FC) dialogue channel on 5.1 / 7.1 surround tracks over LFE and surround BGM channels.
- Added GPL-3.0 `LICENSE`, Android CI workflow, and GitHub issue templates.

## v1.2.0
- Added synced `.srt` export via Android SAF with interactive ad/credit deletion review (uncheck false positives to keep them) and optional linear FPS drift correction.
- Added Concept C vector app icon with Android 13+ monochrome themed icon support.
- Added local recent check history and instant Indonesian / English UI toggle.
- Added reusable PCM scratch buffers to reduce GC overhead.
- Added compact `arm64-v8a` APK split (~2.9 MB) and persistent CI signing keystore support.

## v1.1.0
- Added multi-audio track picker (e.g. English / Hindi) for both sync analysis and 10-second audio preview.
- Redesigned UI with matte dark palette, monospace timecode readouts, 3-column checkpoint strip, and segmented fine-tune controls.

## v1.0.1
- Fixed FFmpeg `DecoderInputBuffer` flip bug causing `Error decoding (see logcat)` on software-decoded tracks.

## v1.0.0
- Initial release with multi-point audio-only sync detection, ad/credit/SDH filtering, Media3 + FFmpeg audio decoder fallback, drift detection, and 10-second audio preview.
