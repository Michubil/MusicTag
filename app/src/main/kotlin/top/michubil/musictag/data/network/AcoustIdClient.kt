package top.michubil.musictag.data.network

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import top.michubil.musictag.BuildConfig
import top.michubil.musictag.data.fingerprint.AudioFingerprint
import java.net.URLEncoder
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

internal class AcoustIdClient(
    private val transport: MusicTransport = MusicHttp,
    private val clientKey: String = BuildConfig.ACOUSTID_CLIENT_KEY,
) {
    fun requireConfigured() {
        check(clientKey.isNotBlank()) { "当前安装包未配置音频指纹服务" }
    }

    suspend fun lookup(fingerprint: AudioFingerprint): List<FingerprintSuggestion> {
        requireConfigured()
        val body = mapOf(
            "client" to clientKey,
            "duration" to fingerprint.durationSeconds.toString(),
            "fingerprint" to fingerprint.value,
            "meta" to "recordings compress",
            "format" to "json",
        ).entries.joinToString("&") { (key, value) ->
            "$key=${URLEncoder.encode(value, Charsets.UTF_8)}"
        }.toByteArray(Charsets.UTF_8)
        val response = rateGate.withLock {
            val wait = 350.milliseconds - lastRequest.elapsedNow()
            if (wait.isPositive()) delay(wait.inWholeMilliseconds)
            lastRequest = TimeSource.Monotonic.markNow()
            transport.json("https://api.acoustid.org/v2/lookup", "https://acoustid.org/",
                "AcoustID 指纹查询", body, "application/x-www-form-urlencoded")
        }
        check(response.string("status") == "ok") {
            response.optJSONObject("error")?.string("message") ?: "AcoustID 查询失败"
        }
        return parseSuggestions(response)
    }

    companion object {
        private val rateGate = Mutex()
        private var lastRequest = TimeSource.Monotonic.markNow() - 350.milliseconds

        internal fun parseSuggestions(response: JSONObject): List<FingerprintSuggestion> {
            val suggestions = response.optJSONArray("results").objects().flatMap { result ->
                val score = (result.opt("score") as? Number)?.toDouble() ?: return@flatMap emptyList()
                if (!score.isFinite() || score !in 0.0..1.0) return@flatMap emptyList()
                result.optJSONArray("recordings").objects().mapNotNull { recording ->
                    val title = recording.string("title") ?: return@mapNotNull null
                    val artists = recording.optJSONArray("artists").objects().mapNotNull { it.string("name") }
                    FingerprintSuggestion(title, artists, score, recording.string("id"))
                }
            }.sortedByDescending { it.score }
            val identifiedNames = suggestions.filter { it.recordingId != null }
                .map { it.title to it.artists }.toSet()
            return suggestions.filter { it.recordingId != null || (it.title to it.artists) !in identifiedNames }
                .distinctBy { it.recordingId ?: (it.title to it.artists) }
                .take(8)
        }
    }
}
