package top.michubil.musictag.data

import org.json.JSONException
import org.json.JSONObject
import top.michubil.musictag.data.network.MusicHttp
import java.net.URI

data class AppRelease(val versionName: String, val downloadUrl: String)

internal data class GitHubAsset(val name: String, val url: String)

internal object AppUpdate {
    private const val LatestReleaseUrl = "https://api.github.com/repos/Michubil/MusicTag/releases/latest"
    private val TagPattern = Regex("v[0-9]+\\.[0-9]+\\.[0-9]+")

    suspend fun newerRelease(currentVersion: String): AppRelease? {
        val latest = latestRelease() ?: return null
        return latest.takeIf { isNewerThan(it.versionName, currentVersion) }
    }

    internal suspend fun latestRelease(): AppRelease? {
        val uri = URI(LatestReleaseUrl)
        require(uri.scheme == "https" && uri.userInfo == null && uri.host.equals("api.github.com", true))
        val payload = try {
            MusicHttp.getJson(LatestReleaseUrl, "检查更新", mapOf("Accept" to "application/vnd.github+json"))
        } catch (error: MusicHttp.StatusException) {
            if (error.code == 404) return null else throw error
        }
        return try {
            parseGitHubRelease(payload)
        } catch (error: JSONException) {
            throw IllegalStateException("无法解析更新信息")
        }
    }

    internal fun parseGitHubRelease(json: JSONObject): AppRelease {
        val assets = json.optJSONArray("assets")
        return releaseFrom(
            json.optString("tag_name"),
            if (assets == null) emptyList() else buildList {
                for (index in 0 until assets.length()) {
                    val item = assets.optJSONObject(index) ?: continue
                    add(GitHubAsset(item.optString("name"), item.optString("browser_download_url")))
                }
            },
        )
    }

    internal fun releaseFrom(tag: String, assets: List<GitHubAsset>): AppRelease {
        require(tag.matches(TagPattern)) { "发布标签无效" }
        val versionName = tag.removePrefix("v")
        val expectedName = "MusicTag-v$versionName.apk"
        val downloadUrl = assets.singleOrNull { it.name == expectedName && isTrustedDownloadUrl(it.url, tag, expectedName) }?.url
        requireNotNull(downloadUrl) { "发布信息缺少安装包" }
        return AppRelease(versionName, downloadUrl)
    }

    internal fun isNewerThan(candidate: String, current: String): Boolean {
        val left = versionParts(candidate)
        val right = versionParts(current)
        val size = maxOf(left.size, right.size)
        for (index in 0 until size) {
            val comparison = left.getOrElse(index) { 0 }.compareTo(right.getOrElse(index) { 0 })
            if (comparison != 0) return comparison > 0
        }
        return false
    }

    internal fun isTrustedDownloadUrl(url: String, tag: String, apkName: String): Boolean = runCatching {
        val uri = URI(url)
        uri.scheme == "https" && uri.userInfo == null && uri.host.equals("github.com", true) &&
            uri.rawQuery == null && uri.rawFragment == null &&
            uri.path == "/Michubil/MusicTag/releases/download/$tag/$apkName"
    }.getOrDefault(false)

    private fun versionParts(version: String): List<Int> =
        version.trim().removePrefix("v").removePrefix("V").split('.')
            .map { part -> part.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
}
