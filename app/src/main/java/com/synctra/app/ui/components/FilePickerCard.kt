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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.synctra.app.domain.model.AudioTrackOption
import com.synctra.app.ui.i18n.AppStrings
import com.synctra.app.ui.theme.SynctraColors

/**
 * Unified grouped input panel for selecting the movie, audio track (when multiple exist),
 * and external subtitle file.
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
    enabled: Boolean,
    onPickVideo: () -> Unit,
    onSelectAudioTrack: (Int) -> Unit,
    onPickSubtitle: () -> Unit,
    modifier: Modifier = Modifier,
) {
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
        }
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
