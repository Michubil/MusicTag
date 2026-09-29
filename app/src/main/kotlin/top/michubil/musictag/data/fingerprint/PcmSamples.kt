package top.michubil.musictag.data.fingerprint

import android.media.AudioFormat
import android.media.MediaFormat
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

internal enum class PcmSampleFormat(val bytes: Int) {
    U8(1), S16(2), S24(3), S32(4), F32(4), F64(8),
}

internal data class PcmSettings(val sampleRate: Int, val channels: Int, val samples: PcmSampleFormat) {
    val frameBytes: Int get() = channels * samples.bytes
}

internal fun decoderPcmSettings(output: MediaFormat): PcmSettings {
    val samples = when (output.getInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)) {
        AudioFormat.ENCODING_PCM_8BIT -> PcmSampleFormat.U8
        AudioFormat.ENCODING_PCM_16BIT -> PcmSampleFormat.S16
        AudioFormat.ENCODING_PCM_24BIT_PACKED -> PcmSampleFormat.S24
        AudioFormat.ENCODING_PCM_32BIT -> PcmSampleFormat.S32
        AudioFormat.ENCODING_PCM_FLOAT -> PcmSampleFormat.F32
        else -> error("解码器输出了不支持的 PCM 格式")
    }
    return PcmSettings(output.getInteger(MediaFormat.KEY_SAMPLE_RATE),
        output.getInteger(MediaFormat.KEY_CHANNEL_COUNT), samples).also {
        require(it.sampleRate > 0 && it.channels in 1..2) { "解码器输出的采样率或声道数不受支持" }
    }
}

internal fun wavPcmSettings(source: RandomAccessFile, offset: Long, size: Long): PcmSettings {
    require(size >= 16) { "WAV 音轨格式无效" }
    val bytes = ByteArray(minOf(size, 40).toInt())
    source.seek(offset)
    source.readFully(bytes)
    val header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    var encoding = header.short.toInt() and 0xffff
    val channels = header.short.toInt() and 0xffff
    val sampleRate = header.int
    header.int // Byte rate.
    val blockAlign = header.short.toInt() and 0xffff
    val bits = header.short.toInt() and 0xffff
    if (encoding == 0xfffe) {
        require(size >= 40 && (header.short.toInt() and 0xffff) >= 22) { "WAV 扩展音轨格式无效" }
        val validBits = header.short.toInt() and 0xffff
        header.int // Channel mask.
        encoding = header.short.toInt() and 0xffff
        require(validBits in 1..bits && bytes.copyOfRange(26, 40).contentEquals(
            byteArrayOf(0, 0, 0, 0, 0x10, 0, 0x80.toByte(), 0, 0, 0xaa.toByte(), 0, 0x38, 0x9b.toByte(), 0x71))) {
            "WAV 扩展音轨格式不受支持"
        }
    }
    val samples = when (encoding to bits) {
        1 to 8 -> PcmSampleFormat.U8
        1 to 16 -> PcmSampleFormat.S16
        1 to 24 -> PcmSampleFormat.S24
        1 to 32 -> PcmSampleFormat.S32
        3 to 32 -> PcmSampleFormat.F32
        3 to 64 -> PcmSampleFormat.F64
        else -> error("WAV PCM 编码或位深不受支持")
    }
    return PcmSettings(sampleRate, channels, samples).also {
        require(sampleRate > 0 && channels in 1..2 && blockAlign == it.frameBytes) {
            "WAV 音轨格式不受支持"
        }
    }
}

/** Chromaprint consumes interleaved signed 16-bit samples in native little-endian order. */
internal fun pcmToS16(input: ByteBuffer, offset: Int, sampleCount: Int,
                      format: PcmSampleFormat, output: ByteBuffer): ByteBuffer {
    require(offset >= 0 && sampleCount >= 0 && sampleCount.toLong() * format.bytes <= input.capacity() - offset &&
        sampleCount.toLong() * 2 <= output.capacity()) { "PCM 缓冲区长度无效" }
    val source = input.duplicate().order(ByteOrder.LITTLE_ENDIAN)
    source.position(offset)
    output.clear()
    output.order(ByteOrder.LITTLE_ENDIAN)
    repeat(sampleCount) {
        val value = when (format) {
            PcmSampleFormat.U8 -> ((source.get().toInt() and 0xff) - 128) shl 8
            PcmSampleFormat.S16 -> source.short.toInt()
            PcmSampleFormat.S24 -> {
                source.get()
                val middle = source.get().toInt() and 0xff
                (source.get().toInt() shl 8) or middle
            }
            PcmSampleFormat.S32 -> source.int shr 16
            PcmSampleFormat.F32 -> floatToS16(source.float.toDouble())
            PcmSampleFormat.F64 -> floatToS16(source.double)
        }
        output.putShort(value.toShort())
    }
    output.flip()
    return output
}

private fun floatToS16(value: Double): Int = when {
    value.isNaN() -> 0
    value <= -1.0 -> -32768
    value >= 1.0 -> 32767
    else -> (value * 32768.0).roundToInt().coerceIn(-32768, 32767)
}
