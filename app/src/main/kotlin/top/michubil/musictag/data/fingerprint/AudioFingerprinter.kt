package top.michubil.musictag.data.fingerprint

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import top.michubil.musictag.data.wav.WavCodec
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

internal object NativeFingerprint {
    init { System.loadLibrary("musictag_fingerprint") }

    external fun create(sampleRate: Int, channels: Int): Long
    external fun feed(handle: Long, buffer: java.nio.ByteBuffer, offset: Int, size: Int): Boolean
    external fun finish(handle: Long): String?
    external fun release(handle: Long)
}

internal object AudioFingerprinter {
    suspend fun calculate(file: File, knownDurationMs: Long?): AudioFingerprint = withContext(Dispatchers.Default) {
        if (file.extension.equals("wav", ignoreCase = true)) return@withContext calculateWav(file, knownDurationMs)
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        var codecStarted = false
        var handle = 0L
        try {
            extractor.setDataSource(file.absolutePath)
            val index = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: error("文件没有可解码音轨")
            val format = extractor.getTrackFormat(index)
            val durationMs = knownDurationMs?.takeIf { it > 0 }
                ?: format.getLong(MediaFormat.KEY_DURATION, 0L).takeIf { it > 0 }?.div(1000)
                ?: error("无法确定完整音频时长")
            val durationSeconds = ((durationMs + 500) / 1000).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            require(durationSeconds >= 10) { "音频过短，无法可靠生成指纹" }
            extractor.selectTrack(index)
            val decoder = MediaCodec.createDecoderByType(requireNotNull(format.getString(MediaFormat.KEY_MIME)))
            codec = decoder
            format.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            decoder.configure(format, null, null, 0)
            decoder.start()
            codecStarted = true

            val info = MediaCodec.BufferInfo()
            val started = TimeSource.Monotonic.markNow()
            var inputDone = false
            var outputDone = false
            var frames = 0L
            var sampleRate = 0
            var channels = 0
            while (!outputDone && started.elapsedNow() < 45.seconds) {
                currentCoroutineContext().ensureActive()
                if (!inputDone) {
                    val inputIndex = decoder.dequeueInputBuffer(10_000)
                    if (inputIndex >= 0) {
                        val buffer = requireNotNull(decoder.getInputBuffer(inputIndex))
                        val count = extractor.readSampleData(buffer, 0)
                        if (count < 0) {
                            decoder.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(inputIndex, 0, count, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outputIndex = decoder.dequeueOutputBuffer(info, 10_000)
                if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val (nextRate, nextChannels) = pcmSettings(decoder.outputFormat)
                    require(handle == 0L || (nextRate == sampleRate && nextChannels == channels)) { "音轨格式在解码中变化" }
                    sampleRate = nextRate
                    channels = nextChannels
                    if (handle == 0L) handle = NativeFingerprint.create(sampleRate, channels)
                    check(handle != 0L) { "无法初始化音频指纹" }
                } else if (outputIndex >= 0) {
                    if (handle == 0L) {
                        val settings = pcmSettings(decoder.outputFormat)
                        sampleRate = settings.first
                        channels = settings.second
                        handle = NativeFingerprint.create(sampleRate, channels)
                        check(handle != 0L) { "无法初始化音频指纹" }
                    }
                    if (info.size > 0) {
                        val buffer = requireNotNull(decoder.getOutputBuffer(outputIndex))
                        require(info.size % (2 * channels) == 0) { "解码音频帧不完整" }
                        val availableFrames = info.size / (2 * channels)
                        val maxFrames = 120L * sampleRate - frames
                        val feedFrames = minOf(availableFrames.toLong(), maxFrames).toInt()
                        if (feedFrames > 0) {
                            check(NativeFingerprint.feed(handle, buffer, info.offset, feedFrames * 2 * channels)) {
                                "无法处理音频样本"
                            }
                            frames += feedFrames
                        }
                        if (frames >= 120L * sampleRate) outputDone = true
                    }
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    decoder.releaseOutputBuffer(outputIndex, false)
                }
            }
            check(started.elapsedNow() < 45.seconds) { "音频解码超时" }
            check(frames >= 10L * sampleRate && handle != 0L) { "音频样本不足" }
            val fingerprint = NativeFingerprint.finish(handle)?.takeIf(String::isNotBlank)
                ?: error("无法生成音频指纹")
            AudioFingerprint(fingerprint, durationSeconds)
        } finally {
            try {
                if (handle != 0L) NativeFingerprint.release(handle)
            } finally {
                try {
                    codec?.run { try { if (codecStarted) stop() } finally { release() } }
                } finally {
                    extractor.release()
                }
            }
        }
    }

    private fun pcmSettings(output: MediaFormat): Pair<Int, Int> {
        require(output.getInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT) ==
            AudioFormat.ENCODING_PCM_16BIT) { "解码器未提供 16 位 PCM" }
        return output.getInteger(MediaFormat.KEY_SAMPLE_RATE) to output.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
    }

    private suspend fun calculateWav(file: File, knownDurationMs: Long?): AudioFingerprint {
        val document = WavCodec.read(file)
        val durationMs = knownDurationMs?.takeIf { it > 0 } ?: document.durationMs
            ?: error("无法确定完整音频时长")
        val durationSeconds = ((durationMs + 500) / 1000).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        require(durationSeconds >= 10) { "音频过短，无法可靠生成指纹" }
        return RandomAccessFile(file, "r").use { source ->
            val format = document.chunks.first { it.id == "fmt " }
            require(format.dataSize >= 16) { "WAV 音轨格式无效" }
            val bytes = ByteArray(16)
            source.seek(format.dataOffset)
            source.readFully(bytes)
            val header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val encoding = header.short.toInt() and 0xffff
            val channels = header.short.toInt() and 0xffff
            val sampleRate = header.int
            header.position(14) // Skip bytes per second and block alignment.
            val bits = header.short.toInt() and 0xffff
            require(encoding == 1 && bits == 16 && channels in 1..2 && sampleRate > 0) {
                "目前仅支持 16 位 PCM WAV 指纹识别"
            }
            val handle = NativeFingerprint.create(sampleRate, channels)
            check(handle != 0L) { "无法初始化音频指纹" }
            try {
                source.seek(document.audioRegion.offset)
                val frameSize = 2 * channels
                val raw = ByteArray(64 * 1024 / frameSize * frameSize)
                val direct = ByteBuffer.allocateDirect(raw.size)
                val maxBytes = minOf(document.audioRegion.length, 120L * sampleRate * frameSize)
                var consumed = 0L
                val started = TimeSource.Monotonic.markNow()
                while (consumed < maxBytes && started.elapsedNow() < 45.seconds) {
                    currentCoroutineContext().ensureActive()
                    val count = minOf(raw.size.toLong(), maxBytes - consumed).toInt()
                    source.readFully(raw, 0, count)
                    direct.clear()
                    direct.put(raw, 0, count)
                    check(NativeFingerprint.feed(handle, direct, 0, count)) { "无法处理音频样本" }
                    consumed += count
                }
                check(started.elapsedNow() < 45.seconds) { "音频解码超时" }
                check(consumed >= 10L * sampleRate * frameSize) { "音频样本不足" }
                val fingerprint = NativeFingerprint.finish(handle)?.takeIf(String::isNotBlank)
                    ?: error("无法生成音频指纹")
                AudioFingerprint(fingerprint, durationSeconds)
            } finally {
                NativeFingerprint.release(handle)
            }
        }
    }
}
