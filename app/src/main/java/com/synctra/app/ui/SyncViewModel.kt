package com.synctra.app.ui

import android.app.Application
import android.net.Uri
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.synctra.app.data.file.DocumentMetaReader
import com.synctra.app.data.file.SelectedFileInfo
import com.synctra.app.data.history.SyncHistoryStore
import com.synctra.app.data.media.AudioPreviewPlayer
import com.synctra.app.data.media.AudioPreviewState
import com.synctra.app.data.media.AudioSampleExtractor
import com.synctra.app.domain.model.AudioTrackOption
import com.synctra.app.domain.model.CleanedSubtitleSet
import com.synctra.app.domain.model.DriftSeverity
import com.synctra.app.domain.model.ExportVerificationResult
import com.synctra.app.domain.model.MediaTrackInfo
import com.synctra.app.domain.model.ParsedSubtitleFile
import com.synctra.app.domain.model.SubtitleCue
import com.synctra.app.domain.model.SyncHistoryEntry
import com.synctra.app.domain.model.SyncProgress
import com.synctra.app.domain.model.SyncResult
import com.synctra.app.domain.subtitle.SrtExporter
import com.synctra.app.domain.subtitle.SubtitleCleaner
import com.synctra.app.domain.subtitle.SubtitleEncodingOption
import com.synctra.app.domain.subtitle.SubtitleParser
import com.synctra.app.domain.sync.SyncAnalyzer
import com.synctra.app.domain.sync.SyncDiagnosticsFormatter
import com.synctra.app.domain.sync.WindowAudioInput
import com.synctra.app.domain.sync.WindowPlanner
import com.synctra.app.domain.sync.WindowSyncMatcher
import com.synctra.app.ui.components.ExportSrtOptions
import com.synctra.app.ui.i18n.AppLanguage
import com.synctra.app.ui.i18n.AppStringsProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SynctraUiState(
    val language: AppLanguage = AppLanguage.ID,
    val videoFile: SelectedFileInfo? = null,
    val videoMeta: MediaTrackInfo? = null,
    val isProbingVideo: Boolean = false,
    val selectedAudioTrackIndex: Int = 0,
    val subtitleFile: SelectedFileInfo? = null,
    val selectedEncoding: SubtitleEncodingOption = SubtitleEncodingOption.AUTO,
    val parsedSubtitle: ParsedSubtitleFile? = null,
    val cleanedPreviewStats: CleanedSubtitleSet? = null,
    val isAnalyzing: Boolean = false,
    val progress: SyncProgress? = null,
    val result: SyncResult? = null,
    val manualNudgeMs: Long = 0L,
    val selectedPreviewWindowIndex: Int = 0,
    val exportVerification: ExportVerificationResult? = null,
    val errorMessage: String? = null,
    val previewState: AudioPreviewState = AudioPreviewState(),
    val activePreviewSubtitle: String? = null,
    val recentHistory: List<SyncHistoryEntry> = emptyList(),
) {
    val canStartAnalysis: Boolean
        get() = videoFile != null && subtitleFile != null && !isAnalyzing && !isProbingVideo

    val selectedAudioTrack: AudioTrackOption?
        get() = videoMeta?.audioTracks?.getOrNull(selectedAudioTrackIndex)
            ?: videoMeta?.primaryAudio

    val effectiveOffsetMs: Long
        get() = (result?.offsetMs ?: 0L) + manualNudgeMs
}

class SyncViewModel(application: Application) : AndroidViewModel(application) {

    private val appContext = application.applicationContext
    private val audioExtractor = AudioSampleExtractor(appContext)
    private val previewPlayer = AudioPreviewPlayer(appContext, viewModelScope)
    private val historyStore = SyncHistoryStore(appContext)

    private val internalState = MutableStateFlow(
        SynctraUiState(
            language = AppLanguage.fromCode(historyStore.loadLanguageCode()),
            recentHistory = historyStore.loadHistory(),
        )
    )
    private var analysisJob: Job? = null
    private var videoProbeJob: Job? = null
    private var subtitleLoadJob: Job? = null
    @Volatile
    private var analysisGeneration: Long = 0L
    private var pendingExportOptions: ExportSrtOptions? = null
    private var lastSubtitleBytes: ByteArray? = null
    private var lastExtractedWindows: List<WindowAudioInput> = emptyList()
    private var lastExportedSrtUri: Uri? = null

    val uiState: StateFlow<SynctraUiState> = combine(
        internalState,
        previewPlayer.state,
    ) { state, preview ->
        val activeLine = if (
            preview.isPlaying &&
            !preview.isBuffering &&
            state.result != null &&
            state.parsedSubtitle != null
        ) {
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
        initialValue = internalState.value,
    )

    fun toggleLanguage() {
        val next = if (internalState.value.language == AppLanguage.ID) AppLanguage.EN else AppLanguage.ID
        historyStore.saveLanguageCode(next.code)
        internalState.update { it.copy(language = next) }
    }

    private fun recomputeCleaning(
        parsed: ParsedSubtitleFile?,
        mediaDurationMs: Long,
    ): CleanedSubtitleSet? = parsed?.let { SubtitleCleaner.clean(it, mediaDurationMs = mediaDurationMs) }

    fun onVideoSelected(uri: Uri) {
        previewPlayer.stop()
        analysisGeneration++
        analysisJob?.cancel()
        analysisJob = null
        videoProbeJob?.cancel()
        lastExtractedWindows = emptyList()
        lastExportedSrtUri = null

        videoProbeJob = viewModelScope.launch {
            val info = withContext(Dispatchers.IO) {
                DocumentMetaReader.readInfo(appContext, uri)
            }
            internalState.update {
                it.copy(
                    videoFile = info,
                    videoMeta = null,
                    isProbingVideo = true,
                    selectedAudioTrackIndex = 0,
                    isAnalyzing = false,
                    progress = null,
                    result = null,
                    manualNudgeMs = 0L,
                    exportVerification = null,
                    errorMessage = null,
                )
            }

            runCatching {
                withContext(Dispatchers.IO) { audioExtractor.probeMedia(uri) }
            }.onSuccess { meta ->
                internalState.update { state ->
                    if (state.videoFile?.uri == uri) {
                        state.copy(
                            videoMeta = meta,
                            isProbingVideo = false,
                            selectedAudioTrackIndex = meta.defaultAudioIndex,
                            cleanedPreviewStats = recomputeCleaning(state.parsedSubtitle, meta.durationMs)
                                ?: state.cleanedPreviewStats,
                        )
                    } else {
                        state
                    }
                }
            }.onFailure { err ->
                if (err is CancellationException) return@onFailure
                internalState.update { state ->
                    if (state.videoFile?.uri == uri) {
                        state.copy(
                            isProbingVideo = false,
                            errorMessage = err.localizedMessage ?: "Gagal membaca jalur audio dari video ini.",
                        )
                    } else {
                        state
                    }
                }
            }
        }
    }

    fun onAudioTrackSelected(audioIndex: Int) {
        val state = internalState.value
        val tracks = state.videoMeta?.audioTracks ?: return
        if (audioIndex !in tracks.indices || audioIndex == state.selectedAudioTrackIndex) return

        previewPlayer.stop()
        analysisGeneration++
        analysisJob?.cancel()
        analysisJob = null
        lastExtractedWindows = emptyList()
        lastExportedSrtUri = null
        internalState.update {
            it.copy(
                selectedAudioTrackIndex = audioIndex,
                isAnalyzing = false,
                progress = null,
                result = null,
                manualNudgeMs = 0L,
                exportVerification = null,
                errorMessage = null,
            )
        }
    }

    fun onSubtitleSelected(uri: Uri) {
        previewPlayer.stop()
        analysisGeneration++
        analysisJob?.cancel()
        analysisJob = null
        subtitleLoadJob?.cancel()
        lastExportedSrtUri = null

        subtitleLoadJob = viewModelScope.launch {
            try {
                val (info, bytes, parsed) = withContext(Dispatchers.IO) {
                    val fileInfo = DocumentMetaReader.readInfo(appContext, uri)
                    val rawBytes = DocumentMetaReader.readAllBytes(appContext, uri)
                    val parsedSub = SubtitleParser.parse(rawBytes, fileInfo.displayName)
                    if (parsedSub.allCues.isEmpty()) {
                        throw IllegalArgumentException(
                            "Tidak ada baris dialog yang valid. Pastikan formatnya .srt, .ass, atau .vtt."
                        )
                    }
                    Triple(fileInfo, rawBytes, parsedSub)
                }

                lastSubtitleBytes = bytes
                internalState.update { state ->
                    val cleaned = recomputeCleaning(parsed, state.videoMeta?.durationMs ?: 0L)
                    state.copy(
                        subtitleFile = info,
                        selectedEncoding = SubtitleEncodingOption.AUTO,
                        parsedSubtitle = parsed,
                        cleanedPreviewStats = cleaned,
                        isAnalyzing = false,
                        progress = null,
                        result = null,
                        manualNudgeMs = 0L,
                        exportVerification = null,
                        errorMessage = null,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                internalState.update {
                    it.copy(
                        isAnalyzing = false,
                        progress = null,
                        errorMessage = e.localizedMessage ?: "Gagal membaca file subtitle.",
                    )
                }
            }
        }
    }

    fun onSubtitleEncodingSelected(option: SubtitleEncodingOption) {
        val state = internalState.value
        val subFile = state.subtitleFile ?: return
        if (option == state.selectedEncoding) return

        previewPlayer.stop()
        analysisGeneration++
        analysisJob?.cancel()
        analysisJob = null
        subtitleLoadJob?.cancel()
        lastExportedSrtUri = null

        subtitleLoadJob = viewModelScope.launch {
            try {
                val parsed = withContext(Dispatchers.IO) {
                    val bytes = lastSubtitleBytes ?: DocumentMetaReader.readAllBytes(appContext, subFile.uri).also {
                        lastSubtitleBytes = it
                    }
                    val parsedSub = SubtitleParser.parse(
                        bytes = bytes,
                        fileName = subFile.displayName,
                        forcedCharsetName = option.charsetName,
                    )
                    if (parsedSub.allCues.isEmpty()) {
                        throw IllegalArgumentException(
                            "Tidak ada baris dialog yang valid dengan encoding ${option.label}."
                        )
                    }
                    parsedSub
                }

                internalState.update { cur ->
                    if (cur.subtitleFile?.uri != subFile.uri) {
                        cur
                    } else {
                        val cleaned = recomputeCleaning(parsed, cur.videoMeta?.durationMs ?: 0L)
                        cur.copy(
                            selectedEncoding = option,
                            parsedSubtitle = parsed,
                            cleanedPreviewStats = cleaned,
                            isAnalyzing = false,
                            progress = null,
                            result = null,
                            manualNudgeMs = 0L,
                            exportVerification = null,
                            errorMessage = null,
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                internalState.update { cur ->
                    if (cur.subtitleFile?.uri != subFile.uri) {
                        cur
                    } else {
                        cur.copy(
                            isAnalyzing = false,
                            progress = null,
                            errorMessage = e.localizedMessage ?: "Gagal membaca subtitle dengan encoding ${option.label}.",
                        )
                    }
                }
            }
        }
    }

    fun startAnalysis() {
        val current = internalState.value
        val videoFile = current.videoFile ?: return
        val subtitleFile = current.subtitleFile ?: return
        val chosenAudioIndex = current.selectedAudioTrackIndex
        val chosenEncoding = current.selectedEncoding
        val strings = AppStringsProvider.get(current.language)

        previewPlayer.stop()
        analysisJob?.cancel()
        val gen = ++analysisGeneration

        analysisJob = viewModelScope.launch {
            val startedAt = SystemClock.elapsedRealtime()
            internalState.update {
                if (analysisGeneration != gen) return@update it
                it.copy(
                    isAnalyzing = true,
                    result = null,
                    manualNudgeMs = 0L,
                    exportVerification = null,
                    errorMessage = null,
                    progress = SyncProgress(
                        stepTitle = strings.stepPreparing,
                        stepDetail = strings.stepPreparingDetail,
                        fraction = 0.08f,
                    ),
                )
            }

            try {
                val (result, analyzedTrackIndex, extractedInputs) = withContext(Dispatchers.Default) {
                    val mediaInfo = current.videoMeta ?: audioExtractor.probeMedia(videoFile.uri)
                    val selectedTrack = mediaInfo.audioTracks.getOrNull(chosenAudioIndex)
                        ?: mediaInfo.primaryAudio
                        ?: throw IllegalStateException("No audio track found.")

                    val parsed = current.parsedSubtitle ?: run {
                        val bytes = lastSubtitleBytes ?: DocumentMetaReader.readAllBytes(appContext, subtitleFile.uri).also {
                            lastSubtitleBytes = it
                        }
                        SubtitleParser.parse(bytes, subtitleFile.displayName, chosenEncoding.charsetName)
                    }
                    if (parsed.allCues.size < 3) {
                        throw IllegalStateException("Subtitle file has too few dialogue lines.")
                    }
                    val cleaned = recomputeCleaning(parsed, mediaInfo.durationMs)
                        ?: throw IllegalStateException("Failed to clean subtitle lines.")
                    if (cleaned.dialogueCues.size < 3) {
                        throw IllegalStateException("Not enough spoken dialogue after ad filtering.")
                    }

                    internalState.update {
                        if (analysisGeneration != gen) return@update it
                        it.copy(
                            videoMeta = mediaInfo,
                            parsedSubtitle = parsed,
                            cleanedPreviewStats = cleaned,
                        )
                    }

                    val windows = WindowPlanner.planWindows(
                        mediaDurationMs = mediaInfo.durationMs,
                        dialogueCues = cleaned.dialogueCues,
                    )
                    if (windows.isEmpty()) {
                        throw IllegalStateException("Could not determine video duration for sampling.")
                    }

                    var extractedWindows = audioExtractor.extractWindows(
                        uri = videoFile.uri,
                        windows = windows,
                        selectedAudioIndex = selectedTrack.audioIndex,
                        isCancelled = { !isActive || analysisGeneration != gen },
                    ) { idx, total, _ ->
                        val frac = 0.15f + (idx.toFloat() / total.coerceAtLeast(1)) * 0.55f
                        val winName = strings.windowShortLabel(idx, total)
                        internalState.update {
                            if (analysisGeneration != gen) return@update it
                            it.copy(
                                progress = SyncProgress(
                                    stepTitle = strings.stepSampling(idx + 1, total),
                                    stepDetail = "${selectedTrack.displayTitle} · $winName",
                                    fraction = frac,
                                ),
                            )
                        }
                    }

                    internalState.update {
                        if (analysisGeneration != gen) return@update it
                        it.copy(
                            progress = SyncProgress(
                                stepTitle = strings.stepCorrelating,
                                stepDetail = strings.stepCorrelatingDetail,
                                fraction = 0.78f,
                            ),
                        )
                    }

                    var audioInputs = extractedWindows.map { WindowAudioInput(it.window, it.speechEnvelope) }
                    var matchResult = WindowSyncMatcher.matchBest(
                        windows = audioInputs,
                        dialogueCues = cleaned.dialogueCues,
                        onExpandingSearch = {
                            internalState.update {
                                if (analysisGeneration != gen) return@update it
                                it.copy(
                                    progress = SyncProgress(
                                        stepTitle = strings.stepExpanding,
                                        stepDetail = strings.stepExpandingDetail,
                                        fraction = 0.84f,
                                    ),
                                )
                            }
                        },
                    )

                    // Adaptive extra sampling (F2): when initial checkpoints have weak confidence
                    // or poor agreement on a full movie, sample up to 2 extra interior windows.
                    if (SyncAnalyzer.shouldSampleExtraWindows(matchResult, mediaInfo.durationMs)) {
                        val extraWindows = WindowPlanner.planExtraWindows(
                            mediaDurationMs = mediaInfo.durationMs,
                            dialogueCues = cleaned.dialogueCues,
                            existingWindows = extractedWindows.map { it.window },
                            maxExtra = 2,
                        )
                        if (extraWindows.isNotEmpty()) {
                            internalState.update {
                                if (analysisGeneration != gen) return@update it
                                it.copy(
                                    progress = SyncProgress(
                                        stepTitle = strings.stepExtraSampling,
                                        stepDetail = strings.stepExtraSamplingDetail,
                                        fraction = 0.86f,
                                    ),
                                )
                            }
                            val extraExtracted = audioExtractor.extractWindows(
                                uri = videoFile.uri,
                                windows = extraWindows,
                                selectedAudioIndex = selectedTrack.audioIndex,
                                isCancelled = { !isActive || analysisGeneration != gen },
                            ) { idx, total, _ ->
                                val frac = 0.87f + (idx.toFloat() / total.coerceAtLeast(1)) * 0.08f
                                internalState.update {
                                    if (analysisGeneration != gen) return@update it
                                    it.copy(
                                        progress = SyncProgress(
                                            stepTitle = strings.stepExtraSampling,
                                            stepDetail = "${selectedTrack.displayTitle} · +${idx + 1}/$total",
                                            fraction = frac,
                                        ),
                                    )
                                }
                            }
                            if (extraExtracted.isNotEmpty()) {
                                val combinedSorted = (extractedWindows + extraExtracted)
                                    .sortedBy { it.window.startMs }
                                    .mapIndexed { newIdx, item ->
                                        item.copy(window = item.window.copy(index = newIdx))
                                    }
                                extractedWindows = combinedSorted
                                audioInputs = combinedSorted.map { WindowAudioInput(it.window, it.speechEnvelope) }
                                matchResult = WindowSyncMatcher.matchBest(
                                    windows = audioInputs,
                                    dialogueCues = cleaned.dialogueCues,
                                )
                            }
                        }
                    }

                    val usedFfmpeg = extractedWindows.any { it.usedFfmpegDecoder }
                    val elapsedMs = (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(1L)

                    val aggregated = SyncAnalyzer.aggregate(
                        windowResults = matchResult.windowResults,
                        cleanedSubtitles = cleaned,
                        audioCodecUsed = selectedTrack.technicalSummary,
                        audioTrackTitle = selectedTrack.displayTitle,
                        usedFfmpegDecoder = usedFfmpeg,
                        analysisDurationMs = elapsedMs,
                        usedJointPooling = matchResult.usedJointPooling,
                    )
                    Triple(aggregated, selectedTrack.audioIndex, audioInputs)
                }

                if (analysisGeneration != gen) return@launch

                lastExtractedWindows = extractedInputs
                lastExportedSrtUri = null

                val bestWinIdx = result.windowResults
                    .withIndex()
                    .maxByOrNull { it.value.confidence }
                    ?.index ?: 0

                val updatedHistory = historyStore.addEntry(
                    SyncHistoryEntry(
                        videoName = videoFile.displayName,
                        subtitleName = subtitleFile.displayName,
                        audioTrackTitle = result.audioTrackTitle,
                        offsetMs = result.offsetMs,
                        hasDrift = result.drift.severity != DriftSeverity.NONE,
                        checkedAtEpochMs = System.currentTimeMillis(),
                        audioTrackIndex = analyzedTrackIndex,
                    )
                )

                internalState.update {
                    if (analysisGeneration != gen) return@update it
                    it.copy(
                        isAnalyzing = false,
                        progress = null,
                        result = result,
                        selectedPreviewWindowIndex = bestWinIdx,
                        recentHistory = updatedHistory,
                        errorMessage = null,
                    )
                }
            } catch (_: CancellationException) {
                if (analysisGeneration == gen) {
                    internalState.update {
                        it.copy(isAnalyzing = false, progress = null)
                    }
                }
            } catch (e: Exception) {
                if (analysisGeneration == gen) {
                    internalState.update {
                        it.copy(
                            isAnalyzing = false,
                            progress = null,
                            errorMessage = e.localizedMessage ?: "Failed to analyze files.",
                        )
                    }
                }
            }
        }
    }

    fun cancelAnalysis() {
        analysisGeneration++
        analysisJob?.cancel()
        analysisJob = null
        internalState.update { it.copy(isAnalyzing = false, progress = null) }
    }

    fun clearHistory() {
        historyStore.clearHistory()
        internalState.update { it.copy(recentHistory = emptyList()) }
    }

    fun suggestedExportFileName(): String =
        SrtExporter.suggestedOutputFileName(internalState.value.subtitleFile?.displayName)

    fun prepareExport(options: ExportSrtOptions) {
        pendingExportOptions = options
    }

    fun consumePendingExport(
        destinationUri: Uri?,
        onSaved: (keptCount: Int, removedCount: Int) -> Unit,
    ) {
        val options = pendingExportOptions
        pendingExportOptions = null
        if (destinationUri != null && options != null) {
            exportSyncedSrt(destinationUri, options, onSaved)
        }
    }

    fun exportSyncedSrt(
        destinationUri: Uri,
        options: ExportSrtOptions,
        onSaved: (keptCount: Int, removedCount: Int) -> Unit,
    ) {
        val state = internalState.value
        val parsed = state.parsedSubtitle ?: return
        val targetResult = state.result ?: return
        val mediaDurationMs = state.videoMeta?.durationMs ?: 0L
        val cachedWindows = lastExtractedWindows

        viewModelScope.launch {
            try {
                val (keptCount, removedCount, verification) = withContext(Dispatchers.IO) {
                    val exportResult = SrtExporter.exportSrtWithStats(
                        allCues = parsed.allCues,
                        offsetMs = state.effectiveOffsetMs,
                        excludedCueIndices = options.excludedCueIndices,
                    )
                    DocumentMetaReader.writeUtf8Text(appContext, destinationUri, exportResult.content)
                    val removed = exportResult.excludedCount + exportResult.droppedByShiftCount
                    val verify = runCatching {
                        verifyExportedSrtBytes(
                            srtBytes = DocumentMetaReader.readAllBytes(appContext, destinationUri),
                            cachedWindows = cachedWindows,
                            mediaDurationMs = mediaDurationMs,
                        )
                    }.getOrNull()
                    Triple(exportResult.writtenCount, removed, verify)
                }
                if (internalState.value.result === targetResult) {
                    lastExportedSrtUri = destinationUri
                    internalState.update { cur ->
                        if (cur.result === targetResult) cur.copy(exportVerification = verification) else cur
                    }
                }
                onSaved(keptCount, removedCount)
            } catch (e: Exception) {
                internalState.update {
                    it.copy(errorMessage = e.localizedMessage ?: "Failed to save .srt file.")
                }
            }
        }
    }

    fun reverifyLastExport() {
        val state = internalState.value
        val targetResult = state.result ?: return
        val uri = lastExportedSrtUri ?: return
        val cachedWindows = lastExtractedWindows
        if (cachedWindows.isEmpty()) return
        val mediaDurationMs = state.videoMeta?.durationMs ?: 0L

        viewModelScope.launch {
            val verify = withContext(Dispatchers.IO) {
                runCatching {
                    val bytes = DocumentMetaReader.readAllBytes(appContext, uri)
                    verifyExportedSrtBytes(bytes, cachedWindows, mediaDurationMs)
                }.getOrNull()
            }
            if (verify != null) {
                internalState.update { cur ->
                    if (cur.result === targetResult) cur.copy(exportVerification = verify) else cur
                }
            }
        }
    }

    fun buildDiagnosticsText(): String? {
        val state = internalState.value
        val result = state.result ?: return null
        return SyncDiagnosticsFormatter.format(
            result = result,
            videoName = state.videoFile?.displayName,
            subtitleName = state.subtitleFile?.displayName,
            encodingName = state.parsedSubtitle?.encodingName,
            manualNudgeMs = state.manualNudgeMs,
            verification = state.exportVerification,
        )
    }

    private fun verifyExportedSrtBytes(
        srtBytes: ByteArray,
        cachedWindows: List<WindowAudioInput>,
        mediaDurationMs: Long,
    ): ExportVerificationResult? {
        if (cachedWindows.isEmpty()) return null
        val reparsed = SubtitleParser.parse(srtBytes, "exported.srt")
        if (reparsed.allCues.size < 3) return null
        val cleaned = SubtitleCleaner.clean(reparsed, mediaDurationMs = mediaDurationMs)
        if (cleaned.dialogueCues.size < 3) return null

        val match = WindowSyncMatcher.matchBest(
            windows = cachedWindows,
            dialogueCues = cleaned.dialogueCues,
        )
        val aggregated = SyncAnalyzer.aggregate(
            windowResults = match.windowResults,
            cleanedSubtitles = cleaned,
            audioCodecUsed = "",
            usedFfmpegDecoder = false,
            analysisDurationMs = 1L,
            usedJointPooling = match.usedJointPooling,
        )
        return ExportVerificationResult(
            residualOffsetMs = aggregated.offsetMs,
            confidence = aggregated.confidence,
            hasDrift = aggregated.drift.severity != DriftSeverity.NONE,
            verifiedCuesCount = reparsed.allCues.size,
        )
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

    fun stopAudioPreview() {
        previewPlayer.stop()
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
            SyncAnalyzer.pickPreviewTimestamp(
                window = selectedWr,
                offsetMs = state.effectiveOffsetMs,
                cues = cues,
            )
        } else {
            result.recommendedPreviewAudioMs
        }

        previewPlayer.playClip(
            videoUri = videoUri,
            startMs = startMs,
            selectedAudioIndex = state.selectedAudioTrackIndex,
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
        analysisGeneration++
        videoProbeJob?.cancel()
        subtitleLoadJob?.cancel()
        analysisJob?.cancel()
        previewPlayer.release()
        super.onCleared()
    }

    private fun findActiveSubtitleText(
        cues: List<SubtitleCue>,
        audioPosMs: Long,
        appliedOffsetMs: Long,
    ): String? {
        val targetSubMs = audioPosMs - appliedOffsetMs
        val active = cues.filter { targetSubMs in it.startMs..it.endMs }
        if (active.isEmpty()) return null
        return active.joinToString("\n") { it.cleanText }
    }
}
