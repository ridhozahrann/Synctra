package com.synctra.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.synctra.app.data.file.DocumentMetaReader
import com.synctra.app.ui.components.AudioPreviewCard
import com.synctra.app.ui.components.ExportSrtSheet
import com.synctra.app.ui.components.MediaInputSheet
import com.synctra.app.ui.components.RecentHistoryCard
import com.synctra.app.ui.components.ResultCard
import com.synctra.app.ui.components.formatClock
import com.synctra.app.ui.i18n.AppLanguage
import com.synctra.app.ui.i18n.AppStringsProvider
import com.synctra.app.ui.theme.SynctraColors
import kotlinx.coroutines.launch

@Composable
fun SynctraScreen(
    viewModel: SyncViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val strings = remember(state.language) { AppStringsProvider.get(state.language) }
    val snackbarHostState = remember { SnackbarHostState() }
    val clipboard = LocalClipboardManager.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()

    var showExportSheet by remember { mutableStateOf(false) }

    // Keep screen awake during active audio extraction/correlation so long files on slow storage don't sleep
    DisposableEffect(view, state.isAnalyzing) {
        if (state.isAnalyzing) {
            view.keepScreenOn = true
        }
        onDispose {
            view.keepScreenOn = false
        }
    }

    // Pause audio preview automatically if user switches apps or presses Home
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        viewModel.stopAudioPreview()
    }

    val videoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) viewModel.onVideoSelected(uri)
    }

    val subtitlePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) viewModel.onSubtitleSelected(uri)
    }

    val createSrtLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/x-subrip"),
    ) { uri ->
        viewModel.consumePendingExport(uri) { kept, removed ->
            scope.launch {
                snackbarHostState.showSnackbar(strings.savedSrtToast(kept, removed))
            }
        }
    }

    fun copyWithToast(value: String, label: String) {
        clipboard.setText(AnnotatedString(value))
        scope.launch {
            snackbarHostState.showSnackbar(strings.copiedToast(label))
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            modifier = Modifier
                .fillMaxSize()
                .background(SynctraColors.Background),
            containerColor = SynctraColors.Background,
            snackbarHost = { SnackbarHost(snackbarHostState) },
        ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Minimal header + subtle ID/EN toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Synctra",
                        fontSize = 26.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = SynctraColors.InkPrimary,
                        letterSpacing = (-0.6).sp,
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = strings.subtitleTagline,
                        fontSize = 13.sp,
                        color = SynctraColors.InkMuted,
                    )
                }

                Surface(
                    onClick = { viewModel.toggleLanguage() },
                    shape = RoundedCornerShape(8.dp),
                    color = SynctraColors.Surface,
                    border = BorderStroke(1.dp, SynctraColors.Hairline),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "ID",
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = if (state.language == AppLanguage.ID) FontWeight.Bold else FontWeight.Normal,
                            color = if (state.language == AppLanguage.ID) SynctraColors.Accent else SynctraColors.InkMuted,
                        )
                        Text(
                            text = "/",
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = SynctraColors.Hairline,
                        )
                        Text(
                            text = "EN",
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = if (state.language == AppLanguage.EN) FontWeight.Bold else FontWeight.Normal,
                            color = if (state.language == AppLanguage.EN) SynctraColors.Accent else SynctraColors.InkMuted,
                        )
                    }
                }
            }

            // Unified Input Panel
            val videoMetaLine = buildList {
                state.videoFile?.sizeBytes?.takeIf { it > 0 }?.let {
                    add(DocumentMetaReader.formatFileSize(it))
                }
                state.videoMeta?.durationMs?.takeIf { it > 0 }?.let {
                    add(formatClock(it))
                }
                if ((state.videoMeta?.audioTracks?.size ?: 0) == 1) {
                    state.selectedAudioTrack?.let { tr ->
                        add(tr.technicalSummary)
                    }
                }
            }.joinToString(" · ").ifBlank { null }

            val subtitleMetaLine = state.cleanedPreviewStats?.let { stats ->
                buildList {
                    add(stats.format.displayName)
                    add(strings.dialogsMeta(stats.dialogueCues.size))
                    if (stats.totalFilteredCount > 0) {
                        add(strings.filteredMeta(stats.totalFilteredCount))
                    }
                }.joinToString(" · ")
            }

            MediaInputSheet(
                strings = strings,
                videoName = state.videoFile?.displayName,
                videoMeta = videoMetaLine,
                isProbingVideo = state.isProbingVideo,
                audioTracks = state.videoMeta?.audioTracks.orEmpty(),
                selectedAudioIndex = state.selectedAudioTrackIndex,
                subtitleName = state.subtitleFile?.displayName,
                subtitleMeta = subtitleMetaLine,
                selectedEncoding = state.selectedEncoding,
                detectedEncodingName = state.parsedSubtitle?.encodingName,
                subtitlePreviewCues = state.cleanedPreviewStats?.dialogueCues?.take(3)
                    ?: state.parsedSubtitle?.allCues?.take(3).orEmpty(),
                enabled = !state.isAnalyzing,
                onPickVideo = {
                    videoPicker.launch(
                        arrayOf(
                            "video/*",
                            "application/x-matroska",
                            "application/octet-stream",
                        )
                    )
                },
                onSelectAudioTrack = { viewModel.onAudioTrackSelected(it) },
                onPickSubtitle = {
                    subtitlePicker.launch(
                        arrayOf(
                            "application/x-subrip",
                            "text/vtt",
                            "text/plain",
                            "text/*",
                            "application/octet-stream",
                        )
                    )
                },
                onSelectEncoding = { viewModel.onSubtitleEncodingSelected(it) },
            )

            // Primary Action or Progress Strip
            if (state.isAnalyzing && state.progress != null) {
                val prog = state.progress!!
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    color = SynctraColors.Surface,
                    border = BorderStroke(1.dp, SynctraColors.Hairline),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = prog.stepTitle,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                color = SynctraColors.InkPrimary,
                            )
                            Text(
                                text = "${(prog.fraction * 100).toInt()}%",
                                fontSize = 13.sp,
                                fontFamily = FontFamily.Monospace,
                                color = SynctraColors.Accent,
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = prog.stepDetail,
                            fontSize = 12.sp,
                            color = SynctraColors.InkMuted,
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        LinearProgressIndicator(
                            progress = { prog.fraction },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(3.dp),
                            color = SynctraColors.Accent,
                            trackColor = SynctraColors.SurfaceSubtle,
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        TextButton(
                            onClick = { viewModel.cancelAnalysis() },
                            modifier = Modifier.align(Alignment.End),
                        ) {
                            Text(strings.cancelAction, fontSize = 12.sp, color = SynctraColors.InkSecondary)
                        }
                    }
                }
            } else {
                Button(
                    onClick = { viewModel.startAnalysis() },
                    enabled = state.canStartAnalysis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = SynctraColors.ActionBg,
                        contentColor = SynctraColors.ActionInk,
                        disabledContainerColor = SynctraColors.SurfaceSubtle,
                        disabledContentColor = SynctraColors.InkMuted,
                    ),
                ) {
                    Text(
                        text = if (state.result != null) strings.recheckSyncCta else strings.checkSyncCta,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }

            // Error callout
            AnimatedVisibility(visible = state.errorMessage != null) {
                state.errorMessage?.let { err ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(IntrinsicSize.Min)
                            .background(SynctraColors.DangerDim, RoundedCornerShape(12.dp)),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .width(3.dp)
                                .fillMaxHeight()
                                .background(SynctraColors.Danger, RoundedCornerShape(topStart = 12.dp, bottomStart = 12.dp)),
                        )
                        Text(
                            text = err,
                            fontSize = 13.sp,
                            color = SynctraColors.InkPrimary,
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 12.dp, vertical = 12.dp),
                        )
                        TextButton(onClick = { viewModel.dismissError() }) {
                            Text(strings.closeAction, fontSize = 12.sp, color = SynctraColors.Danger)
                        }
                    }
                }
            }

            // Result + Audio Check
            state.result?.let { syncResult ->
                ResultCard(
                    strings = strings,
                    result = syncResult,
                    effectiveOffsetMs = state.effectiveOffsetMs,
                    manualNudgeMs = state.manualNudgeMs,
                    exportVerification = state.exportVerification,
                    onCopySeconds = { copyWithToast(it, it) },
                    onCopyMillis = { copyWithToast(it, "$it ms") },
                    onOpenSaveSrt = { showExportSheet = true },
                    onReverifyExport = { viewModel.reverifyLastExport() },
                    onCopyDiagnostics = {
                        viewModel.buildDiagnosticsText()?.let { diag ->
                            copyWithToast(diag, strings.diagnosticsCopiedLabel)
                        }
                    },
                )

                AudioPreviewCard(
                    strings = strings,
                    previewState = state.previewState,
                    activeSubtitleText = state.activePreviewSubtitle,
                    windows = syncResult.windowResults,
                    selectedWindowIndex = state.selectedPreviewWindowIndex,
                    manualNudgeMs = state.manualNudgeMs,
                    audioTrackTitle = syncResult.audioTrackTitle.ifBlank { state.selectedAudioTrack?.displayTitle },
                    onSelectWindow = { viewModel.onSelectPreviewWindow(it) },
                    onTogglePlay = { viewModel.toggleAudioPreview() },
                    onNudgeOffset = { viewModel.nudgeOffset(it) },
                    onResetNudge = { viewModel.resetManualNudge() },
                )
            }

            // Recent check history
            if (state.recentHistory.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                RecentHistoryCard(
                    strings = strings,
                    entries = state.recentHistory,
                    onCopyOffset = { copyWithToast(it, it) },
                    onClearHistory = { viewModel.clearHistory() },
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
        }
    }

        // Export .srt review bottom sheet (in-window overlay so Activity system bar insets always apply)
        if (showExportSheet && state.result != null && state.parsedSubtitle != null) {
            val cleaned = state.cleanedPreviewStats
            ExportSrtSheet(
                strings = strings,
                totalCueCount = state.parsedSubtitle!!.allCues.size,
                effectiveOffsetMs = state.effectiveOffsetMs,
                result = state.result!!,
                flaggedAds = cleaned?.flaggedAdOrCreditCues.orEmpty(),
                flaggedSdh = cleaned?.flaggedSdhCues.orEmpty(),
                onDismiss = { showExportSheet = false },
                onConfirmSave = { options ->
                    showExportSheet = false
                    viewModel.prepareExport(options)
                    createSrtLauncher.launch(viewModel.suggestedExportFileName())
                },
            )
        }
    }
}
