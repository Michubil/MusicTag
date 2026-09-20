package top.michubil.musictag.data.network

import org.json.JSONArray
import org.json.JSONObject

internal fun JSONObject.string(key: String): String? =
    (opt(key) as? String)?.trim()?.takeIf(String::isNotEmpty)

/** Accept integer numbers and decimal integer strings without coercing invalid values to zero. */
internal fun JSONObject.long(key: String): Long? {
    val value = opt(key)
    if (value is Number) return value.toString().toLongOrNull()
    if (value is String) return value.trim().toLongOrNull()
    return null
}

internal fun JSONObject.boolean(key: String): Boolean? = opt(key) as? Boolean

internal fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else
    (0 until length()).mapNotNull(::optJSONObject)
