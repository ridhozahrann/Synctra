package com.synctra.app.data.media

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class AudioPreviewState(
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val clipStartMs: Long = 0L,
    val clipDurationMs: Long = AudioPreviewPlayer.DEFAULT_CLIP_DURATION_MS,
    val currentPositionMs: Long = 0L,
    val errorMessage: String? = null,
)

/**
 * Lightweight audio-only preview player for "Test Dengar 10 Detik".
 *
 * Uses Media3 ExoPlayer with [DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON]
 * (automatically leveraging Jellyfin's FFmpeg audio-only decoder for AC3/EAC3/DTS/TrueHD),
 * disables the video track, and routes playback to the exact [selectedAudioIndex] chosen by the user.
 */
@OptIn(UnstableApi::class)
class AudioPreviewPlayer(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    companion object {
        const val DEFAULT_CLIP_DURATION_MS = 10_000L
    }

    private var player: ExoPlayer? = null
    private var preparedUri: Uri? = null
    private var desiredAudioIndex: Int = 0
    private var appliedAudioIndex: Int = -1
    private var tickerJob: Job? = null

    private val _state = MutableStateFlow(AudioPreviewState())
    val state: StateFlow<AudioPreviewState> = _state.asStateFlow()

    fun playClip(
        videoUri: Uri,
        startMs: Long,
        selectedAudioIndex: Int = 0,
        durationMs: Long = DEFAULT_CLIP_DURATION_MS,
    ) {
        val safeStart = startMs.coerceAtLeast(0L)
        desiredAudioIndex = selectedAudioIndex.coerceAtLeast(0)
        val exo = ensurePlayer(videoUri)

        applyAudioTrackOverrideIfReady(exo, exo.currentTracks)

        _state.value = AudioPreviewState(
            isPlaying = true,
            isBuffering = exo.playbackState != Player.STATE_READY,
            clipStartMs = safeStart,
            clipDurationMs = durationMs,
            currentPositionMs = safeStart,
            errorMessage = null,
        )

        exo.seekTo(safeStart)
        exo.playWhenReady = true
        startTicker(safeStart, durationMs)
    }

    fun stop() {
        tickerJob?.cancel()
        tickerJob = null
        player?.let {
            it.playWhenReady = false
            it.pause()
        }
        _state.value = _state.value.copy(isPlaying = false, isBuffering = false)
    }

    fun release() {
        tickerJob?.cancel()
        tickerJob = null
        player?.release()
        player = null
        preparedUri = null
        appliedAudioIndex = -1
        _state.value = AudioPreviewState()
    }

    private fun ensurePlayer(videoUri: Uri): ExoPlayer {
        val existing = player
        if (existing != null && preparedUri == videoUri) {
            if (existing.playbackState == Player.STATE_IDLE) {
                existing.prepare()
            }
            return existing
        }

        existing?.release()
        appliedAudioIndex = -1

        val renderersFactory = DefaultRenderersFactory(context.applicationContext)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)

        val exo = ExoPlayer.Builder(context.applicationContext, renderersFactory)
            .build()
            .apply {
                trackSelectionParameters = trackSelectionParameters
                    .buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                    .build()

                addListener(object : Player.Listener {
                    override fun onTracksChanged(tracks: Tracks) {
                        applyAudioTrackOverrideIfReady(this@apply, tracks)
                    }

                    override fun onPlaybackStateChanged(playbackState: Int) {
                        when (playbackState) {
                            Player.STATE_BUFFERING -> {
                                _state.value = _state.value.copy(isBuffering = true)
                            }
                            Player.STATE_READY -> {
                                _state.value = _state.value.copy(isBuffering = false)
                            }
                            Player.STATE_ENDED -> {
                                stop()
                            }
                            else -> Unit
                        }
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        tickerJob?.cancel()
                        _state.value = _state.value.copy(
                            isPlaying = false,
                            isBuffering = false,
                            errorMessage = "Gagal memutar preview audio: ${error.localizedMessage ?: error.errorCodeName}",
                        )
                    }
                })

                setMediaItem(MediaItem.fromUri(videoUri))
                prepare()
            }

        player = exo
        preparedUri = videoUri
        return exo
    }

    private fun applyAudioTrackOverrideIfReady(exo: ExoPlayer, tracks: Tracks) {
        val audioGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
        if (audioGroups.isEmpty()) return

        val clampedIndex = desiredAudioIndex.coerceIn(0, audioGroups.lastIndex)
        if (appliedAudioIndex == clampedIndex) return

        val targetGroup = audioGroups[clampedIndex]
        exo.trackSelectionParameters = exo.trackSelectionParameters
            .buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
            .addOverride(TrackSelectionOverride(targetGroup.mediaTrackGroup, 0))
            .build()
        appliedAudioIndex = clampedIndex
    }

    private fun startTicker(clipStartMs: Long, clipDurationMs: Long) {
        tickerJob?.cancel()
        tickerJob = scope.launch {
            val endMs = clipStartMs + clipDurationMs
            var sawInRange = false
            val startedAtMs = android.os.SystemClock.elapsedRealtime()
            while (isActive) {
                val exo = player ?: break
                val pos = exo.currentPosition
                if (pos < endMs) {
                    sawInRange = true
                }
                val seekSettled = sawInRange || (android.os.SystemClock.elapsedRealtime() - startedAtMs >= 300L)
                if (pos >= endMs && exo.isPlaying && seekSettled) {
                    stop()
                    _state.value = _state.value.copy(currentPositionMs = clipStartMs)
                    break
                }
                _state.value = _state.value.copy(
                    currentPositionMs = pos.coerceIn(clipStartMs, endMs),
                    isPlaying = exo.playWhenReady && exo.playbackState != Player.STATE_ENDED,
                    isBuffering = exo.playbackState == Player.STATE_BUFFERING,
                )
                delay(50L)
            }
        }
    }
}
