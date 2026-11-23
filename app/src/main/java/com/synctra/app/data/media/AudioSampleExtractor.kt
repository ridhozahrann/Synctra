package com.synctra.app.data.media

import android.content.Context
import android.media.MediaCodec
import android.media.MediaFormat
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.decoder.ffmpeg.SynctraFfmpegPcmBridge
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import com.synctra.app.domain.audio.SpeechActivityDetector
import com.synctra.app.domain.model.AudioTrackOption
import com.synctra.app.domain.model.MediaTrackInfo
import com.synctra.app.domain.model.SampleWindow
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class ExtractedWindowAudio(
    val window: SampleWindow,
    val speechEnvelope: FloatArray,
    val usedFfmpegDecoder: Boolean,
)

/**
 * Extracts and decodes ONLY the selected audio track for sampled movie windows (skipping video frames),
 * feeding decoded 16-bit PCM directly into [SpeechActivityDetector.StreamingCollector].
 *
 * Uses Media3 container extractors (MP4, MKV, WebM, AVI, TS, etc.) with automatic dual-path
 * audio decoding:
 *   1. Native Android [MediaCodec] when supported by the device
 *   2. Bundled audio-only FFmpeg ([SynctraFfmpegPcmBridge]) for AC-3, E-AC-3, DTS,
 *      TrueHD, MLP, ALAC, and any codec unsupported by the device's platform codecs.
 */
@OptIn(UnstableApi::class)
class AudioSampleExtractor(private val context: Context) {

    /**
     * Probes container metadata (duration & all available audio tracks) without decoding video.
     */
    @Throws(IOException::class)
    fun probeMedia(uri: Uri): MediaTrackInfo {
        val dataSource = DefaultDataSource.Factory(context.applicationContext).createDataSource()
        try {
            val totalLength = dataSource.open(DataSpec(uri))
            val probeSession = openExtractorAndProbe(dataSource, uri, totalLength)
            try {
                if (probeSession.audioEntries.isEmpty()) {
                    throw IOException("File ini tidak memiliki jalur audio yang bisa dibaca.")
                }
                val durationMs = if (probeSession.seekMap.durationUs != C.TIME_UNSET) {
                    probeSession.seekMap.durationUs / 1000L
                } else {
                    0L
                }
                val tracks = probeSession.audioEntries.mapIndexed { idx, entry ->
                    val fmt = entry.format
                    val mime = fmt?.sampleMimeType ?: MimeTypes.AUDIO_UNKNOWN
                    AudioTrackOption(
                        audioIndex = idx,
                        extractorTrackId = entry.trackId,
                        mimeType = mime,
                        codecLabel = friendlyCodecLabel(mime),
                        languageCode = fmt?.language,
                        trackLabel = fmt?.label,
                        channelCount = fmt?.channelCount?.takeIf { it > 0 } ?: 2,
                        sampleRate = fmt?.sampleRate?.takeIf { it > 0 } ?: 48_000,
                        isDefault = ((fmt?.selectionFlags ?: 0) and C.SELECTION_FLAG_DEFAULT) != 0,
                    )
                }
                val defaultIdx = tracks.indexOfFirst { it.isDefault }.takeIf { it >= 0 } ?: 0
                return MediaTrackInfo(
                    durationMs = durationMs,
                    audioTracks = tracks,
                    defaultAudioIndex = defaultIdx,
                )
            } finally {
                probeSession.extractor.release()
            }
        } finally {
            runCatching { dataSource.close() }
        }
    }

    /**
     * Extracts speech-activity envelopes for each planned [SampleWindow] from the chosen [selectedAudioIndex].
     */
    @Throws(IOException::class)
    fun extractWindows(
        uri: Uri,
        windows: List<SampleWindow>,
        selectedAudioIndex: Int = 0,
        onWindowProgress: (windowIndex: Int, totalWindows: Int, window: SampleWindow) -> Unit = { _, _, _ -> },
    ): List<ExtractedWindowAudio> {
        val dataSource = DefaultDataSource.Factory(context.applicationContext).createDataSource()
        try {
            val totalLength = dataSource.open(DataSpec(uri))
            val session = openExtractorAndProbe(dataSource, uri, totalLength)
            if (session.audioEntries.isEmpty()) {
                throw IOException("Jalur audio tidak ditemukan di dalam file video.")
            }

            val safeIdx = selectedAudioIndex.coerceIn(0, session.audioEntries.lastIndex)
            val targetEntry = session.audioEntries[safeIdx]
            val audioFormat = targetEntry.format
                ?: throw IOException("Format jalur audio yang dipilih tidak terbaca.")

            val decoder = createBestAudioDecoder(audioFormat)
            try {
                val results = ArrayList<ExtractedWindowAudio>(windows.size)

                for ((idx, window) in windows.withIndex()) {
                    onWindowProgress(idx, windows.size, window)
                    val envelope = extractSingleWindow(
                        dataSource = dataSource,
                        uri = uri,
                        totalLength = totalLength,
                        session = session,
                        targetTrackOutput = targetEntry.trackOutput,
                        decoder = decoder,
                        window = window,
                    )
                    results.add(
                        ExtractedWindowAudio(
                            window = window,
                            speechEnvelope = envelope,
                            usedFfmpegDecoder = decoder.isFfmpeg,
                        )
                    )
                }

                return results
            } finally {
                decoder.close()
                session.extractor.release()
            }
        } finally {
            runCatching { dataSource.close() }
        }
    }

    private fun extractSingleWindow(
        dataSource: DataSource,
        uri: Uri,
        totalLength: Long,
        session: ProbeSession,
        targetTrackOutput: AudioCaptureTrackOutput,
        decoder: AudioPacketDecoder,
        window: SampleWindow,
    ): FloatArray {
        val startUs = window.startMs * 1000L
        val endUs = window.endMs * 1000L

        decoder.flush()
        session.audioEntries.forEach { it.trackOutput.reset() }

        var vad: SpeechActivityDetector? = null
        var collector: SpeechActivityDetector.StreamingCollector? = null
        var windowFinished = false

        targetTrackOutput.packetListener = { packetBytes, packetSize, ptsUs ->
            if (!windowFinished) {
                if (ptsUs >= endUs + 500_000L) {
                    windowFinished = true
                } else {
                    decoder.decode(packetBytes, packetSize, ptsUs) { pcm, count, channels, sr, outPtsUs ->
                        if (outPtsUs >= startUs - 40_000L && outPtsUs <= endUs) {
                            if (vad == null) {
                                val detector = SpeechActivityDetector(sampleRate = sr)
                                vad = detector
                                collector = detector.StreamingCollector()
                            }
                            collector?.pushPcm16(pcm, count, channels)
                        }
                    }
                }
            }
        }

        // Seek container to the keyframe at or just before window.startMs
        val seekPoints = session.seekMap.getSeekPoints(startUs)
        var currentPos = seekPoints.first.position.coerceAtLeast(0L)
        session.extractor.seek(currentPos, seekPoints.first.timeUs)

        dataSource.close()
        reopenAt(dataSource, uri, currentPos)
        var extractorInput = DefaultExtractorInput(dataSource, currentPos, totalLength)
        val positionHolder = PositionHolder()

        var readIterations = 0
        val maxReadIterations = 1_000_000

        while (!windowFinished && readIterations < maxReadIterations) {
            readIterations++
            val readResult = try {
                session.extractor.read(extractorInput, positionHolder)
            } catch (_: EOFException) {
                Extractor.RESULT_END_OF_INPUT
            }

            when (readResult) {
                Extractor.RESULT_CONTINUE -> Unit
                Extractor.RESULT_SEEK -> {
                    currentPos = positionHolder.position.coerceAtLeast(0L)
                    dataSource.close()
                    reopenAt(dataSource, uri, currentPos)
                    extractorInput = DefaultExtractorInput(dataSource, currentPos, totalLength)
                }
                Extractor.RESULT_END_OF_INPUT -> break
            }
        }

        targetTrackOutput.packetListener = null
        return collector?.buildEnvelope() ?: FloatArray(0)
    }

    private fun reopenAt(dataSource: DataSource, uri: Uri, position: Long) {
        val spec = DataSpec.Builder()
            .setUri(uri)
            .setPosition(position)
            .build()
        dataSource.open(spec)
    }

    private class AudioTrackEntry(
        val trackId: Int,
        val trackOutput: AudioCaptureTrackOutput,
    ) {
        var format: Format? = null
    }

    private data class ProbeSession(
        val extractor: Extractor,
        val seekMap: SeekMap,
        val audioEntries: List<AudioTrackEntry>,
    )

    private fun openExtractorAndProbe(
        dataSource: DataSource,
        uri: Uri,
        totalLength: Long,
    ): ProbeSession {
        val extractors = DefaultExtractorsFactory().createExtractors(uri, emptyMap())
        var matched: Extractor? = null

        for (candidate in extractors) {
            val input = DefaultExtractorInput(dataSource, 0L, totalLength)
            val ok = try {
                candidate.sniff(input)
            } catch (_: Exception) {
                false
            }
            if (ok) {
                matched = candidate
                break
            } else {
                candidate.release()
                dataSource.close()
                reopenAt(dataSource, uri, 0L)
            }
        }

        val extractor = matched
            ?: throw IOException("Format container video tidak dikenali. Gunakan file MP4, MKV, WebM, atau AVI.")

        dataSource.close()
        reopenAt(dataSource, uri, 0L)

        var discoveredSeekMap: SeekMap? = null
        var tracksEnded = false
        val audioEntries = mutableListOf<AudioTrackEntry>()
        val discardTrackOutput = DiscardTrackOutput()

        extractor.init(object : ExtractorOutput {
            override fun track(id: Int, type: Int): TrackOutput {
                if (type == C.TRACK_TYPE_AUDIO) {
                    lateinit var entry: AudioTrackEntry
                    val output = AudioCaptureTrackOutput { fmt ->
                        if (entry.format == null) {
                            entry.format = fmt
                        }
                    }
                    entry = AudioTrackEntry(id, output)
                    audioEntries.add(entry)
                    return output
                }
                return discardTrackOutput
            }

            override fun endTracks() {
                tracksEnded = true
            }

            override fun seekMap(seekMap: SeekMap) {
                discoveredSeekMap = seekMap
            }
        })

        var extractorInput = DefaultExtractorInput(dataSource, 0L, totalLength)
        val positionHolder = PositionHolder()
        var steps = 0

        fun allAudioFormatsReady(): Boolean =
            tracksEnded && audioEntries.isNotEmpty() && audioEntries.all { it.format != null }

        while ((discoveredSeekMap == null || !allAudioFormatsReady()) && steps < 6000) {
            steps++
            val res = try {
                extractor.read(extractorInput, positionHolder)
            } catch (_: EOFException) {
                Extractor.RESULT_END_OF_INPUT
            }
            when (res) {
                Extractor.RESULT_CONTINUE -> Unit
                Extractor.RESULT_SEEK -> {
                    val pos = positionHolder.position.coerceAtLeast(0L)
                    dataSource.close()
                    reopenAt(dataSource, uri, pos)
                    extractorInput = DefaultExtractorInput(dataSource, pos, totalLength)
                }
                Extractor.RESULT_END_OF_INPUT -> break
            }
        }

        val seekMap = discoveredSeekMap
            ?: throw IOException("Tidak dapat membaca timeline/seek map dari file video ini.")

        val validAudioEntries = audioEntries.filter { it.format != null }

        return ProbeSession(
            extractor = extractor,
            seekMap = seekMap,
            audioEntries = validAudioEntries,
        )
    }

    private fun createBestAudioDecoder(format: Format): AudioPacketDecoder {
        val mime = format.sampleMimeType ?: ""

        if (mime == MimeTypes.AUDIO_RAW) {
            return RawPcmDecoder(
                channels = format.channelCount.coerceAtLeast(1),
                sampleRate = format.sampleRate.coerceAtLeast(8000),
            )
        }

        val preferFfmpeg = mime in setOf(
            MimeTypes.AUDIO_AC3,
            MimeTypes.AUDIO_E_AC3,
            MimeTypes.AUDIO_E_AC3_JOC,
            MimeTypes.AUDIO_DTS,
            MimeTypes.AUDIO_DTS_HD,
            MimeTypes.AUDIO_DTS_X,
            MimeTypes.AUDIO_TRUEHD,
            "audio/mlp",
            MimeTypes.AUDIO_ALAC,
        )

        if (preferFfmpeg && SynctraFfmpegPcmBridge.supportsMimeType(mime)) {
            runCatching { return FfmpegPacketDecoder(SynctraFfmpegPcmBridge.create(format)) }
        }

        runCatching { return PlatformMediaCodecDecoder(format) }

        if (SynctraFfmpegPcmBridge.supportsMimeType(mime)) {
            return FfmpegPacketDecoder(SynctraFfmpegPcmBridge.create(format))
        }

        throw IOException("Codec audio (${friendlyCodecLabel(mime)}) belum didukung di perangkat ini.")
    }

    private fun friendlyCodecLabel(mime: String): String = when (mime) {
        MimeTypes.AUDIO_AAC -> "AAC"
        MimeTypes.AUDIO_AC3 -> "AC-3"
        MimeTypes.AUDIO_E_AC3, MimeTypes.AUDIO_E_AC3_JOC -> "E-AC-3"
        MimeTypes.AUDIO_DTS, MimeTypes.AUDIO_DTS_HD, MimeTypes.AUDIO_DTS_X -> "DTS"
        MimeTypes.AUDIO_TRUEHD -> "TrueHD"
        MimeTypes.AUDIO_OPUS -> "Opus"
        MimeTypes.AUDIO_VORBIS -> "Vorbis"
        MimeTypes.AUDIO_MPEG -> "MP3"
        MimeTypes.AUDIO_FLAC -> "FLAC"
        MimeTypes.AUDIO_ALAC -> "ALAC"
        MimeTypes.AUDIO_RAW -> "PCM"
        else -> mime.substringAfter('/').uppercase().ifBlank { "Audio" }
    }

    // --- Decoder abstraction ---

    private interface AudioPacketDecoder : AutoCloseable {
        val isFfmpeg: Boolean
        fun flush()
        fun decode(
            data: ByteArray,
            size: Int,
            ptsUs: Long,
            onPcm16: (samples: ShortArray, sampleCount: Int, channels: Int, sampleRate: Int, outPtsUs: Long) -> Unit,
        )
    }

    private class FfmpegPacketDecoder(
        private val bridge: SynctraFfmpegPcmBridge,
    ) : AudioPacketDecoder {
        override val isFfmpeg: Boolean = true
        override fun flush() = bridge.flush()
        override fun decode(
            data: ByteArray,
            size: Int,
            ptsUs: Long,
            onPcm16: (ShortArray, Int, Int, Int, Long) -> Unit,
        ) = bridge.decodePacket(data, size, ptsUs, onPcm16)

        override fun close() = bridge.close()
    }

    private class RawPcmDecoder(
        private val channels: Int,
        private val sampleRate: Int,
    ) : AudioPacketDecoder {
        override val isFfmpeg: Boolean = false
        private var pcmScratch = ShortArray(8192)
        override fun flush() = Unit
        override fun decode(
            data: ByteArray,
            size: Int,
            ptsUs: Long,
            onPcm16: (ShortArray, Int, Int, Int, Long) -> Unit,
        ) {
            val count = size / 2
            if (count <= 0) return
            if (pcmScratch.size < count) pcmScratch = ShortArray(count)
            ByteBuffer.wrap(data, 0, size).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(pcmScratch, 0, count)
            onPcm16(pcmScratch, count, channels, sampleRate, ptsUs)
        }

        override fun close() = Unit
    }

    private class PlatformMediaCodecDecoder(format: Format) : AudioPacketDecoder {
        override val isFfmpeg: Boolean = false
        private val codec: MediaCodec
        private var outChannels = format.channelCount.coerceAtLeast(1)
        private var outSampleRate = format.sampleRate.coerceAtLeast(8000)
        private val bufferInfo = MediaCodec.BufferInfo()
        private var pcmScratch = ShortArray(8192)

        init {
            val mime = format.sampleMimeType
                ?: throw IOException("MIME audio kosong.")
            val mediaFormat = MediaFormat.createAudioFormat(
                mime,
                outSampleRate,
                outChannels,
            )
            format.initializationData.forEachIndexed { index, csd ->
                mediaFormat.setByteBuffer("csd-$index", ByteBuffer.wrap(csd))
            }
            codec = MediaCodec.createDecoderByType(mime)
            try {
                codec.configure(mediaFormat, null, null, 0)
                codec.start()
            } catch (e: Exception) {
                codec.release()
                throw e
            }
        }

        override fun flush() {
            codec.flush()
        }

        override fun decode(
            data: ByteArray,
            size: Int,
            ptsUs: Long,
            onPcm16: (ShortArray, Int, Int, Int, Long) -> Unit,
        ) {
            if (size <= 0) return

            var queued = false
            for (attempt in 0 until 10) {
                drainOutputs(onPcm16)
                val inIdx = codec.dequeueInputBuffer(5_000L)
                if (inIdx >= 0) {
                    val inBuf = codec.getInputBuffer(inIdx)
                    if (inBuf != null && inBuf.capacity() >= size) {
                        inBuf.clear()
                        inBuf.put(data, 0, size)
                        codec.queueInputBuffer(inIdx, 0, size, ptsUs, 0)
                    } else {
                        codec.queueInputBuffer(inIdx, 0, 0, ptsUs, 0)
                    }
                    queued = true
                    break
                }
            }

            if (queued) {
                drainOutputs(onPcm16)
            }
        }

        private fun drainOutputs(
            onPcm16: (ShortArray, Int, Int, Int, Long) -> Unit,
        ) {
            while (true) {
                val outIdx = codec.dequeueOutputBuffer(bufferInfo, 0L)
                when {
                    outIdx >= 0 -> {
                        val outBuf = codec.getOutputBuffer(outIdx)
                        if (outBuf != null && bufferInfo.size >= 2) {
                            outBuf.position(bufferInfo.offset)
                            outBuf.limit(bufferInfo.offset + bufferInfo.size)
                            val count = bufferInfo.size / 2
                            if (pcmScratch.size < count) pcmScratch = ShortArray(count)
                            outBuf.slice().order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(pcmScratch, 0, count)
                            onPcm16(pcmScratch, count, outChannels, outSampleRate, bufferInfo.presentationTimeUs)
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                    }
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val fmt = codec.outputFormat
                        if (fmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                            outChannels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
                        }
                        if (fmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                            outSampleRate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE).coerceAtLeast(8000)
                        }
                    }
                    else -> break
                }
            }
        }

        override fun close() {
            runCatching { codec.stop() }
            runCatching { codec.release() }
        }
    }

    // --- Media3 TrackOutput helpers ---

    private class AudioCaptureTrackOutput(
        private val onFormatDiscovered: (Format) -> Unit,
    ) : TrackOutput {
        private val packetAccumulator = ByteArrayOutputStream(16 * 1024)
        private val scratch = ByteArray(16 * 1024)
        var packetListener: ((data: ByteArray, size: Int, ptsUs: Long) -> Unit)? = null

        fun reset() {
            packetAccumulator.reset()
        }

        override fun format(format: Format) {
            onFormatDiscovered(format)
        }

        override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int {
            val toRead = length.coerceAtMost(scratch.size)
            val bytesRead = input.read(scratch, 0, toRead)
            if (bytesRead == C.RESULT_END_OF_INPUT) {
                if (allowEndOfInput) return C.RESULT_END_OF_INPUT
                throw EOFException()
            }
            if (packetListener != null && bytesRead > 0) {
                packetAccumulator.write(scratch, 0, bytesRead)
            }
            return bytesRead
        }

        override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
            if (packetListener != null && length > 0) {
                if (length <= scratch.size) {
                    data.readBytes(scratch, 0, length)
                    packetAccumulator.write(scratch, 0, length)
                } else {
                    val tmp = ByteArray(length)
                    data.readBytes(tmp, 0, length)
                    packetAccumulator.write(tmp, 0, length)
                }
            } else {
                data.skipBytes(length)
            }
        }

        override fun sampleMetadata(
            timeUs: Long,
            flags: Int,
            size: Int,
            offset: Int,
            cryptoData: TrackOutput.CryptoData?,
        ) {
            val listener = packetListener
            val bytes = packetAccumulator.toByteArray()
            packetAccumulator.reset()

            if (bytes.isEmpty()) return

            val sampleEnd = (bytes.size - offset).coerceIn(0, bytes.size)
            val sampleStart = (sampleEnd - size).coerceIn(0, sampleEnd)
            val sampleLen = sampleEnd - sampleStart

            if (listener != null && sampleLen > 0) {
                if (sampleStart == 0 && sampleLen == bytes.size) {
                    listener(bytes, sampleLen, timeUs)
                } else {
                    val slice = bytes.copyOfRange(sampleStart, sampleEnd)
                    listener(slice, slice.size, timeUs)
                }
            }

            if (offset > 0 && sampleEnd < bytes.size) {
                packetAccumulator.write(bytes, sampleEnd, bytes.size - sampleEnd)
            }
        }
    }

    private class DiscardTrackOutput : TrackOutput {
        private val scratch = ByteArray(16 * 1024)

        override fun format(format: Format) = Unit

        override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int {
            val bytesRead = input.read(scratch, 0, length.coerceAtMost(scratch.size))
            if (bytesRead == C.RESULT_END_OF_INPUT) {
                if (allowEndOfInput) return C.RESULT_END_OF_INPUT
                throw EOFException()
            }
            return bytesRead
        }

        override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
            data.skipBytes(length)
        }

        override fun sampleMetadata(
            timeUs: Long,
            flags: Int,
            size: Int,
            offset: Int,
            cryptoData: TrackOutput.CryptoData?,
        ) = Unit
    }
}
