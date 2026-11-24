package com.synctra.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.synctra.app.domain.model.AudioTrackOption
import com.synctra.app.domain.model.SubtitleCue
import com.synctra.app.domain.subtitle.SubtitleEncodingOption
import com.synctra.app.ui.i18n.AppStrings
import com.synctra.app.ui.theme.SynctraColors
import java.util.Locale

/**
 * Unified grouped input panel for selecting the movie, audio track (when multiple exist),
 * external subtitle file, and optional subtitle encoding override with live preview.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MediaInputSheet(
    strings: AppStrings,
    videoName: String?,
    videoMeta: String?,
    isProbingVideo: Boolean,
    audioTracks: List<AudioTrackOption>,
    selectedAudioIndex: Int,
    subtitleName: String?,
    subtitleMeta: String?,
    selectedEncoding: SubtitleEncodingOption,
    detectedEncodingName: String?,
    subtitlePreviewCues: List<SubtitleCue>,
    enabled: Boolean,
    onPickVideo: () -> Unit,
    onSelectAudioTrack: (Int) -> Unit,
    onPickSubtitle: () -> Unit,
    onSelectEncoding: (SubtitleEncodingOption) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showEncodingPicker by remember(subtitleName) { mutableStateOf(false) }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = SynctraColors.Surface,
        border = BorderStroke(1.dp, SynctraColors.Hairline),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            InputRow(
                label = strings.filmLabel,
                value = videoName,
                placeholder = strings.filmPlaceholder,
                meta = if (isProbingVideo) strings.probingAudio else videoMeta,
                pickLabel = strings.pickAction,
                changeLabel = strings.changeAction,
                enabled = enabled,
                onClick = onPickVideo,
            )

            AnimatedVisibility(visible = audioTracks.size > 1) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    HorizontalDivider(
                        color = SynctraColors.Hairline,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = strings.audioTrackLabel,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                color = SynctraColors.InkMuted,
                            )
                            Text(
                                text = strings.availableCount(audioTracks.size),
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                color = SynctraColors.InkMuted,
                            )
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            audioTracks.forEach { track ->
                                AudioTrackChip(
                                    track = track,
                                    selected = track.audioIndex == selectedAudioIndex,
                                    enabled = enabled,
                                    onClick = { onSelectAudioTrack(track.audioIndex) },
                                )
                            }
                        }
                    }
                }
            }

            HorizontalDivider(
                color = SynctraColors.Hairline,
                modifier = Modifier.padding(horizontal = 16.dp),
            )

            InputRow(
                label = strings.subtitleLabel,
                value = subtitleName,
                placeholder = strings.subtitlePlaceholder,
                meta = subtitleMeta,
                pickLabel = strings.pickAction,
                changeLabel = strings.changeAction,
                enabled = enabled,
                onClick = onPickSubtitle,
            )

            AnimatedVisibility(visible = subtitleName != null && detectedEncodingName != null) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    HorizontalDivider(
                        color = SynctraColors.Hairline,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = enabled) { showEncodingPicker = !showEncodingPicker }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = strings.encodingLabel,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = SynctraColors.InkMuted,
                        )
                        val activeBadge = if (selectedEncoding == SubtitleEncodingOption.AUTO) {
                            "${SubtitleEncodingOption.AUTO.label} (${detectedEncodingName.orEmpty()})"
                        } else {
                            selectedEncoding.label
                        }
                        Text(
                            text = "$activeBadge  ${if (showEncodingPicker) "▲" else "▼"}",
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = if (showEncodingPicker || selectedEncoding != SubtitleEncodingOption.AUTO) {
                                SynctraColors.Accent
                            } else {
                                SynctraColors.InkSecondary
                            },
                        )
                    }

                    AnimatedVisibility(visible = showEncodingPicker) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 16.dp, end = 16.dp, bottom = 14.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                SubtitleEncodingOption.entries.forEach { option ->
                                    val isSelected = option == selectedEncoding
                                    Surface(
                                        onClick = { onSelectEncoding(option) },
                                        enabled = enabled,
                                        shape = RoundedCornerShape(8.dp),
                                        color = if (isSelected) SynctraColors.AccentDim else SynctraColors.SurfaceSubtle,
                                        border = BorderStroke(
                                            width = 1.dp,
                                            color = if (isSelected) SynctraColors.Accent else SynctraColors.Hairline,
                                        ),
                                    ) {
                                        Text(
                                            text = option.label,
                                            fontSize = 11.sp,
                                            fontFamily = FontFamily.Monospace,
                                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                            color = if (isSelected) SynctraColors.Accent else SynctraColors.InkSecondary,
                                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
                                        )
                                    }
                                }
                            }

                            if (subtitlePreviewCues.isNotEmpty()) {
                                Surface(
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(10.dp),
                                    color = SynctraColors.SurfaceSubtle,
                                    border = BorderStroke(1.dp, SynctraColors.Hairline),
                                ) {
                                    Column(
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                        verticalArrangement = Arrangement.spacedBy(4.dp),
                                    ) {
                                        Text(
                                            text = strings.encodingPreviewLabel,
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = SynctraColors.InkMuted,
                                        )
                                        subtitlePreviewCues.take(3).forEach { cue ->
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                            ) {
                                                Text(
                                                    text = formatCompactCueTime(cue.startMs),
                                                    fontSize = 11.sp,
                                                    fontFamily = FontFamily.Monospace,
                                                    color = SynctraColors.InkMuted,
                                                )
                                                Text(
                                                    text = cue.cleanText.replace("\n", " "),
                                                    fontSize = 12.sp,
                                                    color = SynctraColors.InkPrimary,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                    modifier = Modifier.weight(1f),
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun formatCompactCueTime(ms: Long): String {
    val totalSec = (ms / 1000L).coerceAtLeast(0L)
    val h = totalSec / 3600L
    val m = (totalSec % 3600L) / 60L
    val s = totalSec % 60L
    return if (h > 0L) {
        String.format(Locale.US, "%d:%02d:%02d", h, m, s)
    } else {
        String.format(Locale.US, "%02d:%02d", m, s)
    }
}

@Composable
private fun InputRow(
    label: String,
    value: String?,
    placeholder: String,
    meta: String?,
    pickLabel: String,
    changeLabel: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val selected = value != null
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(if (selected) SynctraColors.Accent else SynctraColors.Hairline),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = label,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = SynctraColors.InkMuted,
                )
            }
            Spacer(modifier = Modifier.height(5.dp))
            Text(
                text = value ?: placeholder,
                fontSize = 15.sp,
                fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                color = if (selected) SynctraColors.InkPrimary else SynctraColors.InkMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!meta.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = meta,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    color = SynctraColors.InkSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        Text(
            text = if (selected) changeLabel else pickLabel,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = if (enabled) SynctraColors.InkPrimary else SynctraColors.InkMuted,
        )
    }
}

@Composable
private fun AudioTrackChip(
    track: AudioTrackOption,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(10.dp),
        color = if (selected) SynctraColors.AccentDim else SynctraColors.SurfaceSubtle,
        border = BorderStroke(
            width = 1.dp,
            color = if (selected) SynctraColors.Accent else SynctraColors.Hairline,
        ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 11.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(if (selected) SynctraColors.Accent else SynctraColors.InkMuted.copy(alpha = 0.4f)),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column {
                Text(
                    text = track.displayTitle,
                    fontSize = 13.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (selected) SynctraColors.InkPrimary else SynctraColors.InkSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = track.technicalSummary,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = if (selected) SynctraColors.Accent else SynctraColors.InkMuted,
                )
            }
        }
    }
}
