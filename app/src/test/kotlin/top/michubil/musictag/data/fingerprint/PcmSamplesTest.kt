package top.michubil.musictag.data.fingerprint

import android.media.AudioFormat
import android.media.MediaFormat
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class PcmSamplesTest {
    @Test
    fun standardTwentyFourBitWavIsSupported() {
        val file = File.createTempFile("pcm-format-", ".bin")
        try {
            val header = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
            header.putShort(1).putShort(2).putInt(44_100).putInt(264_600).putShort(6).putShort(24)
            file.writeBytes(header.array())
            RandomAccessFile(file, "r").use { source ->
                val pcm = wavPcmSettings(source, 0, 16)
                assertEquals(PcmSampleFormat.S24, pcm.samples)
                assertEquals(44_100, pcm.sampleRate)
                assertEquals(2, pcm.channels)
            }
        } finally {
            file.delete()
        }
    }

    @Test
    fun integerAndFloatPcmConvertToInterleavedSigned16Bit() {
        fun check(format: PcmSampleFormat, input: ByteBuffer, expected: List<Int>) {
            val converted = pcmToS16(input, 1, expected.size, format,
                ByteBuffer.allocateDirect(expected.size * 2))
            assertEquals(expected, List(expected.size) { converted.short.toInt() })
        }
        check(PcmSampleFormat.U8, bytes(0, 0, 128, 255), listOf(-32768, 0, 32512))
        check(PcmSampleFormat.S16, bytes(0, 0, 128, 255, 127), listOf(-32768, 32767))
        check(PcmSampleFormat.S24, bytes(0, 0, 0, 128, 255, 255, 127), listOf(-32768, 32767))
        check(PcmSampleFormat.S32, bytes(0, 0, 0, 0, 128, 255, 255, 255, 127), listOf(-32768, 32767))
        val floats = ByteBuffer.allocateDirect(1 + 5 * 4).order(ByteOrder.LITTLE_ENDIAN)
        floats.put(0).putFloat(-1f).putFloat(0f).putFloat(1f).putFloat(Float.NaN).putFloat(2f)
        check(PcmSampleFormat.F32, floats, listOf(-32768, 0, 32767, 0, 32767))
        val doubles = ByteBuffer.allocateDirect(1 + 3 * 8).order(ByteOrder.LITTLE_ENDIAN)
        doubles.put(0).putDouble(-1.0).putDouble(0.5).putDouble(1.0)
        check(PcmSampleFormat.F64, doubles, listOf(-32768, 16384, 32767))
    }

    @Test
    fun decoderOutputEncodingIsUsedInsteadOfRequestedEncoding() {
        val mappings = listOf(
            AudioFormat.ENCODING_PCM_8BIT to PcmSampleFormat.U8,
            AudioFormat.ENCODING_PCM_16BIT to PcmSampleFormat.S16,
            AudioFormat.ENCODING_PCM_24BIT_PACKED to PcmSampleFormat.S24,
            AudioFormat.ENCODING_PCM_32BIT to PcmSampleFormat.S32,
            AudioFormat.ENCODING_PCM_FLOAT to PcmSampleFormat.F32,
        )
        mappings.forEach { (encoding, expected) ->
            val output = MediaFormat.createAudioFormat("audio/raw", 48_000, 2)
            output.setInteger(MediaFormat.KEY_PCM_ENCODING, encoding)
            assertEquals(expected, decoderPcmSettings(output).samples)
        }
    }

    @Test
    fun waveExtensibleUsesItsPcmSubformatAndRejectsUnknownEncoding() {
        val file = File.createTempFile("pcm-format-", ".bin")
        try {
            val header = ByteBuffer.allocate(40).order(ByteOrder.LITTLE_ENDIAN)
            header.putShort(0xfffe.toShort()).putShort(2).putInt(44_100).putInt(264_600)
                .putShort(6).putShort(24).putShort(22).putShort(24).putInt(3)
                .put(byteArrayOf(1, 0, 0, 0, 0, 0, 0x10, 0, 0x80.toByte(), 0, 0, 0xaa.toByte(), 0, 0x38, 0x9b.toByte(), 0x71))
            file.writeBytes(header.array())
            RandomAccessFile(file, "r").use { source ->
                assertEquals(PcmSampleFormat.S24, wavPcmSettings(source, 0, 40).samples)
            }
            header.put(24, 2)
            file.writeBytes(header.array())
            RandomAccessFile(file, "r").use { source ->
                assertThrows(IllegalStateException::class.java) { wavPcmSettings(source, 0, 40) }
            }
        } finally {
            file.delete()
        }
    }

    private fun bytes(vararg values: Int): ByteBuffer = ByteBuffer.allocateDirect(values.size).also { buffer ->
        values.forEach { buffer.put(it.toByte()) }
    }
}
