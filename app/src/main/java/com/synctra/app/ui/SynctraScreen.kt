package com.synctra.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.synctra.app.data.file.DocumentMetaReader
import com.synctra.app.ui.components.AudioPreviewCard
import com.synctra.app.ui.components.FilePickerCard
import com.synctra.app.ui.components.ResultCard
import com.synctra.app.ui.components.formatClock
import com.synctra.app.ui.theme.SynctraColors
import kotlinx.coroutines.launch

@Composable
fun SynctraScreen(
    viewModel: SyncViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()

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

    fun copyWithToast(value: String, label: String) {
        clipboard.setText(AnnotatedString(value))
        scope.launch {
            snackbarHostState.showSnackbar("Disalin ke clipboard: $label")
        }
    }

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
                .padding(horizontal = 18.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // Header
            Column {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "Synctra",
                        fontSize = 28.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = SynctraColors.TextPrimary,
                        letterSpacing = (-0.5).sp,
                    )
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = SynctraColors.PrimaryContainer,
                    ) {
                        Text(
                            text = "OFFLINE",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp,
                            color = SynctraColors.Primary,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        )
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Cek selisih detik subtitle vs film tanpa tebak-tebakan.",
                    fontSize = 14.sp,
                    color = SynctraColors.TextSecondary,
                )
            }

            // Step 1: Pick Movie / Video
            val videoMetaLine = buildString {
                state.videoFile?.sizeBytes?.takeIf { it > 0 }?.let {
                    append(DocumentMetaReader.formatFileSize(it))
                }
                state.videoMeta?.let { meta ->
                    if (isNotEmpty()) append(" · ")
                    if (meta.durationMs > 0) {
                        append(formatClock(meta.durationMs))
                        append(" · ")
                    }
                    append(meta.audioCodecLabel)
                }
            }.ifBlank { null }

            FilePickerCard(
                stepNumber = "1",
                title = "FILE FILM / VIDEO",
                placeholder = "Pilih video (.mp4, .mkv, .avi, .webm)",
                fileName = state.videoFile?.displayName,
                metaBadge = videoMetaLine,
                enabled = !state.isAnalyzing,
                onClick = {
                    videoPicker.launch(
                        arrayOf(
                            "video/*",
                            "application/x-matroska",
                            "application/octet-stream",
                        )
                    )
                },
            )

            // Step 2: Pick Subtitle
            val subMetaLine = state.cleanedPreviewStats?.let { stats ->
                buildString {
                    append(stats.format.displayName)
                    append(" · ${stats.dialogueCues.size} dialog")
                    if (stats.totalFilteredCount > 0) {
                        append(" (${stats.totalFilteredCount} iklan/kredit/SDH difilter)")
                    }
                }
            }

            FilePickerCard(
                stepNumber = "2",
                title = "FILE SUBTITLE",
                placeholder = "Pilih subtitle (.srt, .ass, .ssa, .vtt)",
                fileName = state.subtitleFile?.displayName,
                metaBadge = subMetaLine,
                enabled = !state.isAnalyzing,
                onClick = {
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
            )

            // Primary Action Button or Progress Card
            if (state.isAnalyzing && state.progress != null) {
                val prog = state.progress!!
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    color = SynctraColors.Surface,
                    border = BorderStroke(1.dp, SynctraColors.Primary.copy(alpha = 0.4f)),
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
                                fontWeight = FontWeight.SemiBold,
                                color = SynctraColors.TextPrimary,
                            )
                            Text(
                                text = "${(prog.fraction * 100).toInt()}%",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = SynctraColors.Primary,
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = prog.stepDetail,
                            fontSize = 12.sp,
                            color = SynctraColors.TextSecondary,
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        LinearProgressIndicator(
                            progress = { prog.fraction },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp),
                            color = SynctraColors.Primary,
                            trackColor = SynctraColors.SurfaceElevated,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = { viewModel.cancelAnalysis() },
                            modifier = Modifier.align(Alignment.End),
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, SynctraColors.Border),
                        ) {
                            Text("Batalkan", fontSize = 12.sp, color = SynctraColors.TextSecondary)
                        }
                    }
                }
            } else {
                Button(
                    onClick = { viewModel.startAnalysis() },
                    enabled = state.canStartAnalysis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = SynctraColors.Primary,
                        contentColor = SynctraColors.OnPrimary,
                        disabledContainerColor = SynctraColors.SurfaceElevated,
                        disabledContentColor = SynctraColors.TextMuted,
                    ),
                ) {
                    Text(
                        text = if (state.result != null) "Cek Ulang Sinkronisasi" else "Cek Sinkronisasi",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }

            // Error Banner
            AnimatedVisibility(visible = state.errorMessage != null) {
                state.errorMessage?.let { err ->
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        color = SynctraColors.ErrorContainer,
                        border = BorderStroke(1.dp, SynctraColors.Error.copy(alpha = 0.5f)),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = err,
                                fontSize = 13.sp,
                                color = SynctraColors.Error,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { viewModel.dismissError() }) {
                                Text("Tutup", fontSize = 12.sp, color = SynctraColors.TextPrimary)
                            }
                        }
                    }
                }
            }

            // Result + Test Dengar 10 Detik
            state.result?.let { syncResult ->
                ResultCard(
                    result = syncResult,
                    effectiveOffsetMs = state.effectiveOffsetMs,
                    manualNudgeMs = state.manualNudgeMs,
                    onCopySeconds = { copyWithToast(it, it) },
                    onCopyMillis = { copyWithToast(it, "$it ms") },
                )

                AudioPreviewCard(
                    previewState = state.previewState,
                    activeSubtitleText = state.activePreviewSubtitle,
                    windows = syncResult.windowResults,
                    selectedWindowIndex = state.selectedPreviewWindowIndex,
                    manualNudgeMs = state.manualNudgeMs,
                    onSelectWindow = { viewModel.onSelectPreviewWindow(it) },
                    onTogglePlay = { viewModel.toggleAudioPreview() },
                    onNudgeOffset = { viewModel.nudgeOffset(it) },
                    onResetNudge = { viewModel.resetManualNudge() },
                )
            }

            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}
