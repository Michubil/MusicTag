package top.michubil.musictag.data.network

import top.michubil.musictag.data.model.CoverImages
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import top.michubil.musictag.data.model.CoverImage
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI

internal object MusicHttp {
    suspend fun json(url: String, referer: String, label: String, body: ByteArray? = null,
        contentType: String = "application/json"): JSONObject = JSONObject(
        request(URI(url), referer, label, 4 * 1024 * 1024, body, contentType).toString(Charsets.UTF_8),
    )

    suspend fun cover(rawUrl: String, referer: String, trustedHost: (String) -> Boolean): CoverImage {
        val uri = URI(rawUrl.replaceFirst("http://", "https://"))
        require(uri.scheme == "https" && uri.userInfo == null && trustedHost(uri.host.orEmpty().lowercase())) {
            "封面来源不受信任"
        }
        return CoverImages.read(request(uri, referer, "封面下载", CoverImages.MAX_BYTES))
    }

    private suspend fun request(uri: URI, referer: String, label: String, limit: Int,
        body: ByteArray? = null, contentType: String = "application/json"): ByteArray = withContext(Dispatchers.IO) {
        currentCoroutineContext().ensureActive()
        require(uri.scheme == "https")
        val connection = (uri.toURL().openConnection() as HttpURLConnection).apply {
            requestMethod = if (body == null) "GET" else "POST"
            instanceFollowRedirects = false
            connectTimeout = 10_000
            readTimeout = 15_000
            setRequestProperty("Referer", referer)
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 17) MusicTag/${top.michubil.musictag.BuildConfig.VERSION_NAME}")
            if (body != null) {
                doOutput = true
                setFixedLengthStreamingMode(body.size)
                setRequestProperty("Content-Type", contentType)
            }
        }
        try {
            if (body != null) connection.outputStream.use { it.write(body) }
            check(connection.responseCode in 200..299) { "$label 请求失败：HTTP ${connection.responseCode}" }
            require(connection.contentLengthLong <= limit) { "$label 响应内容过大" }
            connection.inputStream.use { input ->
                val output = ByteArrayOutputStream(minOf(limit, 64 * 1024))
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(output.size() + count <= limit) { "$label 响应内容过大" }
                    output.write(buffer, 0, count)
                }
                currentCoroutineContext().ensureActive()
                output.toByteArray()
            }
        } finally {
            connection.disconnect()
        }
    }

}
