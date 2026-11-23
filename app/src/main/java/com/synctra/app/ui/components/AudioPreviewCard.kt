package com.synctra.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.synctra.app.data.media.AudioPreviewState
import com.synctra.app.domain.model.WindowSyncResult
import com.synctra.app.ui.i18n.AppStrings
import com.synctra.app.ui.theme.SynctraColors

@Composable
fun AudioPreviewCard(
    strings: AppStrings,
    previewState: AudioPreviewState,
    activeSubtitleText: String?,
    windows: List<WindowSyncResult>,
    selectedWindowIndex: Int,
    manualNudgeMs: Long,
    audioTrackTitle: String?,
    onSelectWindow: (Int) -> Unit,
    onTogglePlay: () -> Unit,
    onNudgeOffset: (Long) -> Unit,
    onResetNudge: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val elapsed = (previewState.currentPositionMs - previewState.clipStartMs)
        .coerceIn(0L, previewState.clipDurationMs)
    val progress = if (previewState.clipDurationMs > 0L) {
        elapsed.toFloat() / previewState.clipDurationMs.toFloat()
    } else {
        0f
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = SynctraColors.Surface,
        border = BorderStroke(1.dp, SynctraColors.Hairline),
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = strings.testListenTitle,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = SynctraColors.InkPrimary,
                    )
                    if (!audioTrackTitle.isNullOrBlank()) {
                        Text(
                            text = strings.testListenAudioMeta(audioTrackTitle),
                            fontSize = 12.sp,
                            color = SynctraColors.InkMuted,
                        )
                    }
                }

                if (windows.size > 1) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        windows.forEachIndexed { idx, _ ->
                            val selected = idx == selectedWindowIndex
                            Surface(
                                onClick = { onSelectWindow(idx) },
                                shape = RoundedCornerShape(8.dp),
                                color = if (selected) SynctraColors.SurfaceSubtle else SynctraColors.Surface,
                                border = BorderStroke(
                                    1.dp,
                                    if (selected) SynctraColors.Accent else SynctraColors.Hairline,
                                ),
                            ) {
                                Text(
                                    text = strings.windowShortLabel(idx, windows.size),
                                    fontSize = 11.sp,
                                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                    color = if (selected) SynctraColors.InkPrimary else SynctraColors.InkMuted,
                                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 88.dp),
                shape = RoundedCornerShape(12.dp),
                color = SynctraColors.Stage,
                border = BorderStroke(1.dp, SynctraColors.Hairline),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = when {
                            previewState.isBuffering -> strings.loadingAudio
                            !activeSubtitleText.isNullOrBlank() -> activeSubtitleText
                            previewState.isPlaying -> "—"
                            else -> strings.pressPlayHint
                        },
                        fontSize = if (!activeSubtitleText.isNullOrBlank()) 16.sp else 13.sp,
                        fontWeight = if (!activeSubtitleText.isNullOrBlank()) FontWeight.Medium else FontWeight.Normal,
                        color = if (!activeSubtitleText.isNullOrBlank()) {
                            SynctraColors.InkPrimary
                        } else {
                            SynctraColors.InkMuted
                        },
                        textAlign = TextAlign.Center,
                        lineHeight = 22.sp,
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(if (previewState.isPlaying) SynctraColors.SurfaceSubtle else SynctraColors.ActionBg)
                        .clickable(onClick = onTogglePlay),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = if (previewState.isPlaying) Icons.Filled.Close else Icons.Filled.PlayArrow,
                        contentDescription = if (previewState.isPlaying) strings.stopContentDesc else strings.playContentDesc,
                        tint = if (previewState.isPlaying) SynctraColors.InkPrimary else SynctraColors.ActionInk,
                        modifier = Modifier.size(20.dp),
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(3.dp),
                        color = SynctraColors.Accent,
                        trackColor = SynctraColors.SurfaceSubtle,
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = formatClock(previewState.currentPositionMs),
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = SynctraColors.InkSecondary,
                        )
                        Text(
                            text = formatClock(previewState.clipStartMs + previewState.clipDurationMs),
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = SynctraColors.InkMuted,
                        )
                    }
                }
            }

            previewState.errorMessage?.let { err ->
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = err,
                    fontSize = 12.sp,
                    color = SynctraColors.Danger,
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                color = SynctraColors.Stage,
                border = BorderStroke(1.dp, SynctraColors.Hairline),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    StepperCell("-0.5", Modifier.weight(1f)) { onNudgeOffset(-500L) }
                    StepperDivider()
                    StepperCell("-0.1", Modifier.weight(1f)) { onNudgeOffset(-100L) }
                    StepperDivider()
                    StepperCell(
                        label = if (manualNudgeMs == 0L) "0.0" else strings.resetLabel,
                        modifier = Modifier.weight(1f),
                        accent = manualNudgeMs != 0L,
                        enabled = manualNudgeMs != 0L,
                        onClick = onResetNudge,
                    )
                    StepperDivider()
                    StepperCell("+0.1", Modifier.weight(1f)) { onNudgeOffset(+100L) }
                    StepperDivider()
                    StepperCell("+0.5", Modifier.weight(1f)) { onNudgeOffset(+500L) }
                }
            }
        }
    }
}

@Composable
private fun StepperDivider() {
    Box(
        modifier = Modifier
            .width(1.dp)
            .height(22.dp)
            .background(SynctraColors.Hairline),
    )
}

@Composable
private fun StepperCell(
    label: String,
    modifier: Modifier = Modifier,
    accent: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium,
            color = when {
                accent -> SynctraColors.Accent
                enabled -> SynctraColors.InkPrimary
                else -> SynctraColors.InkMuted
            },
        )
    }
}
