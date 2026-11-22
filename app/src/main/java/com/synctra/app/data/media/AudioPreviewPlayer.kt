package com.synctra.app.data.media

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
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
 * (automatically leveraging Jellyfin's FFmpeg audio-only decoder for AC3/EAC3/DTS/TrueHD)
 * and disables the video track completely so playback uses minimal CPU/RAM.
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
    private var tickerJob: Job? = null

    private val _state = MutableStateFlow(AudioPreviewState())
    val state: StateFlow<AudioPreviewState> = _state.asStateFlow()

    fun playClip(
        videoUri: Uri,
        startMs: Long,
        durationMs: Long = DEFAULT_CLIP_DURATION_MS,
    ) {
        val safeStart = startMs.coerceAtLeast(0L)
        val exo = ensurePlayer(videoUri)

        _state.value = AudioPreviewState(
            isPlaying = true,
            isBuffering = true,
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
        _state.value = AudioPreviewState()
    }

    private fun ensurePlayer(videoUri: Uri): ExoPlayer {
        val existing = player
        if (existing != null && preparedUri == videoUri) {
            return existing
        }

        existing?.release()

        val renderersFactory = DefaultRenderersFactory(context.applicationContext)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)

        val exo = ExoPlayer.Builder(context.applicationContext, renderersFactory)
            .build()
            .apply {
                // Disable video decoding completely for lightweight audio-only verification
                trackSelectionParameters = trackSelectionParameters
                    .buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                    .build()

                addListener(object : Player.Listener {
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
                            errorMessage = "Gagal memutar audio preview: ${error.localizedMessage ?: error.errorCodeName}",
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

    private fun startTicker(clipStartMs: Long, clipDurationMs: Long) {
        tickerJob?.cancel()
        tickerJob = scope.launch {
            val endMs = clipStartMs + clipDurationMs
            while (isActive) {
                val exo = player ?: break
                val pos = exo.currentPosition
                if (pos >= endMs && exo.playbackState == Player.STATE_READY) {
                    stop()
                    _state.value = _state.value.copy(currentPositionMs = clipStartMs)
                    break
                }
                _state.value = _state.value.copy(
                    currentPositionMs = pos.coerceAtLeast(clipStartMs),
                    isPlaying = exo.playWhenReady && exo.playbackState != Player.STATE_ENDED,
                )
                delay(50L) // 20 Hz UI updates for crisp subtitle timing during preview
            }
        }
    }
}
