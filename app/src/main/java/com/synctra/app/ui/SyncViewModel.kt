package com.synctra.app.ui

import android.app.Application
import android.net.Uri
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.synctra.app.data.file.DocumentMetaReader
import com.synctra.app.data.file.SelectedFileInfo
import com.synctra.app.data.media.AudioPreviewPlayer
import com.synctra.app.data.media.AudioPreviewState
import com.synctra.app.data.media.AudioSampleExtractor
import com.synctra.app.domain.model.CleanedSubtitleSet
import com.synctra.app.domain.model.MediaTrackInfo
import com.synctra.app.domain.model.ParsedSubtitleFile
import com.synctra.app.domain.model.SubtitleCue
import com.synctra.app.domain.model.SyncProgress
import com.synctra.app.domain.model.SyncResult
import com.synctra.app.domain.subtitle.SubtitleCleaner
import com.synctra.app.domain.subtitle.SubtitleParser
import com.synctra.app.domain.sync.FftCrossCorrelator
import com.synctra.app.domain.sync.SyncAnalyzer
import com.synctra.app.domain.sync.WindowPlanner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SynctraUiState(
    val videoFile: SelectedFileInfo? = null,
    val videoMeta: MediaTrackInfo? = null,
    val subtitleFile: SelectedFileInfo? = null,
    val parsedSubtitle: ParsedSubtitleFile? = null,
    val cleanedPreviewStats: CleanedSubtitleSet? = null,
    val isAnalyzing: Boolean = false,
    val progress: SyncProgress? = null,
    val result: SyncResult? = null,
    val manualNudgeMs: Long = 0L,
    val selectedPreviewWindowIndex: Int = 0,
    val errorMessage: String? = null,
    val previewState: AudioPreviewState = AudioPreviewState(),
    val activePreviewSubtitle: String? = null,
) {
    val canStartAnalysis: Boolean
        get() = videoFile != null && subtitleFile != null && !isAnalyzing

    val effectiveOffsetMs: Long
        get() = (result?.offsetMs ?: 0L) + manualNudgeMs
}

class SyncViewModel(application: Application) : AndroidViewModel(application) {

    private val appContext = application.applicationContext
    private val audioExtractor = AudioSampleExtractor(appContext)
    private val previewPlayer = AudioPreviewPlayer(appContext, viewModelScope)

    private val internalState = MutableStateFlow(SynctraUiState())
    private var analysisJob: Job? = null

    val uiState: StateFlow<SynctraUiState> = combine(
        internalState,
        previewPlayer.state,
    ) { state, preview ->
        val activeLine = if (state.result != null && state.parsedSubtitle != null) {
            findActiveSubtitleText(
                cues = state.parsedSubtitle.allCues,
                audioPosMs = preview.currentPositionMs,
                appliedOffsetMs = state.effectiveOffsetMs,
            )
        } else {
            null
        }
        state.copy(
            previewState = preview,
            activePreviewSubtitle = activeLine,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = SynctraUiState(),
    )

    fun onVideoSelected(uri: Uri) {
        previewPlayer.stop()
        analysisJob?.cancel()

        viewModelScope.launch {
            val info = withContext(Dispatchers.IO) {
                DocumentMetaReader.readInfo(appContext, uri)
            }
            internalState.update {
                it.copy(
                    videoFile = info,
                    videoMeta = null,
                    result = null,
                    manualNudgeMs = 0L,
                    errorMessage = null,
                )
            }
            // Quick background probe so user immediately sees audio codec & duration
            runCatching {
                withContext(Dispatchers.IO) { audioExtractor.probeMedia(uri) }
            }.onSuccess { meta ->
                internalState.update { state ->
                    if (state.videoFile?.uri == uri) state.copy(videoMeta = meta) else state
                }
            }
        }
    }

    fun onSubtitleSelected(uri: Uri) {
        previewPlayer.stop()
        analysisJob?.cancel()

        viewModelScope.launch {
            try {
                val (info, parsed, cleaned) = withContext(Dispatchers.IO) {
                    val fileInfo = DocumentMetaReader.readInfo(appContext, uri)
                    val bytes = DocumentMetaReader.readAllBytes(appContext, uri)
                    val parsedSub = SubtitleParser.parse(bytes, fileInfo.displayName)
                    if (parsedSub.allCues.isEmpty()) {
                        throw IllegalArgumentException(
                            "Tidak ada baris dialog yang valid di file subtitle ini. Pastikan formatnya .srt, .ass, atau .vtt."
                        )
                    }
                    val cleanedSub = SubtitleCleaner.clean(
                        parsedSub,
                        mediaDurationMs = internalState.value.videoMeta?.durationMs ?: 0L,
                    )
                    Triple(fileInfo, parsedSub, cleanedSub)
                }

                internalState.update {
                    it.copy(
                        subtitleFile = info,
                        parsedSubtitle = parsed,
                        cleanedPreviewStats = cleaned,
                        result = null,
                        manualNudgeMs = 0L,
                        errorMessage = null,
                    )
                }
            } catch (e: Exception) {
                internalState.update {
                    it.copy(
                        errorMessage = e.localizedMessage ?: "Gagal membaca file subtitle.",
                    )
                }
            }
        }
    }

    fun startAnalysis() {
        val current = internalState.value
        val videoFile = current.videoFile ?: return
        val subtitleFile = current.subtitleFile ?: return

        previewPlayer.stop()
        analysisJob?.cancel()

        analysisJob = viewModelScope.launch {
            val startedAt = SystemClock.elapsedRealtime()
            internalState.update {
                it.copy(
                    isAnalyzing = true,
                    result = null,
                    manualNudgeMs = 0L,
                    errorMessage = null,
                    progress = SyncProgress(
                        stepTitle = "Menyiapkan analisis...",
                        stepDetail = "Membaca metadata audio film & membersihkan iklan subtitle",
                        fraction = 0.08f,
                    ),
                )
            }

            try {
                val result = withContext(Dispatchers.Default) {
                    // 1. Ensure video metadata is probed
                    val mediaInfo = current.videoMeta ?: audioExtractor.probeMedia(videoFile.uri)

                    // 2. Parse & clean subtitles (filter ads, credits, URLs, SDH, intro/outro edges)
                    val parsed = current.parsedSubtitle ?: run {
                        val bytes = DocumentMetaReader.readAllBytes(appContext, subtitleFile.uri)
                        SubtitleParser.parse(bytes, subtitleFile.displayName)
                    }
                    if (parsed.allCues.size < 3) {
                        throw IllegalStateException("File subtitle terlalu pendek atau tidak memiliki cukup dialog.")
                    }
                    val cleaned = SubtitleCleaner.clean(parsed, mediaInfo.durationMs)
                    if (cleaned.dialogueCues.size < 3) {
                        throw IllegalStateException("Tidak cukup dialog murni setelah filter iklan & kredit.")
                    }

                    // 3. Plan interior multi-point sampling windows
                    val windows = WindowPlanner.planWindows(
                        mediaDurationMs = mediaInfo.durationMs,
                        dialogueCues = cleaned.dialogueCues,
                    )
                    if (windows.isEmpty()) {
                        throw IllegalStateException("Durasi video tidak terbaca sehingga jendela sampel tidak dapat dibuat.")
                    }

                    // 4. Extract audio-only speech activity envelopes (dual-path: MediaCodec + FFmpeg fallback)
                    val extractedWindows = audioExtractor.extractWindows(
                        uri = videoFile.uri,
                        windows = windows,
                    ) { idx, total, win ->
                        val frac = 0.15f + (idx.toFloat() / total.coerceAtLeast(1)) * 0.60f
                        internalState.update {
                            it.copy(
                                videoMeta = mediaInfo,
                                cleanedPreviewStats = cleaned,
                                progress = SyncProgress(
                                    stepTitle = "Mengambil sampel audio (${idx + 1}/$total)...",
                                    stepDetail = "Menganalisis bagian ${win.label} (audio-only, skip video)",
                                    fraction = frac,
                                ),
                            )
                        }
                    }

                    // 5. Correlate each window against cleaned subtitle dialogue envelope
                    internalState.update {
                        it.copy(
                            progress = SyncProgress(
                                stepTitle = "Mencocokkan pola dialog...",
                                stepDetail = "Menghitung korelasi suara vs subtitle & mendeteksi drift FPS",
                                fraction = 0.85f,
                            ),
                        )
                    }

                    var windowSyncResults = extractedWindows.map { ext ->
                        FftCrossCorrelator.correlateWindow(
                            window = ext.window,
                            audioEnvelope = ext.speechEnvelope,
                            dialogueCues = cleaned.dialogueCues,
                            maxLagMs = 60_000L,
                        )
                    }

                    // Auto-expand search window to +/- 120 seconds if initial +/- 60s match was weak
                    val maxConf = windowSyncResults.maxOfOrNull { it.confidence } ?: 0f
                    if (maxConf < 0.35f) {
                        internalState.update {
                            it.copy(
                                progress = SyncProgress(
                                    stepTitle = "Memperluas pencarian offset (±2 menit)...",
                                    stepDetail = "Mencari kemungkinan selisih besar hingga ±120 detik",
                                    fraction = 0.92f,
                                ),
                            )
                        }
                        val wideResults = extractedWindows.map { ext ->
                            FftCrossCorrelator.correlateWindow(
                                window = ext.window,
                                audioEnvelope = ext.speechEnvelope,
                                dialogueCues = cleaned.dialogueCues,
                                maxLagMs = 120_000L,
                            )
                        }
                        val wideMaxConf = wideResults.maxOfOrNull { it.confidence } ?: 0f
                        if (wideMaxConf > maxConf + 0.05f) {
                            windowSyncResults = wideResults
                        }
                    }

                    val usedFfmpeg = extractedWindows.any { it.usedFfmpegDecoder }
                    val elapsedMs = (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(1L)

                    SyncAnalyzer.aggregate(
                        windowResults = windowSyncResults,
                        cleanedSubtitles = cleaned,
                        audioCodecUsed = mediaInfo.audioCodecLabel,
                        usedFfmpegDecoder = usedFfmpeg,
                        analysisDurationMs = elapsedMs,
                    )
                }

                // Default Test Dengar checkpoint to the highest-confidence window
                val bestWinIdx = result.windowResults
                    .withIndex()
                    .maxByOrNull { it.value.confidence }
                    ?.index ?: 0

                internalState.update {
                    it.copy(
                        isAnalyzing = false,
                        progress = null,
                        result = result,
                        selectedPreviewWindowIndex = bestWinIdx,
                        errorMessage = null,
                    )
                }
            } catch (_: CancellationException) {
                internalState.update {
                    it.copy(
                        isAnalyzing = false,
                        progress = null,
                    )
                }
            } catch (e: Exception) {
                internalState.update {
                    it.copy(
                        isAnalyzing = false,
                        progress = null,
                        errorMessage = e.localizedMessage
                            ?: "Terjadi kendala saat menganalisis file. Pastikan file video tidak rusak.",
                    )
                }
            }
        }
    }

    fun cancelAnalysis() {
        analysisJob?.cancel()
        analysisJob = null
        internalState.update { it.copy(isAnalyzing = false, progress = null) }
    }

    fun onSelectPreviewWindow(index: Int) {
        val state = internalState.value
        val result = state.result ?: return
        if (index !in result.windowResults.indices) return

        internalState.update { it.copy(selectedPreviewWindowIndex = index) }
        if (previewPlayer.state.value.isPlaying) {
            startPreviewForWindow(index)
        }
    }

    fun toggleAudioPreview() {
        if (previewPlayer.state.value.isPlaying) {
            previewPlayer.stop()
        } else {
            startPreviewForWindow(internalState.value.selectedPreviewWindowIndex)
        }
    }

    private fun startPreviewForWindow(windowIndex: Int) {
        val state = internalState.value
        val videoUri = state.videoFile?.uri ?: return
        val result = state.result ?: return
        val cues = state.cleanedPreviewStats?.dialogueCues
            ?: state.parsedSubtitle?.allCues
            ?: emptyList()

        val selectedWr = result.windowResults.getOrNull(windowIndex)
        val startMs = if (selectedWr != null) {
            SyncAnalyzer.pickPreviewTimestamp(selectedWr, state.effectiveOffsetMs, cues)
        } else {
            result.recommendedPreviewAudioMs
        }

        previewPlayer.playClip(
            videoUri = videoUri,
            startMs = startMs,
            durationMs = AudioPreviewPlayer.DEFAULT_CLIP_DURATION_MS,
        )
    }

    fun nudgeOffset(deltaMs: Long) {
        internalState.update { it.copy(manualNudgeMs = it.manualNudgeMs + deltaMs) }
    }

    fun resetManualNudge() {
        internalState.update { it.copy(manualNudgeMs = 0L) }
    }

    fun dismissError() {
        internalState.update { it.copy(errorMessage = null) }
    }

    override fun onCleared() {
        analysisJob?.cancel()
        previewPlayer.release()
        super.onCleared()
    }

    private fun findActiveSubtitleText(
        cues: List<SubtitleCue>,
        audioPosMs: Long,
        appliedOffsetMs: Long,
    ): String? {
        // Since newSubTime = originalSubTime + appliedOffsetMs,
        // the original subtitle timestamp matching audioPosMs is: audioPosMs - appliedOffsetMs
        val targetSubMs = audioPosMs - appliedOffsetMs
        val active = cues.filter { targetSubMs in it.startMs..it.endMs }
        if (active.isEmpty()) return null
        return active.joinToString("\n") { it.cleanText }
    }
}
