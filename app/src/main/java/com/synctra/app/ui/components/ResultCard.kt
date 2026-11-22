package com.synctra.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.synctra.app.domain.model.ConfidenceLevel
import com.synctra.app.domain.model.DriftSeverity
import com.synctra.app.domain.model.SyncResult
import com.synctra.app.ui.theme.SynctraColors
import java.util.Locale
import kotlin.math.abs

@Composable
fun ResultCard(
    result: SyncResult,
    effectiveOffsetMs: Long,
    manualNudgeMs: Long,
    onCopySeconds: (String) -> Unit,
    onCopyMillis: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val isSynced = abs(effectiveOffsetMs) <= 150L
    val formattedSec = formatSignedSeconds(effectiveOffsetMs)
    val formattedMs = formatSignedMillis(effectiveOffsetMs)

    val confidenceColor = when (result.confidenceLevel) {
        ConfidenceLevel.HIGH -> SynctraColors.Synced
        ConfidenceLevel.MEDIUM -> SynctraColors.Accent
        ConfidenceLevel.LOW -> SynctraColors.Danger
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = SynctraColors.Surface,
        border = BorderStroke(1.dp, SynctraColors.Hairline),
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            // Top metadata row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Offset subtitle",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = SynctraColors.InkMuted,
                )
                Text(
                    text = "${(result.confidence * 100).toInt()}% · ${result.confidenceLevel.labelId}",
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    color = confidenceColor,
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Hero numeric readout
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = if (isSynced) "Sinkron" else "${formattedSec}s",
                    fontSize = 40.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = if (isSynced) FontFamily.Default else FontFamily.Monospace,
                    letterSpacing = (-1.5).sp,
                    color = if (isSynced) SynctraColors.Synced else SynctraColors.InkPrimary,
                )
                if (!isSynced) {
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "$formattedMs ms",
                        fontSize = 14.sp,
                        fontFamily = FontFamily.Monospace,
                        color = SynctraColors.InkMuted,
                        modifier = Modifier.padding(bottom = 7.dp),
                    )
                }
            }

            if (manualNudgeMs != 0L) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Manual ${formatSignedSeconds(manualNudgeMs)}s (awal ${formatSignedSeconds(result.offsetMs)}s)",
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    color = SynctraColors.Accent,
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Direct player instruction
            val guidance = when {
                isSynced -> "Sudah cocok dengan audio (selisih $formattedMs ms)."
                effectiveOffsetMs > 0 ->
                    "Subtitle terlalu cepat ${String.format(Locale.US, "%.2f", effectiveOffsetMs / 1000.0)} detik. " +
                        "Atur delay ${formattedSec}s di VLC / MX Player / Nova."
                else ->
                    "Subtitle terlambat ${String.format(Locale.US, "%.2f", abs(effectiveOffsetMs) / 1000.0)} detik. " +
                        "Atur delay ${formattedSec}s di VLC / MX Player / Nova."
            }
            Text(
                text = guidance,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                color = SynctraColors.InkSecondary,
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Action buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = { onCopySeconds("${formattedSec}s") },
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = SynctraColors.ActionBg,
                        contentColor = SynctraColors.ActionInk,
                    ),
                ) {
                    Text(
                        text = "Salin ${formattedSec}s",
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                    )
                }

                OutlinedButton(
                    onClick = { onCopyMillis(formattedMs) },
                    modifier = Modifier.height(44.dp),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, SynctraColors.Hairline),
                ) {
                    Text(
                        text = "Salin ms",
                        color = SynctraColors.InkPrimary,
                        fontWeight = FontWeight.Medium,
                        fontSize = 13.sp,
                    )
                }
            }

            // Drift warning callout (left-accent bar, clean and restrained)
            if (result.drift.severity != DriftSeverity.NONE) {
                Spacer(modifier = Modifier.height(14.dp))
                val isSevere = result.drift.severity == DriftSeverity.SEVERE
                val barColor = if (isSevere) SynctraColors.Danger else SynctraColors.Accent
                val bgColor = if (isSevere) SynctraColors.DangerDim else SynctraColors.AccentDim

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(IntrinsicSize.Min)
                        .background(bgColor, RoundedCornerShape(10.dp)),
                ) {
                    Box(
                        modifier = Modifier
                            .width(3.dp)
                            .fillMaxHeight()
                            .background(barColor, RoundedCornerShape(topStart = 10.dp, bottomStart = 10.dp)),
                    )
                    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                        Text(
                            text = if (isSevere) "Drift / beda FPS terdeteksi" else "Ada sedikit pergeseran antar bagian",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = barColor,
                        )
                        result.drift.suspectedCause?.let { cause ->
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = cause,
                                fontSize = 12.sp,
                                color = SynctraColors.InkPrimary,
                            )
                        }
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Selisih antar titik sampel ${String.format(Locale.US, "%.2f", result.drift.spreadMs / 1000.0)}s.",
                            fontSize = 12.sp,
                            color = SynctraColors.InkSecondary,
                        )
                    }
                }
            }

            // 3-column monospace checkpoint strip
            if (result.windowResults.isNotEmpty()) {
                Spacer(modifier = Modifier.height(14.dp))
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    color = SynctraColors.Stage,
                    border = BorderStroke(1.dp, SynctraColors.Hairline),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 10.dp, horizontal = 6.dp),
                    ) {
                        result.windowResults.forEachIndexed { idx, wr ->
                            Column(
                                modifier = Modifier.weight(1f),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Text(
                                    text = formatClock(wr.window.startMs),
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = SynctraColors.InkMuted,
                                )
                                Spacer(modifier = Modifier.height(3.dp))
                                Text(
                                    text = "${formatSignedSeconds(wr.offsetMs)}s",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    fontFamily = FontFamily.Monospace,
                                    color = SynctraColors.InkPrimary,
                                    textAlign = TextAlign.Center,
                                )
                                Text(
                                    text = "${(wr.confidence * 100).toInt()}%",
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = SynctraColors.InkMuted,
                                )
                            }
                            if (idx < result.windowResults.lastIndex) {
                                Box(
                                    modifier = Modifier
                                        .width(1.dp)
                                        .height(36.dp)
                                        .align(Alignment.CenterVertically)
                                        .background(SynctraColors.Hairline),
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Quiet technical footer
            val footerParts = buildList {
                if (result.audioTrackTitle.isNotBlank()) add(result.audioTrackTitle)
                add(result.audioCodecUsed)
                add(if (result.usedFfmpegDecoder) "FFmpeg" else "MediaCodec")
                add("${String.format(Locale.US, "%.1f", result.analysisDurationMs / 1000.0)}s")
                if (result.filteredJunkCues > 0) add("${result.filteredJunkCues} iklan/SDH difilter")
            }
            Text(
                text = footerParts.joinToString(" · "),
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = SynctraColors.InkMuted,
                lineHeight = 16.sp,
            )
        }
    }
}

fun formatSignedSeconds(ms: Long): String {
    val sec = ms / 1000.0
    val sign = if (ms > 0) "+" else if (ms < 0) "-" else ""
    return "$sign${String.format(Locale.US, "%.2f", abs(sec))}"
}

fun formatSignedMillis(ms: Long): String {
    return if (ms > 0) "+$ms" else "$ms"
}

fun formatClock(ms: Long): String {
    val totalSec = (ms.coerceAtLeast(0L) / 1000L)
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) {
        String.format(Locale.US, "%d:%02d:%02d", h, m, s)
    } else {
        String.format(Locale.US, "%02d:%02d", m, s)
    }
}
