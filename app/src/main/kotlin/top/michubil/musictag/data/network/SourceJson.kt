package top.michubil.musictag.data.network

import org.json.JSONArray
import org.json.JSONObject

internal fun JSONObject.string(key: String): String? =
    (opt(key) as? String)?.trim()?.takeIf(String::isNotEmpty)

/** Accept integer numbers and decimal integer strings without coercing invalid values to zero. */
internal fun JSONObject.long(key: String): Long? = when (val value = opt(key)) {
    is Number -> value.toString().toLongOrNull()
    is String -> value.trim().toLongOrNull()
    else -> null
}

internal fun JSONObject.boolean(key: String): Boolean? = opt(key) as? Boolean

internal fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else
    (0 until length()).mapNotNull(::optJSONObject)
