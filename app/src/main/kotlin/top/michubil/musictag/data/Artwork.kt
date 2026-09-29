package top.michubil.musictag.data

import android.graphics.Bitmap
import android.graphics.ImageDecoder

internal fun decodeArtworkCandidates(candidates: Sequence<ByteArray>, maximumSize: Int): Bitmap? {
    var foundPicture = false
    for (bytes in candidates) {
        foundPicture = true
        decodeArtwork(bytes, maximumSize)?.let { return it }
    }
    // A decode failure must not become a persistent "no artwork" preview.
    check(!foundPicture) { "无法解码封面图片" }
    return null
}

internal fun decodeArtwork(bytes: ByteArray, maximumSize: Int): Bitmap? = try {
    ImageDecoder.decodeBitmap(ImageDecoder.createSource(bytes)) { decoder, info, _ ->
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        val longest = maxOf(info.size.width, info.size.height)
        if (longest > maximumSize) {
            val scale = longest.toFloat() / maximumSize
            decoder.setTargetSize(
                (info.size.width / scale).toInt().coerceAtLeast(1),
                (info.size.height / scale).toInt().coerceAtLeast(1),
            )
        }
    }
} catch (_: Exception) {
    null
}

