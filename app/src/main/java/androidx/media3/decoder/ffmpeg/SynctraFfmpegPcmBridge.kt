package androidx.media3.decoder.ffmpeg

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import java.nio.ByteOrder

/**
 * Thin bridge placed in [androidx.media3.decoder.ffmpeg] to access Media3's package-private
 * [FfmpegAudioDecoder] for fast non-realtime audio decoding (AC-3, E-AC-3, DTS, TrueHD, etc.).
 */
@OptIn(UnstableApi::class)
class SynctraFfmpegPcmBridge private constructor(
    private val decoder: FfmpegAudioDecoder,
) : AutoCloseable {

    val channelCount: Int get() = decoder.channelCount.coerceAtLeast(1)
    val sampleRate: Int get() = decoder.sampleRate.coerceAtLeast(8000)

    /**
     * Decodes a single compressed audio packet and invokes [onPcm16] with decoded interleaved
     * 16-bit signed PCM samples and their presentation timestamp in microseconds.
     */
    fun decodePacket(
        data: ByteArray,
        size: Int,
        timeUs: Long,
        onPcm16: (samples: ShortArray, sampleCount: Int, channels: Int, sampleRate: Int, ptsUs: Long) -> Unit,
    ) {
        val inputBuffer = dequeueInputWithRetry() ?: return
        inputBuffer.clear()
        inputBuffer.ensureSpaceForWrite(size)
        inputBuffer.data?.put(data, 0, size)
        inputBuffer.timeUs = timeUs
        decoder.queueInputBuffer(inputBuffer)

        drainAvailableOutputs(onPcm16)
    }

    fun flush() {
        decoder.flush()
    }

    fun drainEndOfStream(
        onPcm16: (samples: ShortArray, sampleCount: Int, channels: Int, sampleRate: Int, ptsUs: Long) -> Unit,
    ) {
        val eosBuffer = dequeueInputWithRetry()
        if (eosBuffer != null) {
            eosBuffer.clear()
            eosBuffer.setFlags(C.BUFFER_FLAG_END_OF_STREAM)
            decoder.queueInputBuffer(eosBuffer)
        }
        // Poll briefly for any final output buffers
        for (attempt in 0 until 40) {
            val out = decoder.dequeueOutputBuffer()
            if (out != null) {
                val isEos = out.isEndOfStream
                emitOutputBuffer(out, onPcm16)
                out.release()
                if (isEos) break
            } else {
                Thread.sleep(2)
            }
        }
    }

    private fun dequeueInputWithRetry(): androidx.media3.decoder.DecoderInputBuffer? {
        for (attempt in 0 until 100) {
            val buf = decoder.dequeueInputBuffer()
            if (buf != null) return buf
            // Drain outputs if decoder output queue is full
            var out = decoder.dequeueOutputBuffer()
            while (out != null) {
                out.release()
                out = decoder.dequeueOutputBuffer()
            }
            Thread.sleep(1)
        }
        return null
    }

    private fun drainAvailableOutputs(
        onPcm16: (samples: ShortArray, sampleCount: Int, channels: Int, sampleRate: Int, ptsUs: Long) -> Unit,
    ) {
        var out = decoder.dequeueOutputBuffer()
        while (out != null) {
            emitOutputBuffer(out, onPcm16)
            out.release()
            out = decoder.dequeueOutputBuffer()
        }
    }

    private fun emitOutputBuffer(
        out: androidx.media3.decoder.SimpleDecoderOutputBuffer,
        onPcm16: (samples: ShortArray, sampleCount: Int, channels: Int, sampleRate: Int, ptsUs: Long) -> Unit,
    ) {
        val byteBuf = out.data ?: return
        val remaining = byteBuf.remaining()
        if (remaining < 2) return

        val shortCount = remaining / 2
        val shorts = ShortArray(shortCount)
        byteBuf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts)
        onPcm16(shorts, shortCount, channelCount, sampleRate, out.timeUs)
    }

    override fun close() {
        decoder.release()
    }

    companion object {
        private const val NUM_BUFFERS = 8
        private const val INITIAL_INPUT_BUFFER_SIZE = 64 * 1024

        fun isAvailable(): Boolean = FfmpegLibrary.isAvailable()

        fun supportsMimeType(mimeType: String?): Boolean {
            if (mimeType.isNullOrBlank()) return false
            return FfmpegLibrary.isAvailable() && FfmpegLibrary.supportsFormat(mimeType)
        }

        @Throws(FfmpegDecoderException::class)
        fun create(format: Format): SynctraFfmpegPcmBridge {
            val decoder = FfmpegAudioDecoder(
                format,
                NUM_BUFFERS,
                NUM_BUFFERS,
                INITIAL_INPUT_BUFFER_SIZE,
                false, // 16-bit PCM output
            )
            return SynctraFfmpegPcmBridge(decoder)
        }
    }
}
