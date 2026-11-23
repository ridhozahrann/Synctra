package com.synctra.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.synctra.app.domain.model.DriftSeverity
import com.synctra.app.domain.model.FlaggedSubtitleCue
import com.synctra.app.domain.model.SyncResult
import com.synctra.app.ui.i18n.AppStrings
import com.synctra.app.ui.theme.SynctraColors

data class ExportSrtOptions(
    val excludedCueIndices: Set<Int>,
    val applyDriftCorrection: Boolean,
)

/**
 * Interactive bottom sheet shown before saving a synced `.srt` file.
 * Lists every flagged ad/credit cue with timestamp & reason so the user can review
 * and uncheck any false positive before writing the file.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportSrtSheet(
    strings: AppStrings,
    totalCueCount: Int,
    effectiveOffsetMs: Long,
    result: SyncResult,
    flaggedAds: List<FlaggedSubtitleCue>,
    flaggedSdh: List<FlaggedSubtitleCue>,
    onDismiss: () -> Unit,
    onConfirmSave: (ExportSrtOptions) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // Default: all flagged ads/credits checked for removal; SDH unchecked (kept) by default
    var removedAdIndices by remember(flaggedAds) {
        mutableStateOf(flaggedAds.map { it.cue.index }.toSet())
    }
    var removeAllSdh by remember(flaggedSdh) { mutableStateOf(false) }
    var applyDrift by remember(result.drift.severity) {
        mutableStateOf(result.drift.severity == DriftSeverity.SEVERE)
    }

    val totalRemovedCount = removedAdIndices.size + if (removeAllSdh) flaggedSdh.size else 0
    val keptCount = (totalCueCount - totalRemovedCount).coerceAtLeast(0)
    val allAdsSelected = flaggedAds.isNotEmpty() && removedAdIndices.size == flaggedAds.size

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = SynctraColors.Surface,
        contentColor = SynctraColors.InkPrimary,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 20.dp),
        ) {
            Text(
                text = strings.exportTitle,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                color = SynctraColors.InkPrimary,
            )

            // Timing mode picker (only shown when drift was detected)
            if (result.drift.severity != DriftSeverity.NONE) {
                Spacer(modifier = Modifier.height(14.dp))
                Text(
                    text = strings.exportModeLabel,
                    fontSize = 12.sp,
                    color = SynctraColors.InkMuted,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ModeChip(
                        label = strings.exportModeConstant(formatSignedSeconds(effectiveOffsetMs)),
                        selected = !applyDrift,
                        modifier = Modifier.weight(1f),
                        onClick = { applyDrift = false },
                    )
                    ModeChip(
                        label = strings.exportModeDrift,
                        selected = applyDrift,
                        modifier = Modifier.weight(1f),
                        onClick = { applyDrift = true },
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Flagged ads & credits review section
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = strings.exportAdsSectionTitle(flaggedAds.size),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = SynctraColors.InkPrimary,
                )
                if (flaggedAds.isNotEmpty()) {
                    TextButton(
                        onClick = {
                            removedAdIndices = if (allAdsSelected) {
                                emptySet()
                            } else {
                                flaggedAds.map { it.cue.index }.toSet()
                            }
                        },
                    ) {
                        Text(
                            text = if (allAdsSelected) strings.exportKeepAllAds else strings.exportSelectAllAds,
                            fontSize = 12.sp,
                            color = SynctraColors.Accent,
                        )
                    }
                }
            }

            if (flaggedAds.isEmpty()) {
                Text(
                    text = strings.exportNoAdsDetected,
                    fontSize = 12.sp,
                    color = SynctraColors.InkMuted,
                    modifier = Modifier.padding(vertical = 6.dp),
                )
            } else {
                Text(
                    text = strings.exportAdsHint,
                    fontSize = 12.sp,
                    color = SynctraColors.InkMuted,
                )
                Spacer(modifier = Modifier.height(10.dp))

                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 260.dp),
                    shape = RoundedCornerShape(12.dp),
                    color = SynctraColors.Stage,
                    border = BorderStroke(1.dp, SynctraColors.Hairline),
                ) {
                    LazyColumn(modifier = Modifier.fillMaxWidth()) {
                        items(flaggedAds, key = { it.cue.index }) { item ->
                            val willRemove = item.cue.index in removedAdIndices
                            FlaggedCueRow(
                                item = item,
                                reasonLabel = strings.flagReasonLabel(item.reason),
                                checkedForRemoval = willRemove,
                                onToggle = {
                                    removedAdIndices = if (willRemove) {
                                        removedAdIndices - item.cue.index
                                    } else {
                                        removedAdIndices + item.cue.index
                                    }
                                },
                            )
                            HorizontalDivider(
                                color = SynctraColors.Hairline,
                                modifier = Modifier.padding(horizontal = 12.dp),
                            )
                        }
                    }
                }
            }

            // Optional SDH removal switch (off by default)
            if (flaggedSdh.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = SynctraColors.SurfaceSubtle,
                    border = BorderStroke(1.dp, SynctraColors.Hairline),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { removeAllSdh = !removeAllSdh }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = strings.exportSdhToggleTitle(flaggedSdh.size),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                color = SynctraColors.InkPrimary,
                            )
                            Text(
                                text = strings.exportSdhToggleSubtitle,
                                fontSize = 11.sp,
                                color = SynctraColors.InkMuted,
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Switch(
                            checked = removeAllSdh,
                            onCheckedChange = { removeAllSdh = it },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = SynctraColors.ActionInk,
                                checkedTrackColor = SynctraColors.Accent,
                                uncheckedThumbColor = SynctraColors.InkSecondary,
                                uncheckedTrackColor = SynctraColors.Stage,
                            ),
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Footer summary + save button
            Text(
                text = strings.exportSummary(keptCount, totalRemovedCount),
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                color = SynctraColors.InkSecondary,
            )
            Spacer(modifier = Modifier.height(10.dp))

            Button(
                onClick = {
                    val excluded = buildSet {
                        addAll(removedAdIndices)
                        if (removeAllSdh) {
                            flaggedSdh.forEach { add(it.cue.index) }
                        }
                    }
                    onConfirmSave(
                        ExportSrtOptions(
                            excludedCueIndices = excluded,
                            applyDriftCorrection = applyDrift,
                        )
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = SynctraColors.ActionBg,
                    contentColor = SynctraColors.ActionInk,
                ),
            ) {
                Text(
                    text = strings.exportConfirmCta,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
private fun ModeChip(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        color = if (selected) SynctraColors.AccentDim else SynctraColors.SurfaceSubtle,
        border = BorderStroke(1.dp, if (selected) SynctraColors.Accent else SynctraColors.Hairline),
    ) {
        Text(
            text = label,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) SynctraColors.InkPrimary else SynctraColors.InkSecondary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 9.dp),
        )
    }
}

@Composable
private fun FlaggedCueRow(
    item: FlaggedSubtitleCue,
    reasonLabel: String,
    checkedForRemoval: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = checkedForRemoval,
            onCheckedChange = { onToggle() },
            colors = CheckboxDefaults.colors(
                checkedColor = SynctraColors.Accent,
                uncheckedColor = SynctraColors.InkMuted,
                checkmarkColor = SynctraColors.ActionInk,
            ),
        )
        Column(modifier = Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = formatClock(item.cue.startMs),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = SynctraColors.InkMuted,
                )
                Text(
                    text = reasonLabel,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    color = if (checkedForRemoval) SynctraColors.Accent else SynctraColors.Synced,
                )
            }
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = item.cue.cleanText.replace("\n", " "),
                fontSize = 13.sp,
                color = if (checkedForRemoval) SynctraColors.InkMuted else SynctraColors.InkPrimary,
                textDecoration = if (checkedForRemoval) TextDecoration.LineThrough else TextDecoration.None,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
