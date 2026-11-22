package com.synctra.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.synctra.app.domain.model.ConfidenceLevel
import com.synctra.app.domain.model.DriftSeverity
import com.synctra.app.domain.model.SyncResult
import com.synctra.app.ui.theme.SynctraColors
import java.util.Locale
import kotlin.math.abs

@OptIn(ExperimentalLayoutApi::class)
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
        ConfidenceLevel.HIGH -> SynctraColors.Success
        ConfidenceLevel.MEDIUM -> SynctraColors.Warning
        ConfidenceLevel.LOW -> SynctraColors.Error
    }
    val confidenceBg = when (result.confidenceLevel) {
        ConfidenceLevel.HIGH -> SynctraColors.SuccessContainer
        ConfidenceLevel.MEDIUM -> SynctraColors.WarningContainer
        ConfidenceLevel.LOW -> SynctraColors.ErrorContainer
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = SynctraColors.Surface,
        border = BorderStroke(1.dp, SynctraColors.Primary.copy(alpha = 0.45f)),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "HASIL CEK SYNC",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.2.sp,
                    color = SynctraColors.Primary,
                )

                Surface(
                    shape = RoundedCornerShape(50),
                    color = confidenceBg,
                ) {
                    Text(
                        text = "${result.confidenceLevel.labelId} · ${(result.confidence * 100).toInt()}%",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = confidenceColor,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Big offset hero text
            Text(
                text = if (isSynced) "Sudah Sinkron" else "$formattedSec detik",
                fontSize = 36.sp,
                fontWeight = FontWeight.ExtraBold,
                color = if (isSynced) SynctraColors.Success else SynctraColors.TextPrimary,
                letterSpacing = (-1.0).sp,
            )

            if (manualNudgeMs != 0L) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Termasuk geser manual ${formatSignedSeconds(manualNudgeMs)}s (deteksi awal: ${formatSignedSeconds(result.offsetMs)}s)",
                    fontSize = 12.sp,
                    color = SynctraColors.Primary,
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Plain-language player guidance
            val guidanceText = when {
                isSynced -> "Subtitle sudah cocok dengan suara film (selisih ${formattedMs} ms). Tidak perlu geser delay di pemutar video."
                effectiveOffsetMs > 0 ->
                    "Subtitle muncul ${String.format(Locale.US, "%.2f", effectiveOffsetMs / 1000.0)} detik terlalu cepat. " +
                        "Masukkan $formattedSec detik ($formattedMs ms) pada pengaturan delay subtitle di VLC / MX Player / Nova."
                else ->
                    "Subtitle muncul ${String.format(Locale.US, "%.2f", abs(effectiveOffsetMs) / 1000.0)} detik terlambat. " +
                        "Masukkan $formattedSec detik ($formattedMs ms) pada pengaturan delay subtitle di VLC / MX Player / Nova."
            }

            Text(
                text = guidanceText,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                color = SynctraColors.TextSecondary,
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Copy buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Button(
                    onClick = { onCopySeconds("${formattedSec}s") },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = SynctraColors.Primary,
                        contentColor = SynctraColors.OnPrimary,
                    ),
                ) {
                    Text(
                        text = "Salin ${formattedSec}s",
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                    )
                }

                OutlinedButton(
                    onClick = { onCopyMillis(formattedMs) },
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, SynctraColors.Border),
                ) {
                    Text(
                        text = "$formattedMs ms",
                        color = SynctraColors.TextPrimary,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp,
                    )
                }
            }

            // Drift / FPS mismatch warning banner
            if (result.drift.severity != DriftSeverity.NONE) {
                Spacer(modifier = Modifier.height(16.dp))
                val isSevere = result.drift.severity == DriftSeverity.SEVERE
                val warnColor = if (isSevere) SynctraColors.Error else SynctraColors.Warning
                val warnBg = if (isSevere) SynctraColors.ErrorContainer else SynctraColors.WarningContainer

                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = warnBg,
                    border = BorderStroke(1.dp, warnColor.copy(alpha = 0.5f)),
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = if (isSevere) {
                                "Peringatan: Offset Tidak Stabil (Drift / Beda FPS)"
                            } else {
                                "Info: Sedikit Pergeseran Antar Bagian Film"
                            },
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = warnColor,
                        )
                        result.drift.suspectedCause?.let { cause ->
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = cause,
                                fontSize = 12.sp,
                                color = SynctraColors.TextPrimary,
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Selisih antar titik sampel: ${String.format(Locale.US, "%.2f", result.drift.spreadMs / 1000.0)} detik. " +
                                "Geser satu angka saja mungkin tidak pas dari awal sampai akhir.",
                            fontSize = 12.sp,
                            color = SynctraColors.TextSecondary,
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider(color = SynctraColors.Border)
            Spacer(modifier = Modifier.height(12.dp))

            // Multi-point checkpoint breakdown
            Text(
                text = "Detail Titik Sampel (${result.windowResults.size} bagian tengah film)",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = SynctraColors.TextSecondary,
            )
            Spacer(modifier = Modifier.height(8.dp))

            result.windowResults.forEach { wr ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 3.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "${wr.window.label} (${formatClock(wr.window.startMs)})",
                        fontSize = 12.sp,
                        color = SynctraColors.TextSecondary,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "${formatSignedSeconds(wr.offsetMs)}s",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = SynctraColors.TextPrimary,
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "${(wr.confidence * 100).toInt()}%",
                            fontSize = 11.sp,
                            color = SynctraColors.TextMuted,
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Diagnostic chips
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                InfoChip(
                    label = if (result.usedFfmpegDecoder) {
                        "FFmpeg Audio · ${result.audioCodecUsed}"
                    } else {
                        "Android Audio · ${result.audioCodecUsed}"
                    }
                )
                InfoChip(label = "Proses ${String.format(Locale.US, "%.1f", result.analysisDurationMs / 1000.0)} dtk")
                if (result.filteredJunkCues > 0) {
                    InfoChip(label = "${result.filteredJunkCues} iklan/SDH diabaikan")
                }
            }
        }
    }
}

@Composable
private fun InfoChip(label: String) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = SynctraColors.SurfaceElevated,
    ) {
        Text(
            text = label,
            fontSize = 11.sp,
            color = SynctraColors.TextSecondary,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
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
