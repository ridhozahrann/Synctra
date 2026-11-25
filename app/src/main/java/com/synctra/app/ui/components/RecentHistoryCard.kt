package com.synctra.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.synctra.app.domain.model.SyncHistoryEntry
import com.synctra.app.ui.i18n.AppStrings
import com.synctra.app.ui.theme.SynctraColors
import kotlin.math.abs

@Composable
fun RecentHistoryCard(
    strings: AppStrings,
    entries: List<SyncHistoryEntry>,
    onCopyOffset: (String) -> Unit,
    onClearHistory: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (entries.isEmpty()) return

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = strings.historyTitle,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = SynctraColors.InkMuted,
            )
            Text(
                text = strings.clearHistory,
                fontSize = 12.sp,
                color = SynctraColors.InkMuted,
                modifier = Modifier
                    .clickable(onClick = onClearHistory)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            color = SynctraColors.Surface,
            border = BorderStroke(1.dp, SynctraColors.Hairline),
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                entries.forEachIndexed { idx, entry ->
                    val signedSec = "${formatSignedSeconds(entry.offsetMs)}s"
                    val isSynced = abs(entry.offsetMs) <= 150L && !entry.hasDrift
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onCopyOffset(signedSec) }
                            .padding(horizontal = 14.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = entry.videoName,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                color = SynctraColors.InkPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = buildList {
                                    if (entry.subtitleName.isNotBlank()) add(entry.subtitleName)
                                    if (entry.audioTrackTitle.isNotBlank()) add(entry.audioTrackTitle)
                                }.joinToString(" · "),
                                fontSize = 11.sp,
                                color = SynctraColors.InkMuted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }

                        Spacer(modifier = Modifier.width(10.dp))

                        Text(
                            text = when {
                                isSynced -> strings.syncedStatus
                                entry.hasDrift && abs(entry.offsetMs) <= 150L -> "Drift"
                                else -> signedSec
                            },
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            fontFamily = FontFamily.Monospace,
                            color = when {
                                entry.hasDrift -> SynctraColors.Accent
                                isSynced -> SynctraColors.Synced
                                else -> SynctraColors.InkPrimary
                            },
                        )
                    }

                    if (idx < entries.lastIndex) {
                        HorizontalDivider(
                            color = SynctraColors.Hairline,
                            modifier = Modifier.padding(horizontal = 14.dp),
                        )
                    }
                }
            }
        }
    }
}
