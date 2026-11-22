package com.synctra.app.ui.components

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.synctra.app.data.media.AudioPreviewState
import com.synctra.app.domain.model.WindowSyncResult
import com.synctra.app.ui.theme.SynctraColors

@Composable
fun AudioPreviewCard(
    previewState: AudioPreviewState,
    activeSubtitleText: String?,
    windows: List<WindowSyncResult>,
    selectedWindowIndex: Int,
    manualNudgeMs: Long,
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
        shape = RoundedCornerShape(20.dp),
        color = SynctraColors.Surface,
        border = BorderStroke(1.dp, SynctraColors.Border),
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "TEST DENGAR 10 DETIK",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.2.sp,
                        color = SynctraColors.Success,
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Putar audio saja & lihat subtitle real-time untuk pastikan pas",
                        fontSize = 12.sp,
                        color = SynctraColors.TextSecondary,
                    )
                }
            }

            if (windows.size > 1) {
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    windows.forEachIndexed { idx, wr ->
                        val selected = idx == selectedWindowIndex
                        Surface(
                            onClick = { onSelectWindow(idx) },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp),
                            color = if (selected) SynctraColors.PrimaryContainer else SynctraColors.SurfaceElevated,
                            border = BorderStroke(
                                1.dp,
                                if (selected) SynctraColors.Primary else SynctraColors.Border,
                            ),
                        ) {
                            Text(
                                text = wr.window.label.substringBefore(" "),
                                fontSize = 12.sp,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                color = if (selected) SynctraColors.Primary else SynctraColors.TextSecondary,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(vertical = 7.dp),
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Cinema-style live subtitle preview stage
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 92.dp),
                shape = RoundedCornerShape(14.dp),
                color = SynctraColors.Background,
                border = BorderStroke(1.dp, SynctraColors.Border),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = when {
                            previewState.isBuffering -> "Memuat audio..."
                            !activeSubtitleText.isNullOrBlank() -> activeSubtitleText
                            previewState.isPlaying -> "· · · (jeda dialog) · · ·"
                            else -> "Tekan Putar 10 Detik untuk verifikasi suara & teks"
                        },
                        fontSize = if (!activeSubtitleText.isNullOrBlank()) 16.sp else 13.sp,
                        fontWeight = if (!activeSubtitleText.isNullOrBlank()) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (!activeSubtitleText.isNullOrBlank()) {
                            SynctraColors.TextPrimary
                        } else {
                            SynctraColors.TextMuted
                        },
                        textAlign = TextAlign.Center,
                        lineHeight = 22.sp,
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Playback clock & progress bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = formatClock(previewState.currentPositionMs),
                    fontSize = 11.sp,
                    color = SynctraColors.TextMuted,
                )
                Text(
                    text = formatClock(previewState.clipStartMs + previewState.clipDurationMs),
                    fontSize = 11.sp,
                    color = SynctraColors.TextMuted,
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(5.dp),
                color = SynctraColors.Success,
                trackColor = SynctraColors.SurfaceElevated,
            )

            previewState.errorMessage?.let { err ->
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = err,
                    fontSize = 12.sp,
                    color = SynctraColors.Error,
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Play / Stop button
            Button(
                onClick = onTogglePlay,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (previewState.isPlaying) SynctraColors.SurfaceElevated else SynctraColors.Success,
                    contentColor = if (previewState.isPlaying) SynctraColors.TextPrimary else SynctraColors.OnPrimary,
                ),
            ) {
                Icon(
                    imageVector = if (previewState.isPlaying) Icons.Filled.Close else Icons.Filled.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = if (previewState.isPlaying) "Hentikan Preview" else "Putar 10 Detik",
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Fine-tune nudge bar
            Text(
                text = "Geser halus jika masih terasa sedikit meleset:",
                fontSize = 11.sp,
                color = SynctraColors.TextMuted,
            )
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                NudgeButton("-0.5s", Modifier.weight(1f)) { onNudgeOffset(-500L) }
                NudgeButton("-0.1s", Modifier.weight(1f)) { onNudgeOffset(-100L) }
                OutlinedButton(
                    onClick = onResetNudge,
                    enabled = manualNudgeMs != 0L,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, SynctraColors.Border),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp, vertical = 6.dp),
                ) {
                    Text(
                        text = "Reset",
                        fontSize = 11.sp,
                        color = if (manualNudgeMs != 0L) SynctraColors.Primary else SynctraColors.TextMuted,
                    )
                }
                NudgeButton("+0.1s", Modifier.weight(1f)) { onNudgeOffset(+100L) }
                NudgeButton("+0.5s", Modifier.weight(1f)) { onNudgeOffset(+500L) }
            }
        }
    }
}

@Composable
private fun NudgeButton(
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        color = SynctraColors.SurfaceElevated,
        border = BorderStroke(1.dp, SynctraColors.Border),
    ) {
        Text(
            text = label,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = SynctraColors.TextPrimary,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(vertical = 8.dp),
        )
    }
}
