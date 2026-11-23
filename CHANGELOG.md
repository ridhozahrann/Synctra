# Changelog

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
