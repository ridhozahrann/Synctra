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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.synctra.app.domain.model.ExportVerificationResult
import com.synctra.app.domain.model.SyncResult
import com.synctra.app.domain.model.SyncResultSource
import com.synctra.app.ui.i18n.AppStrings
import com.synctra.app.ui.theme.SynctraColors
import java.util.Locale
import kotlin.math.abs

@Composable
fun ResultCard(
    strings: AppStrings,
    result: SyncResult,
    effectiveOffsetMs: Long,
    manualNudgeMs: Long,
    exportVerification: ExportVerificationResult?,
    onCopySeconds: (String) -> Unit,
    onCopyMillis: (String) -> Unit,
    onOpenSaveSrt: () -> Unit,
    onReverifyExport: () -> Unit,
    onCopyDiagnostics: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isSynced = abs(effectiveOffsetMs) <= 150L && result.drift.severity == DriftSeverity.NONE
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = strings.offsetTitle,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = SynctraColors.InkMuted,
                )
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "${(result.confidence * 100).toInt()}% · ${strings.confidenceLabel(result.confidenceLevel)}",
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        color = confidenceColor,
                    )
                    if (result.source == SyncResultSource.JOINT_POOLED) {
                        Text(
                            text = strings.pooledEstimateBadge,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            color = SynctraColors.InkMuted,
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = if (isSynced) strings.syncedStatus else "${formattedSec}s",
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
                    text = strings.manualNudgeInfo(
                        formatSignedSeconds(manualNudgeMs),
                        formatSignedSeconds(result.offsetMs),
                    ),
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    color = SynctraColors.Accent,
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = strings.formatGuidance(effectiveOffsetMs, formattedSec, formattedMs),
                fontSize = 14.sp,
                lineHeight = 20.sp,
                color = SynctraColors.InkSecondary,
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Primary action bar: Copy seconds + Copy ms + Save .srt
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
                        text = strings.copySecAction(formattedSec),
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
                        text = strings.copyMsAction,
                        color = SynctraColors.InkPrimary,
                        fontWeight = FontWeight.Medium,
                        fontSize = 13.sp,
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedButton(
                onClick = onOpenSaveSrt,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(42.dp),
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, SynctraColors.Hairline),
            ) {
                Text(
                    text = strings.saveSrtAction,
                    color = SynctraColors.InkPrimary,
                    fontWeight = FontWeight.Medium,
                    fontSize = 13.sp,
                )
            }

            // Post-export verification badge (F1)
            if (exportVerification != null) {
                Spacer(modifier = Modifier.height(10.dp))
                val verifiedOk = exportVerification.isVerifiedSynced
                val badgeColor = if (verifiedOk) SynctraColors.Synced else SynctraColors.Accent
                val badgeBg = if (verifiedOk) SynctraColors.SurfaceSubtle else SynctraColors.AccentDim
                val badgeText = when {
                    verifiedOk -> strings.verifySyncedBadge(formatSignedMillis(exportVerification.residualOffsetMs))
                    exportVerification.hasDrift && abs(exportVerification.residualOffsetMs) <= 200L -> strings.verifyDriftBadge
                    else -> strings.verifyOffsetBadge(formatSignedSeconds(exportVerification.residualOffsetMs))
                }
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    color = badgeBg,
                    border = BorderStroke(1.dp, badgeColor.copy(alpha = 0.45f)),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 12.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = badgeText,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            color = badgeColor,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = onReverifyExport) {
                            Text(
                                text = strings.verifyRecheckAction,
                                fontSize = 11.sp,
                                color = SynctraColors.InkPrimary,
                            )
                        }
                    }
                }
            }

            // Drift warning callout
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
                            text = if (isSevere) strings.driftSevereTitle else strings.driftMildTitle,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = barColor,
                        )
                        strings.driftCauseLabel(result.drift.causeKey, result.drift.suspectedCause)?.let { cause ->
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = cause,
                                fontSize = 12.sp,
                                color = SynctraColors.InkPrimary,
                            )
                        }
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = strings.driftSpreadInfo(
                                String.format(Locale.US, "%.2f", result.drift.spreadMs / 1000.0)
                            ),
                            fontSize = 12.sp,
                            color = SynctraColors.InkSecondary,
                        )
                    }
                }
            }

            // Monospace checkpoint strip (chunked into rows of 3 so 4-6 adaptive windows stay spacious)
            if (result.windowResults.isNotEmpty()) {
                Spacer(modifier = Modifier.height(14.dp))
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    color = SynctraColors.Stage,
                    border = BorderStroke(1.dp, SynctraColors.Hairline),
                ) {
                    val rows = result.windowResults.chunked(3)
                    Column(modifier = Modifier.fillMaxWidth()) {
                        rows.forEachIndexed { rowIdx, rowItems ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 10.dp, horizontal = 6.dp),
                            ) {
                                rowItems.forEachIndexed { idx, wr ->
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
                                    if (idx < rowItems.lastIndex) {
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
                            if (rowIdx < rows.lastIndex) {
                                HorizontalDivider(
                                    color = SynctraColors.Hairline,
                                    modifier = Modifier.padding(horizontal = 10.dp),
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            val footerParts = buildList {
                if (result.audioTrackTitle.isNotBlank()) add(result.audioTrackTitle)
                add(result.audioCodecUsed)
                add(if (result.usedFfmpegDecoder) "FFmpeg" else "MediaCodec")
                add("${String.format(Locale.US, "%.1f", result.analysisDurationMs / 1000.0)}s")
                if (result.filteredJunkCues > 0) add(strings.filteredMeta(result.filteredJunkCues))
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = footerParts.joinToString(" · "),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = SynctraColors.InkMuted,
                    lineHeight = 16.sp,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onCopyDiagnostics) {
                    Text(
                        text = strings.copyDiagnosticsAction,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = SynctraColors.InkSecondary,
                    )
                }
            }
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
