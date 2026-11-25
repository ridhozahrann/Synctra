package com.synctra.app

import androidx.media3.common.C
import com.synctra.app.data.file.DocumentMetaReader
import com.synctra.app.data.media.AudioSampleExtractor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class DataHelpersTest {

    @Test
    fun readBoundedBytesAbortsImmediatelyWhenStreamExceedsLimit() {
        var totalConsumed = 0L
        val infiniteStream = object : InputStream() {
            override fun read(): Int {
                totalConsumed++
                return 0x41
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                totalConsumed += len
                if (totalConsumed > 200_000L) {
                    fail("Stream was read far past maxBytes limit ($totalConsumed bytes)")
                }
                return len
            }
        }

        try {
            DocumentMetaReader.readBoundedBytes(infiniteStream, maxBytes = 64L * 1024L)
            fail("Expected IOException when stream exceeds maxBytes")
        } catch (expected: IOException) {
            assertTrue(totalConsumed <= 80_000L)
        }
    }

    @Test
    fun decodeRawPcmTo16BitHandlesAllSupportedPcmEncodings() {
        val scratch = ShortArray(16)

        // 16-bit LE: [0, 32767, -32768]
        val pcm16Le = ByteBuffer.allocate(6).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(0).putShort(32767).putShort(-32768).array()
        val (_, c16Le) = AudioSampleExtractor.decodeRawPcmTo16Bit(pcm16Le, pcm16Le.size, C.ENCODING_PCM_16BIT, scratch)
        assertEquals(3, c16Le)
        assertEquals(0.toShort(), scratch[0])
        assertEquals(32767.toShort(), scratch[1])
        assertEquals((-32768).toShort(), scratch[2])

        // 16-bit BE: [12345, -12345]
        val pcm16Be = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN)
            .putShort(12345).putShort(-12345).array()
        val (_, c16Be) = AudioSampleExtractor.decodeRawPcmTo16Bit(pcm16Be, pcm16Be.size, C.ENCODING_PCM_16BIT_BIG_ENDIAN, scratch)
        assertEquals(2, c16Be)
        assertEquals(12345.toShort(), scratch[0])
        assertEquals((-12345).toShort(), scratch[1])

        // 8-bit unsigned: [0 -> -32768, 128 -> 0, 255 -> 32512]
        val pcm8 = byteArrayOf(0x00.toByte(), 0x80.toByte(), 0xFF.toByte())
        val (_, c8) = AudioSampleExtractor.decodeRawPcmTo16Bit(pcm8, pcm8.size, C.ENCODING_PCM_8BIT, scratch)
        assertEquals(3, c8)
        assertEquals((-32768).toShort(), scratch[0])
        assertEquals(0.toShort(), scratch[1])
        assertEquals(32512.toShort(), scratch[2])

        // 24-bit LE: 0x00, 0x10, 0x40 -> top 16 bits 0x4010 = 16400
        val pcm24Le = byteArrayOf(0x00, 0x10, 0x40, 0x00, 0x00, 0x80.toByte())
        val (_, c24Le) = AudioSampleExtractor.decodeRawPcmTo16Bit(pcm24Le, pcm24Le.size, C.ENCODING_PCM_24BIT, scratch)
        assertEquals(2, c24Le)
        assertEquals(0x4010.toShort(), scratch[0])
        assertEquals((-32768).toShort(), scratch[1])

        // 32-bit LE: 0x00, 0x00, 0x20, 0x7F -> top 16 bits 0x7F20 = 32544
        val pcm32Le = byteArrayOf(0x00, 0x00, 0x20, 0x7F, 0x00, 0x00, 0x00, 0x80.toByte())
        val (_, c32Le) = AudioSampleExtractor.decodeRawPcmTo16Bit(pcm32Le, pcm32Le.size, C.ENCODING_PCM_32BIT, scratch)
        assertEquals(2, c32Le)
        assertEquals(0x7F20.toShort(), scratch[0])
        assertEquals((-32768).toShort(), scratch[1])

        // 32-bit float LE: [1.0f -> 32767, -1.0f -> -32767, 0.0f -> 0]
        val pcmFloat = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
            .putFloat(1.0f).putFloat(-1.0f).putFloat(0.0f).array()
        val (_, cFloat) = AudioSampleExtractor.decodeRawPcmTo16Bit(pcmFloat, pcmFloat.size, C.ENCODING_PCM_FLOAT, scratch)
        assertEquals(3, cFloat)
        assertEquals(32767.toShort(), scratch[0])
        assertEquals((-32767).toShort(), scratch[1])
        assertEquals(0.toShort(), scratch[2])
    }
}
